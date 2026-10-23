package com.neex.deliberator.scn

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.neex.deliberator.run.StepSection

/** A path into a step's response (`$.Messages[0].Body`) with a short rendering of the value found there. */
data class ResponsePath(val path: String, val preview: String)

/** The paths of the latest response of a step: what an `@assert` or `@capture` can name, offered from what really came back. */
object ResponsePaths {
    /** The response document of [section]: its first line that is JSON (the lines before the checks). */
    fun response(section: StepSection?): JsonElement? {
        for (l in section?.lines.orEmpty()) {
            if (l.startsWith("✓ ") || l.startsWith("✗ ")) break
            val t = l.trim()
            if (t.startsWith("{") || t.startsWith("[")) return runCatching { JsonParser.parseString(t) }.getOrNull()
        }
        return null
    }

    /** Every object member and array element down to [depth], breadth stays bounded by [limit]; an array shows its first 3 elements. */
    fun of(root: JsonElement?, limit: Int = 300, depth: Int = 8): List<ResponsePath> {
        val out = ArrayList<ResponsePath>()
        fun walk(e: JsonElement, path: String, d: Int) {
            if (out.size >= limit) return
            out += ResponsePath(path, preview(e))
            if (d >= depth) return
            when (e) {
                is JsonObject -> e.entrySet().forEach { (k, v) -> walk(v, path + member(k), d + 1) }
                is JsonArray -> e.take(3).forEachIndexed { i, v -> walk(v, "$path[$i]", d + 1) }
                else -> {}
            }
        }
        if (root != null) walk(root, "$", 0)
        return out.drop(1) // `$` itself is a root the kind already offers
    }

    /** [path] extended by member [k]. */
    fun child(path: String, k: String) = path + member(k)

    private fun member(k: String) = if (Regex("[A-Za-z_][A-Za-z0-9_]*").matches(k)) ".$k" else "['$k']"

    private fun preview(e: JsonElement): String = when {
        e.isJsonNull -> "null"
        e is JsonObject -> "{${e.size()}}"
        e is JsonArray -> "[${e.size()}]"
        else -> e.asString.replace('\n', ' ').let { if (it.length > 40) it.take(40) + "…" else it }
    }
}
