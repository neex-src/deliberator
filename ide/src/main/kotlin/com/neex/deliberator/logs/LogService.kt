package com.neex.deliberator.logs

import com.google.gson.JsonObject
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.neex.deliberator.run.ScnRunner
import com.neex.deliberator.workspace.WorkspaceService

/** What to fetch: [target] as the environment names it (`log/service-a`) or an absolute file path. */
data class FetchRequest(val target: String, val since: String? = null, val `for`: String? = null, val filter: String? = null)

/** `logs.fetch` for the selected environment; the answer's `path` is the file the daemon wrote. */
@Service(Service.Level.PROJECT)
class LogService(private val project: Project) {
    /** Log targets of the selected environment (`env.describe` keys whose value names a container, file or CloudWatch group). Blocking. */
    fun targets(): List<String> {
        val svc = WorkspaceService.getInstance(project)
        val r = svc.call("env.describe", JsonObject().apply { addProperty("name", svc.selectedEnv) }).resultObject()
        val ts = r.getAsJsonObject("env")?.getAsJsonObject("targets") ?: return emptyList()
        return ts.entrySet().filter { (k, v) -> k.removePrefix(":").startsWith("log/") || (v.isJsonObject && listOf("container", "file", "cloudwatch").any { v.asJsonObject.has(it) }) }
            .map { it.key.removePrefix(":") }.sorted()
    }

    /** Runs `logs.fetch` (needs a session; offers to start one) and remembers the file as a log. Blocking, off the EDT. */
    fun fetchNow(req: FetchRequest): JsonObject {
        val svc = WorkspaceService.getInstance(project)
        ScnRunner.getInstance(project).ensureSession(svc)
        val a = JsonObject().apply {
            addProperty("target", req.target)
            addProperty("env", svc.selectedEnv)
            req.since?.takeIf { it.isNotBlank() && !FetchForm.isFileTarget(req.target) }?.let { addProperty("since", it.trim()) }
            req.`for`?.takeIf { it.isNotBlank() }?.let { addProperty("for", it.trim()) }
            req.filter?.takeIf { it.isNotBlank() }?.let { addProperty("filter", it) }
        }
        val r = svc.call("logs.fetch", a).resultObject()
        r.get("path")?.asString?.let { LogFiles.register(it) }
        return r
    }

    companion object {
        fun getInstance(project: Project): LogService = project.getService(LogService::class.java)
    }
}
