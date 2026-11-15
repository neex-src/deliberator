package com.neex.deliberator.environments

import com.google.gson.JsonObject
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.workspace.WorkspaceService

/** The daemon calls of the deploy view, one function each; all blocking, off the EDT. Reads only (the daemon asks AWS with read calls). */
object DeployOps {
    private fun args(env: String, spec: String) = JsonObject().apply { addProperty("service", spec); addProperty("env", env) }

    /** What the Environments tab names a service for the daemon: `cluster/name`, or a function's name. */
    fun spec(r: DeployedRow): String = if (r.kind == "ecs" && r.cluster != null) "${r.cluster}/${r.name}" else r.name

    /** The answer of the last list, from disk with its age; null when nothing was listed yet (`deploy/no-cache`). */
    fun listCached(svc: WorkspaceService, env: String, spec: String): DeploymentList? = try {
        DeploymentList.parse(env, svc.call("deploy.list", args(env, spec).apply { addProperty("cached", true) }).resultObject())
    } catch (e: DaemonException) {
        if (e.type == "deploy/no-cache") null else throw e
    }

    /** `deploy.list`: asks AWS (read calls), writes the files, answers the rows. */
    fun list(svc: WorkspaceService, env: String, spec: String): DeploymentList =
        DeploymentList.parse(env, svc.call("deploy.list", args(env, spec)).resultObject())

    /** The deploy text of [deployment] (default the newest): its path. A finished deployment already shown comes from disk, else the daemon fetches it. */
    fun showPath(svc: WorkspaceService, env: String, spec: String, deployment: String?, finished: Boolean = false): String {
        val a = args(env, spec).apply { deployment?.let { addProperty("deployment", it) } }
        if (deployment != null && finished) {
            try { return svc.call("deploy.show", JsonObject().also { o -> a.entrySet().forEach { o.add(it.key, it.value) }; o.addProperty("cached", true) }).resultObject().get("path").asString }
            catch (e: DaemonException) { if (e.type != "deploy/no-cache") throw e }
        }
        return svc.call("deploy.show", a).resultObject().get("path").asString
    }

    /** `deploy.where`: the deployments of a commit and/or a CI run, from the caches (no AWS call). */
    fun where(svc: WorkspaceService, sha: String?, runId: String?): List<DeployHit> {
        val rows = LinkedHashMap<String, DeployHit>()
        listOfNotNull(sha?.takeIf { it.length >= 7 }?.let { "sha" to it }, runId?.takeIf { it.isNotBlank() }?.let { "run-id" to it }).forEach { (k, v) ->
            val r = svc.call("deploy.where", JsonObject().apply { addProperty(k, v) }).resultObject()
            r.getAsJsonArray("rows")?.forEach { if (it.isJsonObject) DeployHit.parse(it.asJsonObject).let { h -> rows.putIfAbsent("${h.env}/${h.spec}/${h.deployment ?: h.revision}", h) } }
        }
        return rows.values.toList()
    }
}
