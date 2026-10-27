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

data class RuntimeRow(val role: String, val state: String, val health: String?, val detail: String?, val ports: String,
                      /** container port -> host port, as the daemon mapped them (empty when the container has none). */
                      val portMap: Map<String, String> = emptyMap())

/**
 * One service of `service.list` joined with its `service.status` row and the host ports of its container.
 * [port] and [jdwp] are the description's container ports, [hostPort] and [hostJdwp] where the daemon published them (null while no container exists).
 */
data class ServiceRow(
    val name: String,
    val kind: String?,
    val mode: String,
    val url: String?,
    val status: String,
    val port: Int?,
    val jdwp: Int?,
    val hostPort: String?,
    val hostJdwp: String?,
    val reloadCount: Int?,
    val drift: Boolean,
    val detail: String?,
    /** The service's runtime container name (`service.list` `container`, M6.4). */
    val container: String? = null,
    /** The `log/` target of the environment for this service (`log/service-a`) and what it stands for: a container or a CloudWatch group. */
    val logTarget: String? = null,
    val logContainer: String? = null,
    val logGroup: String? = null,
    /** In mode `fake`: the HTTP fake that serves it. */
    val fake: String? = null,
)

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
    /** The workspace's services in [selectedEnv] (`service.list` + `service.status` + the container ports of `runtime.status`). */
    val services: List<ServiceRow> = emptyList(),
    /** The first failure of the last refresh (parts that failed keep their previous value). */
    val error: String? = null,
    val fetchedAt: Instant? = null,
    /** The daemon does not know [ws] (`workspace/not-found`): nothing else was asked; Open Workspace creates it. */
    val missing: Boolean = false,
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
                val map = c.getAsJsonObject("ports")?.entrySet()?.filter { !it.value.isJsonNull }?.associate { (k, v) -> k to v.asString } ?: emptyMap()
                RuntimeRow(c.str("role") ?: c.str("name") ?: "?", c.str("state") ?: "?", c.str("health"), c.str("detail"), ports, map)
            }
            return Triple(engine?.get("reachable")?.takeIf { it.isJsonPrimitive }?.asBoolean, engine?.str("hint"), rows)
        }

        /** `service.list` rows joined by name with `service.status` rows; host ports from the runtime row `service-<name>`. */
        fun parseServices(list: JsonElement?, status: JsonElement?, containers: List<RuntimeRow>): List<ServiceRow> {
            val st = status.objects().associateBy { it.str("name") }
            return list.objects().map { d ->
                val name = d.str("name") ?: "?"
                val s = st[name]
                val rt = containers.firstOrNull { it.role == "service-$name" || it.role == "service/$name" }
                // service.list's port/jdwp are host ports since B5; the container ports key the runtime row's port map
                val port = d.int("container-port") ?: d.int("port")
                val jdwp = d.int("container-jdwp") ?: d.int("jdwp")
                ServiceRow(name, d.str("kind"), d.str("mode") ?: "local", d.str("url"), s?.str("status") ?: d.str("status") ?: "?", port, jdwp,
                    port?.let { rt?.portMap?.get(it.toString()) }, jdwp?.let { rt?.portMap?.get(it.toString()) },
                    s?.int("reload-count"), s?.get("drift")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false, s?.str("detail"),
                    d.str("container"), d.str("log-target"), d.getAsJsonObject("log")?.str("container"), d.getAsJsonObject("log")?.str("cloudwatch"), d.str("fake"))
            }
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
