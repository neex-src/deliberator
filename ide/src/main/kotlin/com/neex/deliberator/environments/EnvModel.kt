/**
 * The data of the Environments tab, parsed from the daemon's answers (`env.list`, `deployed.show`, `audit.show`, `env.create`) defensively:
 * a missing key is an empty value, never an exception, so a newer core cannot break the view. Nothing here knows where the daemon keeps
 * anything: every path is a string it returned.
 */
package com.neex.deliberator.environments

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.time.Duration
import java.time.Instant

private fun JsonObject.str(k: String): String? =
    get(k)?.takeIf { it.isJsonPrimitive }?.asString?.removePrefix(":")?.takeIf { it.isNotEmpty() }

private fun JsonObject.int(k: String): Int? = get(k)?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull()

private fun JsonElement?.objects(): List<JsonObject> =
    if (this != null && isJsonArray) asJsonArray.filter { it.isJsonObject }.map { it.asJsonObject } else emptyList()

/** One environment of `env.list`. [readOnly] is only meaningful for a remote one. */
data class EnvInfo(
    val name: String,
    val kind: String,
    val account: String?,
    val region: String?,
    val profile: String?,
    val readOnly: Boolean,
    val endpoint: String?,
    val description: String?,
    val targets: Int,
    val error: String?,
) {
    val remote: Boolean get() = kind == "remote"

    /** What a mutation does here: the sentence the table's Mode column shows. */
    val mode: String get() = when {
        error != null -> "broken"
        !remote -> "local (emulator)"
        readOnly -> "read-only: every mutation refused"
        else -> "writable: each mutation asks for a confirmation"
    }

    companion object {
        fun parseList(o: JsonObject): List<EnvInfo> = o.get("environments").objects().map { e ->
            EnvInfo(
                e.str("name") ?: "?", e.str("kind") ?: "?", e.str("account"), e.str("region"), e.str("profile"),
                e.get("read-only")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false, e.str("endpoint"), e.str("description"),
                e.int("targets") ?: 0, e.getAsJsonObject("error")?.str("message"),
            )
        }
    }
}

/** What `env.create` answered: the record written, and whether the profile was asked who it is. */
data class EnvCreated(val name: String, val kind: String?, val account: String?, val checked: Boolean, val readOnly: Boolean, val path: String?) {
    /** The line the form shows after a successful create (the account-check result). */
    fun text(profile: String, region: String): String =
        "Created $name: " + (if (checked) "profile $profile is in account ${account ?: "?"} (checked with sts:GetCallerIdentity)" else "account ${account ?: "?"} NOT checked (offline authoring)") +
            ", region $region, " + (if (readOnly) "read-only: every mutation is refused" else "writable: each mutation asks for a confirmation")

    companion object {
        fun parse(o: JsonObject) = EnvCreated(
            o.str("env") ?: "?", o.str("kind"), o.str("account"),
            o.get("checked")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            o.get("read-only")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false, o.str("path"),
        )
    }
}

/** The form's fields, trimmed; [problem] is what is missing or malformed before the daemon is asked at all. */
data class EnvForm(val name: String, val profile: String, val account: String, val region: String, val readOnly: Boolean) {
    val problem: String? get() = when {
        name.isBlank() -> "Give the environment a name."
        !name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) -> "The name is letters, digits, dot, dash and underscore."
        name == "local" -> "`local` is the emulator's environment and follows the runtime; pick another name."
        profile.isBlank() -> "Give the AWS profile of the shared config and credentials files."
        !account.matches(Regex("\\d{12}")) -> "The account is the 12-digit account id the profile must be in."
        region.isBlank() -> "Give the AWS region."
        else -> null
    }
}

/** One row of `deployed.show`: an ECS service or a Lambda function. */
data class DeployedRow(
    val kind: String,
    val cluster: String?,
    val name: String,
    /** `rev 12` for an ECS service, `$LATEST`/`7` for a function. */
    val revision: String,
    /** Image tags per container (`app:1.4.2`), or the function's image or code sha. */
    val image: String,
    val running: String,
    val updated: String?,
) {
    companion object {
        fun parse(s: JsonObject): DeployedRow {
            val kind = s.str("kind") ?: "?"
            val containers = (s.get("containers").objects()).joinToString(", ") { c ->
                (c.str("name") ?: "?") + ":" + (c.str("tag") ?: c.str("digest") ?: c.str("image") ?: "?")
            }
            return DeployedRow(
                kind, s.str("cluster"), s.str("name") ?: "?",
                if (kind == "ecs") "rev " + (s.str("revision") ?: "?") else (s.str("version") ?: "?"),
                if (kind == "ecs") containers else (s.str("image") ?: s.str("code-sha256") ?: ""),
                if (kind == "ecs") "${s.int("running") ?: "?"}/${s.int("desired") ?: "?"}" else "",
                if (kind == "ecs") s.get("deployments").objects().mapNotNull { it.str("updated-at") }.maxOrNull() else s.str("last-modified"),
            )
        }
    }
}

