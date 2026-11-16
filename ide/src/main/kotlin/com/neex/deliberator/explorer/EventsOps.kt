package com.neex.deliberator.explorer

import com.google.gson.JsonObject
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.workspace.WorkspaceService

/** The daemon calls of the EventBridge view, one function each; all blocking, off the EDT. Reads only (`events.sample` writes a file under the workspace, `console.url` nothing). */
object EventsOps {
    private fun args(env: String) = JsonObject().apply { addProperty("env", env) }

    /** The last map from disk with its age; null when none was fetched yet (`events/no-cache`). */
    fun cachedMap(svc: WorkspaceService, env: String): EventsMap? = try {
        EventsMap.parse(env, svc.call("events.map", args(env).apply { addProperty("cached", true) }).resultObject())
    } catch (e: DaemonException) { if (e.type == "events/no-cache") null else throw e }

    /** `events.map`: reads EventBridge (read calls), writes the cache, answers it. */
    fun fetchMap(svc: WorkspaceService, env: String): EventsMap {
        svc.call("events.map", args(env))   // the answer is counts and paths; the map itself is read back from the cache the daemon just wrote
        return (cachedMap(svc, env) ?: throw DaemonException("events.map wrote no map", "events/no-cache")).copy(cached = false, age = null)
    }

    /** `events.who` for an event: who listens, from the cached map (fetched by the daemon when there is none). */
    fun who(svc: WorkspaceService, env: String, e: SentEvent): List<WhoMatch> {
        val a = args(env).apply {
            e.bus?.let { addProperty("bus", it) }; addProperty("detail-type", e.detailType ?: "scenario"); addProperty("source", e.source ?: "deliberator")
            e.detail?.takeIf { it.isNotBlank() }?.let { addProperty("detail", it) }
        }
        val r = svc.call("events.who", a).resultObject()
        return r.getAsJsonArray("matches")?.filter { it.isJsonObject }?.map { it.asJsonObject }.orEmpty().map { m ->
            WhoMatch(m.get("bus").asString, m.get("rule").asString, m.get("listening")?.asBoolean ?: false, m.get("pattern-line")?.asString.orEmpty(),
                m.getAsJsonArray("targets")?.filter { it.isJsonObject }?.map { it.asJsonObject }.orEmpty().map { t -> "${t.get("kind")?.asString} ${t.get("name")?.asString}" })
        }
    }

    /** `events.sample` of [rule]: the step text (`### eventbridge put <bus>` ...). */
    fun sample(svc: WorkspaceService, env: String, bus: String, rule: String): String {
        val r = svc.call("events.sample", args(env).apply { addProperty("rule", rule); addProperty("bus", bus) }).resultObject()
        return r.get("step")?.asString ?: throw DaemonException("events.sample returned no step", "events/no-step")
    }

    /** `console.url` with the sign-in asked for: the link goes straight to the browser from memory and is never kept; [plain] is the page without a session. */
    class ConsoleLink(val url: String, val signedIn: Boolean, val note: String?)

    fun consoleLink(svc: WorkspaceService, env: String, kind: String, name: String): ConsoleLink {
        val r = svc.call("console.url", args(env).apply { addProperty("kind", kind); addProperty("name", name); addProperty("reveal", true) }).resultObject()
        val signed = r.get("signin-url")?.takeIf { it.isJsonPrimitive }?.asString
        val note = r.get("signin-error")?.takeIf { it.isJsonPrimitive }?.asString ?: r.get("note")?.takeIf { it.isJsonPrimitive }?.asString
        return ConsoleLink(signed ?: r.get("url").asString, signed != null, note)
    }
}
