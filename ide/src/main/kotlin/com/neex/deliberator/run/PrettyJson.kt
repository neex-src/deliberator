package com.neex.deliberator.run

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.neex.deliberator.scn.ResponsePaths

/** One line of a pretty-printed response: its text, the path of the value it belongs to and, for a scalar, what an `@assert` compares with. */
class PrettyLine(val text: String, val path: String, val scalar: String?)

/** A response document laid out one value per line, each line remembering its `$.path`. No PSI: the popup and the expanded inlay share it. */
object PrettyJson {
    fun render(root: JsonElement, limit: Int = 2000): List<PrettyLine> {
        val out = ArrayList<PrettyLine>()
        fun key(k: String) = "\"$k\": "
        fun walk(e: JsonElement, path: String, indent: Int, prefix: String, comma: String) {
            if (out.size >= limit) return
            val pad = "  ".repeat(indent)
            when (e) {
                is JsonObject -> if (e.size() == 0) out += PrettyLine("$pad$prefix{}$comma", path, null) else {
                    out += PrettyLine("$pad$prefix{", path, null)
                    val last = e.keySet().last()
                    e.entrySet().forEach { (k, v) -> walk(v, ResponsePaths.child(path, k), indent + 1, key(k), if (k == last) "" else ",") }
                    out += PrettyLine("$pad}$comma", path, null)
                }
                is JsonArray -> if (e.size() == 0) out += PrettyLine("$pad$prefix[]$comma", path, null) else {
                    out += PrettyLine("$pad$prefix[", path, null)
                    e.forEachIndexed { i, v -> walk(v, "$path[$i]", indent + 1, "", if (i == e.size() - 1) "" else ",") }
                    out += PrettyLine("$pad]$comma", path, null)
                }
                else -> out += PrettyLine("$pad$prefix$e$comma", path, if (e.isJsonNull) "null" else if (e.isJsonPrimitive && e.asJsonPrimitive.isString) e.asString else e.toString())
            }
        }
        walk(root, "$", 0, "", "")
        if (out.size >= limit) out += PrettyLine("… (cut at $limit lines; the whole response is in the step's response file)", "$", null)
        return out
    }

    fun of(text: String): List<PrettyLine>? = runCatching { render(JsonParser.parseString(text)) }.getOrNull()
}
