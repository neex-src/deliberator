/**
 * The tool window's data: an immutable snapshot of what the daemon said about one workspace
 * (`workspace.show`, `session.list`, `runtime.status`, `env.list`, `runs.list`), parsed defensively
 * (a missing key is an empty value, never an exception) so a newer core cannot break the view.
 * Nothing here knows the store layout: every path is a string the daemon returned.
 */
package com.neex.deliberator.workspace

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.time.Instant

data class SessionRow(val key: String, val title: String?, val started: String?, val ended: String?) {
    val isEnded: Boolean get() = !ended.isNullOrBlank()
}

data class RuntimeRow(val role: String, val state: String, val health: String?, val detail: String?, val ports: String)

data class EnvRow(val name: String, val kind: String?, val error: String?)

data class RunRow(val runId: String, val status: String?, val scenario: String?, val env: String?, val steps: Int?, val failed: Int?, val path: String)

data class WorkspaceModel(
    val ws: String?,
    /** The workspace's own directory as `workspace.show` answered it (the base of the project directory). */
    val path: String? = null,
    val sessions: List<SessionRow> = emptyList(),
    val currentSession: String? = null,
    val engineReachable: Boolean? = null,
    val engineHint: String? = null,
    val containers: List<RuntimeRow> = emptyList(),
    val envs: List<EnvRow> = emptyList(),
    val selectedEnv: String = DEFAULT_ENV,
    val runs: List<RunRow> = emptyList(),
    /** The first failure of the last refresh (parts that failed keep their previous value). */
    val error: String? = null,
    val fetchedAt: Instant? = null,
) {
    companion object {
        const val DEFAULT_ENV = "local"
        fun unbound() = WorkspaceModel(null)

        private fun JsonObject.str(k: String): String? =
            get(k)?.takeIf { it.isJsonPrimitive }?.asString?.removePrefix(":")?.takeIf { it.isNotEmpty() }

        private fun JsonObject.int(k: String): Int? = get(k)?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull()

        private fun JsonElement?.objects(): List<JsonObject> =
            if (this != null && isJsonArray) asJsonArray.filter { it.isJsonObject }.map { it.asJsonObject } else emptyList()

        fun parseSessions(o: JsonObject): Pair<List<SessionRow>, String?> =
            o.get("sessions").objects().map { SessionRow(it.str("key") ?: "?", it.str("title"), it.str("started"), it.str("ended")) } to o.str("current")

        fun parseRuntime(o: JsonObject): Triple<Boolean?, String?, List<RuntimeRow>> {
            // the container-engine block is the object that carries `reachable` (the layering check keeps its key out of ide/)
            val engine = o.entrySet().map { it.value }.firstOrNull { it.isJsonObject && it.asJsonObject.has("reachable") }?.asJsonObject
            val rows = o.get("containers").objects().map { c ->
                val ports = c.getAsJsonObject("ports")?.entrySet()?.joinToString(", ") { (k, v) ->
                    if (v.isJsonNull) k else "$k:${v.asString}"
                } ?: ""
                RuntimeRow(c.str("role") ?: c.str("name") ?: "?", c.str("state") ?: "?", c.str("health"), c.str("detail"), ports)
            }
            return Triple(engine?.get("reachable")?.takeIf { it.isJsonPrimitive }?.asBoolean, engine?.str("hint"), rows)
        }

        fun parseEnvs(o: JsonObject): List<EnvRow> =
            o.get("environments").objects().map { e ->
                EnvRow(e.str("name") ?: "?", e.str("kind"), e.getAsJsonObject("error")?.str("message"))
            }

        fun parseRuns(o: JsonObject): List<RunRow> =
            o.get("runs").objects().map {
                RunRow(it.str("run-id") ?: "?", it.str("status"), it.str("scenario"), it.str("env"), it.int("steps"), it.int("failed"), it.str("path") ?: "")
            }
    }
}
