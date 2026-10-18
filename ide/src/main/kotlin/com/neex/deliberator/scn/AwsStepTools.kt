package com.neex.deliberator.scn

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/** Pure helpers behind the "skeleton" and "paste an aws CLI command" actions: nothing here knows the platform. */
object AwsStepTools {
    private val PRETTY = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** A value of the shape [n]: the zero of its type; a structure holds its required members only ([all] adds the rest, one level). */
    fun zero(n: AwsNode, all: Boolean = false, depth: Int = 0): JsonElement = when (n.type) {
        "structure" -> JsonObject().also { o ->
            n.order.filter { all && depth == 0 || it in n.required }.forEach { k -> n.members[k]?.let { o.add(k, zero(it, false, depth + 1)) } }
        }
        "list" -> JsonArray()
        "map" -> JsonObject()
        "boolean" -> JsonPrimitive(false)
        "integer", "long", "double", "float" -> JsonPrimitive(0)
        "blob", "timestamp" -> JsonPrimitive("")
        else -> JsonPrimitive(n.enum.firstOrNull() ?: "")
    }

    /** The body of a new step for [input]: its required members, pretty-printed on one level per line. */
    fun skeleton(input: AwsNode, all: Boolean = false): String = PRETTY.toJson(zero(input, all))

    /** `--queue-url` / `QueueUrl` / `queueUrl` all name the same member. */
    private fun norm(s: String) = s.replace("-", "").replace("_", "").lowercase()

    private val SERVICES = mapOf(
        "events" to "eventbridge", "stepfunctions" to "states", "cloudwatch" to "monitoring", "logs" to "logs",
        "secretsmanager" to "secretsmanager", "ssm" to "ssm", "s3api" to "s3", "sns" to "sns", "sqs" to "sqs",
    )

    /** What an `aws ...` command line became: the verb and the request, or why it did not. */
    class Imported(val verb: String?, val request: JsonObject?, val error: String? = null, val unknown: List<String> = emptyList()) {
        /** The step as `.scn` text. */
        fun text(): String? = if (verb == null || request == null) null else "### aws $verb\n" + (if (request.size() == 0) "" else PRETTY.toJson(request) + "\n")
    }

    /** Splits a command line the way a shell does for quotes (single, double, backslash), enough for pasted `aws` commands. */
    fun split(line: String): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder(); var q = 0.toChar(); var has = false; var i = 0
        val s = line.replace("\\\n", " ")
        while (i < s.length) {
            val c = s[i]
            when {
                q != 0.toChar() -> if (c == q) q = 0.toChar() else if (c == '\\' && q == '"' && i + 1 < s.length) { cur.append(s[++i]) } else cur.append(c)
                c == '\'' || c == '"' -> { q = c; has = true }
                c == '\\' && i + 1 < s.length -> { cur.append(s[++i]); has = true }
                c.isWhitespace() -> if (has || cur.isNotEmpty()) { out += cur.toString(); cur.clear(); has = false }
                else -> cur.append(c)
            }
            i++
        }
        if (has || cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /**
     * `aws sqs send-message --queue-url U --message-body B` -> `sqs.SendMessage` + `{"QueueUrl": U, "MessageBody": B}`. [verbs] are the
     * `service.Operation` words the daemon knows, [model] gives an operation's request shape (for member names and types).
     */
    fun fromCli(command: String, verbs: Set<String>, model: (String) -> AwsNode?): Imported {
        val t = split(command.trim()).dropWhile { it == "aws" }
        if (t.size < 2) return Imported(null, null, "expected: aws <service> <operation> [--flag value]...")
        val service = SERVICES[t[0]] ?: t[0]
        val opWord = norm(t[1])
        val verb = verbs.firstOrNull { it.substringBefore('.') == service && norm(it.substringAfter('.')) == opWord }
            ?: return Imported(null, null, "no operation $service.${t[1]} (the aws kind knows ${verbs.count { it.startsWith("$service.") }} operations of $service)")
        val input = model(verb)
        val req = JsonObject(); val unknown = ArrayList<String>()
        var i = 2
        while (i < t.size) {
            val f = t[i]
            if (!f.startsWith("--")) { i++; continue }
            val name = f.removePrefix("--")
            if (name in setOf("endpoint-url", "region", "profile", "output", "query", "no-cli-pager", "cli-input-json", "debug", "no-sign-request")) { i += if (name.startsWith("no-") || name == "debug") 1 else 2; continue }
            val member = input?.members?.keys?.firstOrNull { norm(it) == norm(name) }
            val next = t.getOrNull(i + 1)
            val bare = next == null || next.startsWith("--")
            val node = member?.let { input.members[it] }
            val key = member ?: name.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) }
            if (member == null) unknown += f
            req.add(key, if (bare) JsonPrimitive(true) else coerce(node, next!!))
            i += if (bare) 1 else 2
        }
        return Imported(verb, req, null, unknown)
    }

    private fun coerce(n: AwsNode?, v: String): JsonElement {
        val trimmed = v.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) runCatching { return JsonParser.parseString(trimmed) }
        return when (n?.type) {
            "integer", "long" -> v.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(v)
            "double", "float" -> v.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(v)
            "boolean" -> JsonPrimitive(v.equals("true", true))
            "list" -> JsonArray().also { a -> v.split(' ', ',').filter { it.isNotEmpty() }.forEach { a.add(it) } }
            else -> if (v == "null") JsonNull.INSTANCE else JsonPrimitive(v)
        }
    }
}
