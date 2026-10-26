package com.neex.deliberator.environments

import com.google.gson.JsonObject
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.workspace.WorkspaceService

/**
 * The daemon calls of the Environments tab, one function each; all blocking, off the EDT. The IDE writes nothing itself: a record is
 * written by `env.create`, removed by `env.remove`, the deployed view is derived by `deployed.refresh`, and the audit trail is named by
 * `audit.show`.
 */
object EnvironmentOps {
    fun list(svc: WorkspaceService): List<EnvInfo> = EnvInfo.parseList(svc.call("env.list").resultObject())

    /** `env.create` from the form; a refusal (`env/account-mismatch`, `check-failed`, a name that exists) is the [DaemonException] with its hint. */
    fun create(svc: WorkspaceService, f: EnvForm): EnvCreated = EnvCreated.parse(
        svc.call("env.create", JsonObject().apply {
            addProperty("name", f.name); addProperty("profile", f.profile); addProperty("account", f.account)
            addProperty("region", f.region); addProperty("read-only", f.readOnly)
        }).resultObject(),
    ).also { svc.refreshNow() }

    /** `env.remove`; what was fetched through the environment stays (the daemon says so). */
    fun remove(svc: WorkspaceService, name: String) {
        svc.call("env.remove", JsonObject().apply { addProperty("name", name) })
        if (svc.selectedEnv == name) svc.selectEnv("local")
        svc.refreshNow()
    }

    /** The cached view of [env] (never AWS); `nothing fetched yet` is an empty view, not an error. */
    fun deployed(svc: WorkspaceService, env: String): DeployedView = try {
        DeployedView.parse(env, svc.call("deployed.show", JsonObject().apply { addProperty("env", env) }).resultObject())
    } catch (e: DaemonException) {
        if (e.type == "deployed/no-cache") DeployedView.none(env) else throw e
    }

    /** `deployed.refresh` (read calls only), then the cached view it wrote. Returns the view. */
    fun refreshDeployed(svc: WorkspaceService, env: String): DeployedView {
        svc.call("deployed.refresh", JsonObject().apply { addProperty("env", env) })
        return deployed(svc, env)
    }

    /** `audit.show` of [session] (default: the current session, else the workspace's trail). */
    fun audit(svc: WorkspaceService, session: String? = null): AuditSummary =
        AuditSummary.parse(svc.call("audit.show", JsonObject().apply { if (session != null) addProperty("session", session) }).resultObject())

    /** What [env] knows about its CloudWatch log groups, read once (two cheap daemon calls); [LogGroupIndex.forService] then answers locally. */
    fun logGroupIndex(svc: WorkspaceService, env: String): LogGroupIndex {
        val targets = try {
            svc.call("env.describe", JsonObject().apply { addProperty("name", env) }).resultObject()
                .getAsJsonObject("env")?.getAsJsonObject("targets")
        } catch (_: DaemonException) { null }
        val groups = LinkedHashMap<String, String>()   // group -> target name
        targets?.entrySet()?.forEach { (k, v) ->
            val g = v.takeIf { it.isJsonObject }?.asJsonObject?.get("cloudwatch")?.takeIf { it.isJsonPrimitive }?.asString
            if (k.removePrefix(":").startsWith("log/") && g != null) groups[g] = k.removePrefix(":").removePrefix("log/")
        }
        val lambdas = try { deployed(svc, env).rows.filter { it.kind == "lambda" }.map { it.name } } catch (_: DaemonException) { emptyList() }
        return LogGroupIndex(groups, lambdas)
    }
}

/**
 * The log groups of one environment: its `log/` targets that name a CloudWatch group, and the Lambda functions of the cached deployed
 * view (`/aws/lambda/<fn>`). [forService] narrows to a service by name; with no service every known group is offered.
 */
class LogGroupIndex(private val targetGroups: Map<String, String>, private val lambdas: List<String>) {
    fun forService(service: String?): List<String> {
        if (service.isNullOrBlank()) return targetGroups.keys.toList()
        val named = targetGroups.filter { (g, t) -> t == service || t == "service-$service" || g.contains(service) }.keys
        return (named + lambdas.filter { it.contains(service) }.map { "/aws/lambda/$it" }).distinct()
    }
}
