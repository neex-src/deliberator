package com.neex.deliberator.services

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.neex.deliberator.daemon.DaemonEvent
import com.neex.deliberator.run.ScnRunner
import com.neex.deliberator.workspace.WorkspaceService
import java.util.concurrent.CopyOnWriteArrayList

/** One declared HTTP fake (`fake.list`): [mode] is record, replay or stopped, [serving] what its container does now, [recordings] the ids `fake.replay from` takes (oldest first). */
data class FakeRow(
    val name: String, val mode: String, val serving: String?, val port: Int?, val url: String?, val containerUrl: String?, val upstream: String?,
    val recording: String?, val exchanges: Int?, val attached: List<String>, val recordings: List<String>,
)

/**
 * M6.6 in the Services tab: `fake.record`, `fake.replay`, `fake.stop` and `fake.list`, one blocking function each (call from a background task). The fakes are
 * the daemon's (a container of the runtime, recordings under the session); this only asks and shows. Every mutation refreshes the services (mode `fake`) and the fakes.
 */
@Service(Service.Level.PROJECT)
class FakeOps(private val project: Project) {
    private val svc get() = WorkspaceService.getInstance(project)
    private val listeners = CopyOnWriteArrayList<(List<FakeRow>) -> Unit>()
    @Volatile var fakes: List<FakeRow> = emptyList(); private set

    fun addListener(parent: Disposable, l: (List<FakeRow>) -> Unit) {
        listeners += l
        com.intellij.openapi.util.Disposer.register(parent) { listeners -= l }
    }

    private fun args(vararg kv: Pair<String, Any?>) = JsonObject().apply {
        kv.forEach { (k, v) -> when (v) { null -> {}; is Number -> addProperty(k, v); else -> addProperty(k, v.toString()) } }
    }

    /** `fake.list`, published to the listeners. */
    fun list(): List<FakeRow> {
        val rows = parse(svc.call("fake.list").result)
        fakes = rows
        listeners.forEach { it(rows) }
        return rows
    }

    private fun changed(): Unit { runCatching { svc.refreshNow() }; runCatching { list() } }

    /** `fake.record`: declares the fake and starts a new recording (needs a session); the fake forwards to [upstream] (`service/<name>` or a URL). */
    fun record(name: String, upstream: String, port: Int? = null, onEvent: (DaemonEvent) -> Unit = {}): FakeRow {
        ScnRunner.getInstance(project).ensureSession(svc)
        try {
            return parseOne(svc.call("fake.record", args("name" to name, "upstream" to upstream, "port" to port), onEvent).resultObject())
        } finally { changed() }
    }

    /** `fake.replay`: serve a recording ([from]: its id, default the newest). */
    fun replay(name: String, from: String? = null, onEvent: (DaemonEvent) -> Unit = {}): FakeRow {
        try {
            return parseOne(svc.call("fake.replay", args("name" to name, "from" to from), onEvent).resultObject())
        } finally { changed() }
    }

    /** `fake.stop`: the fake's container goes, the services attached to it are local again. */
    fun stop(name: String, onEvent: (DaemonEvent) -> Unit = {}): JsonObject {
        try {
            return svc.call("fake.stop", args("name" to name), onEvent).resultObject()
        } finally { changed() }
    }

    companion object {
        fun getInstance(project: Project): FakeOps = project.getService(FakeOps::class.java)

        private fun JsonObject.str(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString?.removePrefix(":")?.takeIf { it.isNotEmpty() }
        private fun JsonObject.strings(k: String) = (get(k)?.takeIf { it.isJsonArray } as? JsonArray)?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()

        fun parseOne(o: JsonObject) = FakeRow(
            o.str("name") ?: "?", o.str("mode") ?: "stopped", o.str("serving"), o.get("port")?.takeIf { it.isJsonPrimitive }?.asInt, o.str("url"), o.str("container-url"),
            o.str("upstream"), o.str("recording")?.substringAfterLast('/'), o.get("exchanges")?.takeIf { it.isJsonPrimitive }?.asInt,
            (o.get("attached")?.takeIf { it.isJsonArray } as? JsonArray)?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.str("service") }.orEmpty(),
            o.strings("recordings"))

        fun parse(e: com.google.gson.JsonElement?): List<FakeRow> =
            if (e != null && e.isJsonArray) e.asJsonArray.filter { it.isJsonObject }.map { parseOne(it.asJsonObject) } else emptyList()
    }
}
