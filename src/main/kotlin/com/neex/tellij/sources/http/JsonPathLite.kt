package com.neex.tellij.sources.http

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.neex.tellij.spi.StepError

/**
 * `$.a.b[0].c` — dots and indexes, nothing more. Enough for "give me the id from the response";
 * anything cleverer is where a real JSON-path library plugs in.
 */
object JsonPathLite {
    fun get(json: String, path: String): String {
        val root = try { JsonParser.parseString(json) } catch (e: Exception) { throw StepError("capture: body is not JSON", e.message?.lineSequence()?.first()) }
        return render(select(root, path), path)
    }

    fun select(root: JsonElement, path: String): JsonElement {
        var cur = root
        val p = path.trim().removePrefix("$").removePrefix(".")
        if (p.isEmpty()) return cur
        for (seg in p.split('.')) {
            val m = Regex("""([^\[\]]*)((?:\[\d+])*)""").matchEntire(seg) ?: throw StepError("capture: bad path segment '$seg'")
            val key = m.groupValues[1]
            if (key.isNotEmpty()) {
                cur = (cur as? com.google.gson.JsonObject)?.get(key) ?: throw StepError("capture: no '$key' in $path", "body keys: ${(cur as? com.google.gson.JsonObject)?.keySet()?.joinToString() ?: "not an object"}")
            }
            for (idx in Regex("""\[(\d+)]""").findAll(m.groupValues[2])) {
                val i = idx.groupValues[1].toInt()
                cur = (cur as? com.google.gson.JsonArray)?.takeIf { i < it.size() }?.get(i) ?: throw StepError("capture: index [$i] out of range in $path")
            }
        }
        return cur
    }

    fun render(e: JsonElement, path: String): String = when {
        e.isJsonNull -> "null"
        e.isJsonPrimitive -> e.asString
        else -> e.toString()
    }
}
