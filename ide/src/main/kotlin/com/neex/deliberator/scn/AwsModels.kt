package com.neex.deliberator.scn

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/** One node of an operation's request shape as `aws.describe` returns it. */
class AwsNode(
    val type: String,
    val required: Set<String>,
    val order: List<String>,
    val members: Map<String, AwsNode>,
    val member: AwsNode?,
    val value: AwsNode?,
    val enum: List<String>,
) {
    /** The node reached by [path]: a name steps into a structure member (or a map's value), [ELEMENT] into a list. */
    fun at(path: List<String>): AwsNode? {
        var n: AwsNode = this
        for (k in path) {
            n = if (k == ELEMENT) n.member ?: return null
            else if (n.type == "map") n.value ?: return null
            else n.members[k] ?: return null
        }
        return n
    }

    companion object {
        const val ELEMENT = "[]"

        fun parse(e: JsonElement?): AwsNode? {
            val o = e?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            fun strs(k: String) = o.get(k)?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString } ?: emptyList()
            val ms = LinkedHashMap<String, AwsNode>()
            o.get("members")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (k, v) -> parse(v)?.let { ms[k] = it } }
            val order = strs("order").ifEmpty { ms.keys.toList() }
            return AwsNode(
                o.get("type")?.asString ?: "unknown", strs("required").toSet(), order, ms,
                parse(o.get("member")), parse(o.get("value")), strs("enum"),
            )
        }
    }
}

/**
 * The request shapes of AWS operations, from the daemon's `aws.describe` (the IDE never reads an AWS model itself).
 * Fetched once per operation over the connection the editor already has, never starting a daemon. [input] answers from the
 * cache and fetches a miss on a pooled thread (the first completion in a body shows nothing, the next one everything): the
 * editor's threads never wait for the daemon. [fetch] is the blocking form for background callers (Fill Request Body).
 */
@Service(Service.Level.APP)
class AwsModels {
    private val cache = ConcurrentHashMap<String, java.util.Optional<AwsNode>>()
    private val fetching = ConcurrentHashMap.newKeySet<String>()

    /** The input shape of `service.Operation` ([verb]) from the cache; null when not fetched yet (a fetch is scheduled) or unknown. Never blocks. */
    fun input(project: Project, verb: String): AwsNode? {
        cache[verb]?.let { return it.orElse(null) }
        if (ScnRegistry.getInstance().knownClient() == null || !fetching.add(verb)) return null
        ApplicationManager.getApplication().executeOnPooledThread {
            try { if (!project.isDisposed) fetch(project, verb) } finally { fetching.remove(verb) }
        }
        return null
    }

    /** The input shape of [verb], fetched when not cached. Blocking: background threads only. */
    fun fetch(project: Project, verb: String): AwsNode? {
        val (service, op) = verb.split('.', limit = 2).takeIf { it.size == 2 } ?: return null
        cache[verb]?.let { return it.orElse(null) }
        val client = ScnRegistry.getInstance().knownClient() ?: return null
        val node = try {
            val r = ScnDaemon.call(client, project, "aws.describe", JsonObject().apply { addProperty("service", service); addProperty("op", op) })
            AwsNode.parse(r.takeIf { it.isJsonObject }?.asJsonObject?.get("input"))
        } catch (_: Exception) { return null }
        cache[verb] = java.util.Optional.ofNullable(node)
        return node
    }

    fun reset() = cache.clear()

    companion object {
        fun getInstance(): AwsModels = ApplicationManager.getApplication().getService(AwsModels::class.java)
    }
}
