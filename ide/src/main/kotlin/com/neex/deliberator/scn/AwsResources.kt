package com.neex.deliberator.scn

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.neex.deliberator.workspace.WorkspaceService
import java.util.concurrent.ConcurrentHashMap

/** One thing that exists in the environment: its [name] and a [ref] (queue URL, ARN, parameter type) when there is one. */
data class LiveResource(val name: String, val ref: String?)

/**
 * What exists in the project's environment (`aws.discover`), cached per workspace and environment. [get] answers at once from the cache
 * and, when it is older than [ttlMs], fetches again over the connection the editor already has (never starts a daemon). Blocking
 * on the first fetch only: background threads (completion) only.
 */
@Service(Service.Level.APP)
class AwsResources {
    private class Entry(val at: Long, val byKind: Map<String, List<LiveResource>>)

    private val cache = ConcurrentHashMap<String, Entry>()

    fun get(project: Project, ttlMs: Long = 15_000): Map<String, List<LiveResource>> {
        val svc = WorkspaceService.getInstance(project)
        val ws = svc.ws ?: return emptyMap()
        val key = "$ws/${svc.selectedEnv}"
        val e = cache[key]
        if (e != null && System.currentTimeMillis() - e.at < ttlMs) return e.byKind
        val client = ScnRegistry.getInstance().knownClient() ?: return e?.byKind ?: emptyMap()
        return try {
            val r = ScnDaemon.call(client, project, "aws.discover", JsonObject().apply { addProperty("env", svc.selectedEnv) })
            val byKind = parse(r.takeIf { it.isJsonObject }?.asJsonObject)
            cache[key] = Entry(System.currentTimeMillis(), byKind)
            byKind
        } catch (_: Exception) { e?.byKind ?: emptyMap() }
    }

    /** Forget what was fetched (after a step that creates or deletes things). */
    fun invalidate() = cache.clear()

    companion object {
        fun getInstance(): AwsResources = ApplicationManager.getApplication().getService(AwsResources::class.java)

        fun parse(result: JsonObject?): Map<String, List<LiveResource>> {
            val res = result?.get("resources")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
            return res.entrySet().associate { (kind, arr) ->
                kind to (arr.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e ->
                    val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    LiveResource(o.get("name")?.asString ?: return@mapNotNull null, o.get("ref")?.takeIf { it.isJsonPrimitive }?.asString)
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
