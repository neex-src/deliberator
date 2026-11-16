package com.neex.deliberator.explorer

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString?.removePrefix(":")?.takeIf { it.isNotEmpty() }
private fun JsonElement?.objects(): List<JsonObject> = (this?.takeIf { it.isJsonArray }?.asJsonArray ?: emptyList<JsonElement>()).filter { it.isJsonObject }.map { it.asJsonObject }

/** A target of a rule as `events.map` answers it: what it is (the kind and name come from the ARN, the daemon's words). */
data class TargetRow(val id: String, val kind: String, val name: String, val arn: String, val input: String?, val dlq: String?, val role: String?) {
    fun text(): String = "$kind $name" + (input?.let { "  (input $it)" } ?: "") + (dlq?.let { "  dead-letter $it" } ?: "")
}

/** A rule: its state, the event pattern (as the JSON text AWS keeps) or schedule, and what it delivers to. */
data class RuleRow(val bus: String, val name: String, val state: String, val patternLine: String, val pattern: String?, val schedule: String?, val description: String?, val targets: List<TargetRow>) {
    val listening: Boolean get() = state == "ENABLED"
    val key: String get() = "$bus/$name"

    /** The rule as text for the editor (Enter): header, the pattern as JSON, the targets. */
    fun text(env: String, age: String?): String = buildString {
        append("rule        $bus/$name\n")
        append("environment $env" + (age?.let { "   (map fetched $it ago)" } ?: "") + "\n")
        append("state       $state" + (if (listening) "" else "   NOT LISTENING: the rule is disabled, nothing is delivered") + "\n")
        description?.let { append("description $it\n") }
        if (pattern != null) {
            append("\npattern     $patternLine\n")
            append(PRETTY.toJson(JsonParser.parseString(pattern)))
            append("\n")
        } else schedule?.let { append("\nschedule    $it\n") }
        append("\ntargets (${targets.size})\n")
        targets.forEach { t -> append("  ${t.id}  ${t.text()}\n    ${t.arn}\n" + (t.role?.let { "    role $it\n" } ?: "")) }
    }

    companion object { private val PRETTY = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create() }
}

data class BusRow(val name: String, val rules: List<RuleRow>)

/** The EventBridge map of one environment (`events.map`, from the cache or fetched): [cached] says which, [age] how old. */
data class EventsMap(val env: String, val fetchedAt: String?, val age: String?, val cached: Boolean, val buses: List<BusRow>, val errors: List<String>) {
    val rules: Int get() = buses.sumOf { it.rules.size }

    fun status(): String = "$env: ${buses.size} bus(es), $rules rule(s)" +
        (if (cached) ", map fetched ${age ?: "?"} ago (Refresh reads it again)" else ", fetched now") + (if (errors.isNotEmpty()) "; not readable: ${errors.first().take(100)}" else "")

    /** The buses with only the rules (and buses) that match [q] in the bus name, the rule name, the pattern text or a target; blank [q] is everything. */
    fun filtered(q: String): List<BusRow> {
        val s = q.trim().lowercase()
        if (s.isEmpty()) return buses
        return buses.mapNotNull { b ->
            val busHit = b.name.lowercase().contains(s)
            val rs = b.rules.filter { r -> busHit || r.name.lowercase().contains(s) || r.patternLine.lowercase().contains(s) || (r.pattern?.lowercase()?.contains(s) == true) ||
                r.targets.any { t -> t.name.lowercase().contains(s) || t.kind.lowercase().contains(s) || t.arn.lowercase().contains(s) } }
            if (rs.isEmpty() && !(busHit && b.rules.isEmpty())) null else BusRow(b.name, rs)
        }
    }

    companion object {
        fun parse(env: String, o: JsonObject): EventsMap {
            val buses = o.get("buses").objects().map { b ->
                val bn = b.str("name") ?: "?"
                BusRow(bn, b.get("rules").objects().map { r ->
                    RuleRow(bn, r.str("name") ?: "?", r.str("state") ?: "?", r.str("pattern-line") ?: "", r.str("pattern"), r.str("schedule"), r.str("description"),
                        r.get("targets").objects().map { t ->
                            TargetRow(t.str("id") ?: "?", t.str("kind") ?: "?", t.str("name") ?: "?", t.str("arn") ?: "", t.str("input"), t.str("dlq"), t.str("role"))
                        })
                })
            }
            val errors = o.getAsJsonObject("errors")?.entrySet()?.flatMap { (k, v) -> (if (v.isJsonArray) v.asJsonArray.map { it.asString } else emptyList()).map { "$k: $it" } } ?: emptyList()
            return EventsMap(env, o.str("fetched-at"), o.str("age"), o.get("cached")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false, buses, errors)
        }
    }
}

/** One match of `events.who`. */
data class WhoMatch(val bus: String, val rule: String, val listening: Boolean, val patternLine: String, val targets: List<String>) {
    fun text(): String = "$bus/$rule  " + (if (listening) "listening" else "NOT LISTENING (disabled)") + "  ->  " + targets.ifEmpty { listOf("no target") }.joinToString(", ")
}

/** What a PutEvents step sends, read from the editor: bus, source, detail-type and detail. */
data class SentEvent(val bus: String?, val source: String?, val detailType: String?, val detail: String?)
