package com.neex.deliberator.scn

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.neex.deliberator.workspace.WorkspaceService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One thing that exists in the environment: its [name] and a [ref] (queue URL, ARN, parameter type) when there is one. An ECS service also carries the
 * [containers] of its task definition (B31): what ties it to the workspace service whose container it runs.
 */
data class LiveResource(val name: String, val ref: String?, val containers: List<String> = emptyList())

/**
 * What exists in the project's environment (`aws.discover`), cached per workspace and environment. [get] answers at once from the
 * cache (empty before the first fetch) and, when the copy is older than [ttlMs], refreshes it on a pooled thread over the connection
 * the editor already has (never starts a daemon, never blocks the caller): the editor's threads read caches only. [fetchNow] is the
 * blocking fetch for background callers that want the current answer (the AWS tab's Refresh).
 */
@Service(Service.Level.APP)
class AwsResources {
    private class Entry(val at: Long, val byKind: Map<String, List<LiveResource>>, val errors: Map<String, String> = emptyMap())

    private val cache = ConcurrentHashMap<String, Entry>()
    private val refreshing = AtomicBoolean(false)

    private fun key(project: Project): String? {
        val svc = WorkspaceService.getInstance(project)
        val ws = svc.ws ?: return null
        return "$ws/${svc.selectedEnv}"
    }

    /** The cached resources, refreshed in the background when older than [ttlMs]. Never blocks. */
    fun get(project: Project, ttlMs: Long = 15_000): Map<String, List<LiveResource>> {
        val key = key(project) ?: return emptyMap()
        val e = cache[key]
        if (e == null || System.currentTimeMillis() - e.at >= ttlMs) refreshAsync(project)
        return e?.byKind ?: emptyMap()
    }

    /** `aws.discover` now, over the known connection; the cache follows. Blocking: background threads only. Empty when no daemon is known. */
    fun fetchNow(project: Project): Map<String, List<LiveResource>> {
        val svc = WorkspaceService.getInstance(project)
        val key = key(project) ?: return emptyMap()
        val client = ScnRegistry.getInstance().knownClient() ?: return cache[key]?.byKind ?: emptyMap()
        return try {
            val r = ScnDaemon.call(client, project, "aws.discover", JsonObject().apply { addProperty("env", svc.selectedEnv) }, timeoutMs = 60_000)
            val byKind = parse(r.takeIf { it.isJsonObject }?.asJsonObject)
            cache[key] = Entry(System.currentTimeMillis(), byKind, parseErrors(r.takeIf { it.isJsonObject }?.asJsonObject))
            byKind
        } catch (e: Exception) {
            // the daemon refused the whole listing (a profile that cannot log in, an account that differs): say so, do not look like an empty account
            val prior = cache[key]
            cache[key] = Entry(System.currentTimeMillis(), prior?.byKind ?: emptyMap(), mapOf("aws.discover" to (e.message ?: e.toString())))
            prior?.byKind ?: emptyMap()
        }
    }

    private fun refreshAsync(project: Project) {
        if (ScnRegistry.getInstance().knownClient() == null || !refreshing.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try { if (!project.isDisposed) fetchNow(project) } finally { refreshing.set(false) }
        }
    }

    /** The sections of the last fetch that failed (kind -> the daemon's words: a permission, a subscription), empty before a fetch. */
    fun errors(project: Project): Map<String, String> = key(project)?.let { cache[it]?.errors } ?: emptyMap()

    /** Forget what was fetched (after a step that creates or deletes things). */
    fun invalidate() = cache.clear()

    companion object {
        fun getInstance(): AwsResources = ApplicationManager.getApplication().getService(AwsResources::class.java)

        /** `aws.discover`'s `errors`: a section that could not be listed is an error, never an empty list. */
        fun parseErrors(result: JsonObject?): Map<String, String> =
            result?.get("errors")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.associate { (k, v) -> k to (v.takeIf { it.isJsonPrimitive }?.asString ?: v.toString()) } ?: emptyMap()

        fun parse(result: JsonObject?): Map<String, List<LiveResource>> {
            val res = result?.get("resources")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
            return res.entrySet().associate { (kind, arr) ->
                kind to (arr.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e ->
                    val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    LiveResource(
                        o.get("name")?.asString ?: return@mapNotNull null, o.get("ref")?.takeIf { it.isJsonPrimitive }?.asString,
                        o.get("containers")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { c -> c.takeIf { it.isJsonPrimitive }?.asString } ?: emptyList())
                } ?: emptyList())
            }
        }

        /** The kind of live resource a step kind's target names (`### sqs send <queue>`). */
        val TARGET_KIND = mapOf("sqs" to "sqs", "dynamodb" to "dynamodb", "eventbridge" to "eventbridge", "lambda" to "lambda")

        /** (kind of resource, use the ref instead of the name) for a request member of `service`, null when the member names no resource. */
        fun forMember(service: String, member: String): Pair<String, Boolean>? = when (member) {
            "QueueUrl" -> "sqs" to true
            "QueueName" -> "sqs" to false
            "TableName" -> "dynamodb" to false
            "TopicArn", "TargetArn" -> "sns" to true
            "Bucket" -> "s3" to false
            "EventBusName" -> "eventbridge" to false
            "FunctionName" -> "lambda" to false
            "SecretId" -> "secretsmanager" to false
            "StreamName" -> "kinesis" to false
            "stateMachineArn", "StateMachineArn" -> "states" to true
            "Name", "ParameterName", "Names" -> when (service) { "ssm" -> "ssm" to false; "secretsmanager" -> "secretsmanager" to false; else -> null }
            else -> null
        }
    }
}
