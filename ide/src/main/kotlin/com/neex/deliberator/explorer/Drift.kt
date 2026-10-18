package com.neex.deliberator.explorer

import com.google.gson.JsonObject

/** What `terraform.drift` answered: declared resources missing from the environment and present ones nobody declared. */
class Drift(val env: String, val source: String, val declared: Int, val matching: Int, val missing: List<Item>, val undeclared: List<Item>) {
    class Item(val kind: String, val name: String, val address: String?)

    fun isUndeclared(kind: String, name: String) = undeclared.any { it.kind == kind && it.name == name }

    /** The text of the report, one line per finding: a file in the project like every other result. */
    fun text(): String = buildString {
        appendLine("# Terraform drift of $env against $source")
        appendLine("# declared $declared, present $matching, missing ${missing.size}, undeclared ${undeclared.size}")
        missing.forEach { appendLine("MISSING    ${it.kind}  ${it.name}" + (it.address?.let { a -> "   ($a)" } ?: "")) }
        undeclared.forEach { appendLine("UNDECLARED ${it.kind}  ${it.name}") }
        if (missing.isEmpty() && undeclared.isEmpty()) appendLine("no drift")
    }

    companion object {
        fun parse(r: JsonObject): Drift {
            fun items(k: String) = r.getAsJsonArray(k)?.mapNotNull { e ->
                val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                Item(o.get("kind").asString, o.get("name").asString, o.get("address")?.takeIf { it.isJsonPrimitive }?.asString)
            }.orEmpty()
            fun int(k: String) = r.get(k)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
            return Drift(r.get("env")?.asString ?: "", r.get("source")?.asString ?: "", int("declared"), int("matching"), items("missing"), items("undeclared"))
        }
    }
}
