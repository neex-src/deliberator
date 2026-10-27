package com.neex.deliberator.services

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.neex.deliberator.daemon.DaemonEvent
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.logs.FetchRequest
import com.neex.deliberator.logs.LogService
import com.neex.deliberator.workspace.WorkspaceService

/** What `service.start` answered: the host ports are where the daemon published the container's ports. */
data class Started(val name: String, val status: String, val container: String?, val port: String?, val jdwp: String?)

/** How a reload ended; [lines] are what was printed (console) or shown (balloon). */
data class ReloadOutcome(val ok: Boolean, val lines: List<String>, val failure: ReloadFailure? = null, val result: JsonObject? = null)

/**
 * The Services tab's blocking calls, one op each, shared by the actions, the run configuration and the tests. Every call goes through
 * [WorkspaceService] (the `ws` is added there); all of them block and belong on a background thread. Mutations refresh the workspace model.
 */
@Service(Service.Level.PROJECT)
class ServiceOps(private val project: Project) {
    private val svc get() = WorkspaceService.getInstance(project)

    private fun args(name: String, vararg kv: Pair<String, String?>) = JsonObject().apply {
        addProperty("name", name)
        kv.forEach { (k, v) -> if (v != null) addProperty(k, v) }
    }

    private fun refreshed(f: () -> JsonObject): JsonObject = try { f() } finally { svc.refreshNow() }

    /** A `confirm-required` answer of a secrets start: [token] repeated as `confirm` is the yes; [summary] is the daemon's text, shown verbatim. */
    data class SecretsConfirmation(val token: String, val summary: String, val step: Int, val of: Int, val env: String?)

    /**
     * Asked for each confirmation of a secrets start; true repeats the start with the token. The default is a modal [SecretsConfirmDialog].
     * Replaced by tests. Called on a background thread.
     */
    @Volatile var confirmer: (SecretsConfirmation) -> Boolean = { c ->
        var yes = false
        ApplicationManager.getApplication().invokeAndWait({ yes = SecretsConfirmDialog(project, c).showAndGet() }, ModalityState.nonModal())
        yes
    }

    /**
     * `service.start`; [extra] is merged into the args (the run configuration's `startArgs()`, with `secrets-from` when its checkbox is on). With
     * `secrets-from` the daemon answers `confirm-required` twice: each is put to [confirmer] and, when confirmed, the start is repeated with `confirm` set to the
     * token (token 1, token 2, start). Declining ends it with `service/cancelled`: nothing was started, no value fetched.
     */
    fun start(name: String, extra: JsonObject = JsonObject(), onEvent: (DaemonEvent) -> Unit = {}, onCall: (com.neex.deliberator.daemon.DaemonClient.Call) -> Unit = {}): Started {
        val a = args(name)
        extra.entrySet().forEach { a.add(it.key, it.value) }
        val secrets = a.has("secrets-from")
        try {
            repeat(MAX_CONFIRMATIONS + 1) {
                val call = svc.start("service.start", a, true, onEvent)
                onCall(call)   // the run configuration keeps it: a Stop during the start waits for this answer before stopping
                val t = call.await()
                if (t.ok) return started(t.resultObject(), name)
                val e = t.error
                if (secrets && t.errorType == "confirm-required" && e != null) {
                    val c = SecretsConfirmation(e.str("token") ?: throw DaemonException("confirm-required without a token", "service/confirm-malformed"),
                        e.str("summary") ?: t.errorMessage ?: "", e.get("step")?.takeIf { it.isJsonPrimitive }?.asInt ?: 1,
                        e.get("of")?.takeIf { it.isJsonPrimitive }?.asInt ?: 2, e.str("env"))
                    if (!confirmer(c)) throw DaemonException("start of $name cancelled at confirmation ${c.step} of ${c.of}: nothing was started and no secret value was fetched", "service/cancelled")
                    a.addProperty("confirm", c.token)
                } else {
                    throw DaemonException(t.errorMessage ?: "service.start failed", t.errorType ?: "daemon/error", e?.str("hint"))
                }
            }
            throw DaemonException("the daemon kept asking for confirmation of $name", "service/confirm-loop")
        } finally {
            svc.refreshNow()
        }
    }

