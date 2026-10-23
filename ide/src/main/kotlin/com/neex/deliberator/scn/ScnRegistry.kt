package com.neex.deliberator.scn

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.neex.deliberator.daemon.DaemonClient
import com.neex.deliberator.daemon.DaemonConnection
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.daemon.DaemonService
import com.neex.deliberator.workspace.WorkspaceService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the editor knows about the daemon's registry and the environment, without ever blocking the EDT or
 * starting a daemon on its own account:
 *  - [kinds] answers at once: the copy fetched on the current daemon connection, else the last copy seen
 *    (kept in the IDE config, so the first completion after a restart works offline), else null.
 *  - [refresh] (blocking, background threads only) connects, and fetches `scenario.kinds` once per daemon
 *    connection (pid + endpoint + version); [warm] does that on a pooled thread.
 *  - [targets] answers from the cache of `env.describe` for the project's selected environment; [refreshTargets]
 *    fetches it over the connection already known (it never starts a daemon).
 */
@Service(Service.Level.APP)
@State(name = "DeliberatorKinds", storages = [Storage(value = "deliberator-kinds.xml", roamingType = RoamingType.DISABLED)])
class ScnRegistry : PersistentStateComponent<ScnRegistry.State> {
    class State {
        /** The last `scenario.kinds` result as JSON text. */
        var json: String = ""
        var fetchedAt: Long = 0
        var daemon: String = ""
    }

    private class Live(val key: String, val client: DaemonClient, val kinds: ScnKinds, val fetchedAt: Long)
    private class Targets(val names: List<String>, val fetchedAt: Long)

    private var state = State()
    @Volatile private var live: Live? = null
    @Volatile private var known: DaemonClient? = null
    @Volatile private var persisted: ScnKinds? = null
    private val targetCache = ConcurrentHashMap<String, Targets>()
    private val warming = AtomicBoolean(false)

    override fun getState(): State = state
    override fun loadState(s: State) {
        state = s
        persisted = parseOrNull(s.json)
    }

    /** The registry as last fetched on this connection, else the last copy seen, else null. Never blocks. */
    fun kinds(): ScnKinds? = live?.kinds ?: persisted

    /** The connection last used, null before any (never starts a daemon). */
    fun knownClient(): DaemonClient? = known

    /** True when [kinds] comes from the current daemon connection (not from the saved copy). */
    fun isLive(): Boolean = live != null

    /** Epoch millis of the copy [kinds] returns, 0 when none. */
    fun kindsAge(): Long = live?.fetchedAt ?: state.fetchedAt

    /** Connects (starting the daemon if needed) and loads the registry once per connection. Blocking: background threads only. */
    fun refresh(): ScnKinds? {
        val conn = DaemonService.getInstance().ensure()
        return load(conn)
    }

    /** The kinds on [conn]'s daemon: cached for the connection, else fetched. */
    fun load(conn: DaemonConnection): ScnKinds? {
        known = conn.client
        val key = "${conn.hello.pid}@${conn.hello.endpoint}#${conn.hello.version}"
        live?.let { if (it.key == key) return it.kinds }
        val t = conn.client.call("scenario.kinds")
        if (!t.ok) return kinds()
        val json = t.result ?: return kinds()
        val parsed = ScnKinds.parse(json)
        live = Live(key, conn.client, parsed, System.currentTimeMillis())
        persisted = parsed
        state = State().also { it.json = json.toString(); it.fetchedAt = System.currentTimeMillis(); it.daemon = conn.hello.version }
        return parsed
    }

    /** Fetches in the background (once at a time); failures are silent: the editor works without. */
    fun warm(project: Project? = null) {
        if (!warming.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                refresh()
                if (project != null) refreshTargets(project)
            } catch (e: Exception) {
                LOG.debug("scn registry warm-up: ${e.message}")
            } finally {
                warming.set(false)
            }
        }
    }

    // targets ----------------------------------------------------------------------------------------

    private fun targetKey(project: Project): String? {
        val svc = WorkspaceService.getInstance(project)
        val ws = svc.ws ?: return null
        return "$ws/${svc.selectedEnv}"
    }

    /** Target keys (`service/a`, `queue/inbox`) of the project's selected environment as last fetched, else empty. */
    fun targets(project: Project): List<String> = targetKey(project)?.let { targetCache[it]?.names } ?: emptyList()

    /**
     * Fetches `env.describe` of the project's selected environment over the connection already known (never
     * starts a daemon); empty when the project has no workspace or the daemon is not reachable. Blocking.
     */
    fun refreshTargets(project: Project): List<String> {
        val key = targetKey(project) ?: return emptyList()
        val client = known ?: return targets(project)
        val env = WorkspaceService.getInstance(project).selectedEnv
        return try {
            val r = ScnDaemon.call(client, project, "env.describe", JsonObject().apply { addProperty("name", env) })
            val names = targetNames(r)
            targetCache[key] = Targets(names, System.currentTimeMillis())
            names
        } catch (e: DaemonException) {
            targets(project)
        }
    }

    /** True when a connection is known and the project's target list is missing or older than [maxAgeMs]. */
    fun needsTargets(project: Project, maxAgeMs: Long = 30_000): Boolean =
        known != null && targetKey(project)?.let { k -> targetCache[k]?.let { System.currentTimeMillis() - it.fetchedAt > maxAgeMs } ?: true } == true

    /** Forgets everything fetched (the saved copy too). For tests and for "daemon replaced". */
    fun reset() {
        live = null; known = null; persisted = null; state = State(); targetCache.clear()
    }

    companion object {
        private val LOG = logger<ScnRegistry>()
        fun getInstance(): ScnRegistry = ApplicationManager.getApplication().getService(ScnRegistry::class.java)

        private fun parseOrNull(json: String): ScnKinds? =
            if (json.isBlank()) null else try { ScnKinds.parse(JsonParser.parseString(json)) } catch (_: Exception) { null }

        /** Keys of `{env {targets {"service/a" ".."}}}`; a leading `:` (a keyword printed as text) is dropped. */
        fun targetNames(describe: JsonElement): List<String> {
            val env = describe.takeIf { it.isJsonObject }?.asJsonObject?.get("env")?.takeIf { it.isJsonObject }?.asJsonObject
            val ts = env?.get("targets")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
            return ts.keySet().map { it.removePrefix(":") }.sorted()
        }
    }
}

/** Calls the editor makes: `ws` is added when the project is bound to a workspace. */
internal object ScnDaemon {
    fun call(client: DaemonClient, project: Project?, op: String, args: JsonObject = JsonObject()): JsonElement {
        val a = JsonObject()
        args.entrySet().forEach { a.add(it.key, it.value) }
        project?.let { WorkspaceService.getInstance(it).ws }?.let { a.addProperty("ws", it) }
        val t = client.call(op, a)
        if (!t.ok) throw DaemonException(t.errorMessage ?: "$op failed", t.errorType ?: "daemon/error")
        return t.result ?: JsonArray()
    }
}
