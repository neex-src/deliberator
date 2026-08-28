package com.neex.tellij.vars

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

/**
 * `tellij-env.json` next to the workbench file — one flat object of string values, the same idea as
 * `http-client.env.json` minus the per-environment nesting (a later refinement). Missing file or
 * bad JSON → empty map; the process environment is the fallback anyway.
 */
object EnvFile {
    const val NAME = "tellij-env.json"

    fun load(dir: Path?): Map<String, String> {
        val file = dir?.resolve(NAME) ?: return emptyMap()
        if (!Files.isRegularFile(file)) return emptyMap()
        return try {
            val obj = JsonParser.parseString(Files.readString(file)).asJsonObject
            obj.entrySet().filter { it.value.isJsonPrimitive }.associate { it.key to it.value.asString }
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