    private fun JsonObject.str(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString

    fun restart(name: String, onEvent: (DaemonEvent) -> Unit = {}): Started =
        started(refreshed { svc.call("service.restart", args(name), onEvent).resultObject() }, name)

    fun stop(name: String): JsonObject = refreshed { svc.call("service.stop", args(name)).resultObject() }

    fun build(name: String, onEvent: (DaemonEvent) -> Unit = {}): JsonObject =
        refreshed { svc.call("service.build", args(name), onEvent).resultObject() }

    /** `service.adopt` of the module directory [path] (the description it carries). */
    fun adopt(path: String): JsonObject = refreshed { svc.call("service.adopt", JsonObject().apply { addProperty("path", path) }).resultObject() }

    /** `topology.set` in the selected environment. [mode] is local, external (with [url]) or off. */
    fun setTopology(name: String, mode: String, url: String? = null, containerUrl: String? = null, logContainer: String? = null, fake: String? = null): JsonObject =
        refreshed {
            svc.call("topology.set", args(name, "env" to svc.selectedEnv, "mode" to mode, "url" to url?.takeIf { it.isNotBlank() },
                "container-url" to containerUrl?.takeIf { it.isNotBlank() }, "log-container" to logContainer?.takeIf { it.isNotBlank() },
                "fake" to fake?.takeIf { it.isNotBlank() })).resultObject()
        }

    /** `runtime.ensure`: what the topology changed (stopped / recreated containers) takes effect. */
    fun ensure(onEvent: (DaemonEvent) -> Unit = {}) { svc.runtimeUp(onEvent) }

    /** One-shot `logs.fetch` of the service's `log/` target as `service.list` names it (M6.4; the convention `log/service-<name>` when the row has none); `path` is the file the daemon wrote. */
    fun fetchLog(name: String): JsonObject =
        LogService.getInstance(project).fetchNow(FetchRequest(svc.model.services.firstOrNull { it.name == name }?.logTarget ?: logTarget(name)))

    /**
     * `service.reload`, rendered. Every `service.reload.step` and the end go to [out] as lines as they arrive; the outcome carries the same lines.
     * A typed failure (compile error, not running, ...) is an outcome, not an exception; only a lost daemon throws.
     */
    fun reload(name: String, budgetMs: Int? = null, out: (String) -> Unit = {}): ReloadOutcome {
        val lines = mutableListOf<String>()
        fun emit(l: String) { lines += l; out(l) }
        val a = args(name).apply { budgetMs?.let { addProperty("budget-ms", it) } }
        try {
            val call = svc.start("service.reload", a, true) { ev -> if (ev.name == "service.reload.step") emit(ReloadRender.step(ev)) }
            val t = call.await()
            if (t.ok) {
                val r = t.resultObject()
                ReloadRender.result(r).forEach(::emit)
                return ReloadOutcome(true, lines, null, r)
            }
            val f = ReloadRender.failure(t.error)
            f.lines.forEach(::emit)
            return ReloadOutcome(false, lines, f)
        } finally {
            svc.refreshNow()
        }
    }

    companion object {
        /** Two confirmations plus a margin. */
        private const val MAX_CONFIRMATIONS = 3

        fun getInstance(project: Project): ServiceOps = project.getService(ServiceOps::class.java)

        /** The environment target that stands for the service's container log (`sync-local-env!` writes it; a topology `external` may redirect it). */
        fun logTarget(name: String) = "log/service-$name"

        fun started(r: JsonObject, name: String): Started {
            fun s(k: String) = r.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.removePrefix(":")
            return Started(s("name") ?: name, s("status") ?: "?", s("container"), s("port"), s("jdwp"))
        }
    }
}

/** A [DaemonException] as one readable block (message + hint). */
fun DaemonException.readable() = "$type: $message" + (hint?.let { "\n  hint: $it" } ?: "")
