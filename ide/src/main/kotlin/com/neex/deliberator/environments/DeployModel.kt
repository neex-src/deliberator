package com.neex.deliberator.environments

import com.google.gson.JsonElement
import com.google.gson.JsonObject

private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString?.removePrefix(":")?.takeIf { it.isNotEmpty() }
private fun JsonElement?.objects(): List<JsonObject> = (this?.takeIf { it.isJsonArray }?.asJsonArray ?: emptyList<JsonElement>()).filter { it.isJsonObject }.map { it.asJsonObject }

/**
 * One deployment of `deploy.list`: an ECS service-deployment (or the service's own `deployments` where the call is not allowed), a CodeDeploy deployment, or a Lambda
 * version. The text of a row is the daemon's: nothing is derived here but the layout of the cells.
 */
data class DeploymentRow(
    val id: String,
    val kind: String,
    val source: String?,
    val status: String,
    val rollout: String?,
    val reason: String?,
    val started: String?,
    val finished: String?,
    val revision: String,
    val image: String,
    val commit: String?,
    val runId: String?,
) {
    fun cells(): Array<Any> = arrayOf(status, (rollout ?: "") + (reason?.takeIf { kind == "lambda" }?.let { ": $it" } ?: ""), time(started), revision, image, commit?.take(12) ?: "", runId ?: "", id)

    /** The row as one text line (tests, tooltips). */
    fun text(): String = cells().joinToString("  ")

    companion object {
        fun time(iso: String?) = iso?.replace('T', ' ')?.substringBefore('.')?.removeSuffix("Z")?.let { "$it Z" } ?: ""

        fun parse(o: JsonObject, kind: String): DeploymentRow {
            val lambda = kind == "lambda"
            val before = o.str("revision-before"); val after = o.str("revision-after")
            val ib = o.str("image-before"); val ia = o.str("image-after")
            return DeploymentRow(
                o.str("id") ?: "?", kind, o.str("source"), o.str("status") ?: "?", o.str("rollout-state"), o.str("rollout-reason"), o.str("started"), o.str("finished"),
                if (lambda) "v" + (o.str("version") ?: "?") else (before ?: "-") + " -> " + (after ?: "?"),
                if (lambda) (o.str("code-sha256")?.take(12) ?: "") else if (ib != null && ib != ia) "$ib -> $ia" else (ia ?: ""),
                o.str("git-sha"), o.str("run-id"),
            )
        }
    }
}

/** What `deploy.list` answered: the rows, where they came from and, for a cached answer, its age (principle 2: the age is always on the page). */
data class DeploymentList(
    val env: String, val service: String, val cluster: String?, val kind: String, val source: String?, val cached: Boolean, val age: String?, val fetchedAt: String?,
    val rows: List<DeploymentRow>, val notes: List<String>, val path: String?, val latest: String?,
) {
    fun status(): String = buildString {
        append("$service in $env: ${rows.size} deployment(s)")
        if (cached) append(", cached ${age ?: "?"} ago (Refresh Deployments asks AWS)") else append(", fetched now")
        source?.let { append(", from $it") }
        latest?.let { append("; latest: $it") }
        if (notes.isNotEmpty()) append("; ${notes.first().take(140)}")
    }

    companion object {
        fun parse(env: String, o: JsonObject): DeploymentList {
            val kind = o.str("kind") ?: "ecs"
            val latest = o.getAsJsonObject("latest")?.let { l ->
                listOfNotNull(l.str("state"), l.str("update-status")?.let { "update $it" }, l.str("reason"), l.str("code-sha256")?.let { "code ${it.take(12)}" }, l.str("modified")).joinToString(", ")
            }?.takeIf { it.isNotBlank() }
            return DeploymentList(
                env, o.str("service") ?: "?", o.str("cluster"), kind, o.str("source"), o.get("cached")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                o.str("age"), o.str("fetched-at"), o.get("rows").objects().map { DeploymentRow.parse(it, kind) },
                (o.get("notes")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString } ?: emptyList()), o.str("path"), latest,
            )
        }
    }
}

/** A hit of `deploy.where`: a deployment (or the current revision of a service) that carries a commit or was made by a CI run. */
data class DeployHit(val env: String, val cluster: String?, val service: String, val deployment: String?, val revision: String?, val status: String?, val runId: String?) {
    val spec: String get() = if (cluster != null) "$cluster/$service" else service
    fun label(): String = "$env: $service" + (revision?.let { " revision $it" } ?: "") + (deployment?.let { "  ($it)" } ?: "  (current)")

    companion object {
        fun parse(o: JsonObject) = DeployHit(o.str("env") ?: "?", o.str("cluster"), o.str("service") ?: "?", o.str("deployment"), o.str("revision"), o.str("status"), o.str("run-id"))
    }
}

/** What the deploy actions act on: the service row (its spec for the daemon) and, when one is chosen, its deployment. */
data class DeployTarget(val env: String, val spec: String, val kind: String, val row: DeploymentRow?)
