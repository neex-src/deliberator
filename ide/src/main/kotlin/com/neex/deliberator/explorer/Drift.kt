package com.neex.deliberator.explorer

import com.google.gson.JsonObject

/**
 * What `terraform.drift` answered: declared resources missing from the environment, present ones nobody declared, resources whose
 * values were changed by hand (`changed`, only when the answer comes from a refresh, B39) and kinds the profile could not list (`unknown`).
 */
class Drift(
    val env: String, val source: String, val declared: Int, val matching: Int,
    val missing: List<Item>, val undeclared: List<Item>,
    val changed: List<Changed> = emptyList(), val unknown: List<Item> = emptyList(), val refreshed: Boolean = false,
) {
    class Item(val kind: String, val name: String, val address: String?)

    /** A resource Terraform's own refresh found changed outside Terraform: the attribute NAMES that differ, never their values. */
    class Changed(val address: String, val kind: String?, val name: String?, val attributes: List<String>)

    fun isUndeclared(kind: String, name: String) = undeclared.any { it.kind == kind && it.name == name }

    /** The change of the resource [kind]/[name] (an explorer row), null when it was not changed or the answer has no refresh. */
    fun changeOf(kind: String, name: String) = changed.firstOrNull { it.kind == kind && it.name == name }

    /** The text of the report, one line per finding: a file in the project like every other result. */
    fun text(): String = buildString {
        appendLine("# Terraform drift of $env against $source" + if (refreshed) " (state refreshed from AWS first)" else "")
        appendLine("# declared $declared, present $matching, missing ${missing.size}, undeclared ${undeclared.size}" +
            (if (refreshed) ", changed ${changed.size}" else "") + (if (unknown.isNotEmpty()) ", unknown ${unknown.size}" else ""))
        missing.forEach { appendLine("MISSING    ${it.kind}  ${it.name}" + (it.address?.let { a -> "   ($a)" } ?: "")) }
        changed.forEach { appendLine("CHANGED    ${it.kind ?: "?"}  ${it.name ?: it.address}   (${it.address})   ${it.attributes.joinToString(", ")}") }
        unknown.forEach { appendLine("UNKNOWN    ${it.kind}  ${it.name}" + (it.address?.let { a -> "   ($a)" } ?: "") + "   (the profile cannot list ${it.kind})") }
        undeclared.forEach { appendLine("UNDECLARED ${it.kind}  ${it.name}") }
        if (missing.isEmpty() && undeclared.isEmpty() && changed.isEmpty() && unknown.isEmpty()) appendLine("no drift")
    }

    companion object {
        fun parse(r: JsonObject): Drift {
            fun items(k: String) = r.getAsJsonArray(k)?.mapNotNull { e ->
                val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                Item(o.get("kind").asString, o.get("name").asString, o.get("address")?.takeIf { it.isJsonPrimitive }?.asString)
            }.orEmpty()
            fun int(k: String) = r.get(k)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
            fun str(o: JsonObject, k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString
            val changed = r.getAsJsonArray("changed")?.mapNotNull { e ->
                val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                Changed(str(o, "address") ?: return@mapNotNull null, str(o, "kind"), str(o, "aws-name"),
                    o.getAsJsonArray("attributes")?.map { it.asString }.orEmpty())
            }.orEmpty()
            return Drift(r.get("env")?.asString ?: "", r.get("source")?.asString ?: "", int("declared"), int("matching"),
                items("missing"), items("undeclared"), changed, items("unknown"), r.has("refreshed"))
        }
    }
}