/** `deployed.show` as the tab keeps it. [fetchedAt] null = nothing was fetched yet (the daemon's `deployed/no-cache`). */
data class DeployedView(
    val env: String,
    val fetchedAt: String?,
    val age: String?,
    val rows: List<DeployedRow>,
    val text: String?,
    val errors: List<String>,
    val absent: List<String>,
) {
    /** The status line above the rows (principle 2: the age is always on the page). */
    fun status(): String = when {
        fetchedAt == null -> "$env: nothing fetched yet. Refresh fetches it (read calls only)."
        else -> "$env: fetched $fetchedAt (${age ?: "age unknown"} ago), ${rows.size} service(s)/function(s)" +
            (if (errors.isNotEmpty()) "; some sections failed: ${errors.joinToString("; ")}" else "") +
            (if (absent.isNotEmpty()) "; not available here: ${absent.joinToString(", ")}" else "")
    }

    companion object {
        fun none(env: String) = DeployedView(env, null, null, emptyList(), null, emptyList(), emptyList())

        fun parse(env: String, o: JsonObject): DeployedView {
            val errors = o.getAsJsonObject("errors")?.entrySet()?.flatMap { (section, v) ->
                val es = if (v.isJsonArray) v.asJsonArray.filter { it.isJsonObject }.map { it.asJsonObject } else emptyList()
                es.map { "$section ${it.str("op") ?: ""} ${it.str("code") ?: it.str("message") ?: ""}".trim() }
            } ?: emptyList()
            val absent = o.getAsJsonObject("absent")?.entrySet()?.map { (k, v) -> "$k (${if (v.isJsonPrimitive) v.asString else "absent"})" } ?: emptyList()
            return DeployedView(env, o.str("fetched-at"), o.str("age"), o.get("services").objects().map(DeployedRow::parse), o.str("text"), errors, absent)
        }

        /** The age in words for a refresh result (`fetched-at` only). */
        fun ageOf(fetchedAt: String?, now: Instant = Instant.now()): String? {
            val t = fetchedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
            val d = Duration.between(t, now).coerceAtLeast(Duration.ZERO)
            return when {
                d.toMinutes() < 1 -> "${d.seconds} s"
                d.toHours() < 1 -> "${d.toMinutes()} min"
                d.toDays() < 1 -> "${d.toHours()} h ${d.toMinutes() % 60} min"
                else -> "${d.toDays()} days"
            }
        }
    }
}

/** One line of `audit.show`. */
data class AuditLine(val n: Int, val at: String?, val env: String?, val service: String?, val op: String?, val outcome: String?, val confirmation: String? = null, val reason: String? = null) {
    /** A call that went through with a confirmation (the line says `used` or `pass`; its outcome is the call's own: ok or error). */
    val given: Boolean get() = confirmation != null

    /** The attempts a person must notice: a confirmation asked, given, or a mutation refused. */
    val notable: Boolean get() = given || outcome in NOTABLE

    companion object {
        val NOTABLE = setOf("confirm-required", "confirmed", "refused")
    }
}

/** `audit.show`: the file to open and its lines. */
data class AuditSummary(val path: String, val exists: Boolean, val scope: String, val session: String?, val count: Int, val outcomes: Map<String, Int>, val lines: List<AuditLine>) {
    /** The banner above the opened file. */
    fun banner(): String {
        val conf = lines.count { it.given } + (outcomes["confirmed"] ?: 0)
        val asked = (outcomes["confirm-required"] ?: 0)
        val refused = (outcomes["refused"] ?: 0)
        return (if (scope == "session") "Audit trail of session $session" else "Audit trail of the workspace (no session open)") +
            ": $count attempt(s), $asked confirmation(s) asked, $conf given, $refused refused. Highlighted lines are the confirmations and refusals."
    }

    companion object {
        fun parse(o: JsonObject) = AuditSummary(
            o.str("path") ?: "", o.get("exists")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false, o.str("scope") ?: "workspace", o.str("session"),
            o.int("count") ?: 0,
            o.getAsJsonObject("outcomes")?.entrySet()?.associate { (k, v) -> k to (v.takeIf { it.isJsonPrimitive }?.asInt ?: 0) } ?: emptyMap(),
            o.get("lines").objects().map { AuditLine(it.int("n") ?: 0, it.str("at"), it.str("env"), it.str("service"), it.str("op"), it.str("outcome"), it.str("confirmation"), it.str("reason")) },
        )
    }
}

/** Human size of a byte count: `1.5 KB (1536 bytes)`. */
fun bytesText(bytes: Long?): String = when {
    bytes == null -> "unknown"
    bytes < 1024 -> "$bytes bytes"
    bytes < 1024 * 1024 -> String.format("%.1f KB (%d bytes)", bytes / 1024.0, bytes)
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB (%d bytes)", bytes / 1024.0 / 1024.0, bytes)
    else -> String.format("%.2f GB (%d bytes)", bytes / 1024.0 / 1024.0 / 1024.0, bytes)
}
