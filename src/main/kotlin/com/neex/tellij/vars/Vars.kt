package com.neex.tellij.vars

import com.neex.tellij.spi.StepError
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * An immutable snapshot of the header's `@var` values plus the environment. Builtins that change
 * over time (`{{uuid}}`, `{{now}}`) are fresh on every direct use in a body, but a `@var` that used
 * them was evaluated once at snapshot time — that's what keeps `orderId` identical across blocks.
 */
class Vars(val values: Map<String, String>, private val env: Map<String, String> = emptyMap()) {

    fun substitute(text: String): String = PATTERN.replace(text) { m -> resolve(m.groupValues[1]) }

    /** Names of declared vars referenced in [text], in first-use order. */
    fun referenced(text: CharSequence): List<String> =
        PATTERN.findAll(text).map { it.groupValues[1] }.filter { it in values }.distinct().toList()

    fun referencedValues(text: CharSequence): Map<String, String> =
        referenced(text).associateWith { values.getValue(it) }

    fun resolve(name: String): String {
        values[name]?.let { return it }
        when (name) {
            "uuid" -> return UUID.randomUUID().toString().substring(0, 8)
            "uuid.full" -> return UUID.randomUUID().toString()
            "now" -> return LocalDateTime.now().format(ISO_LOCAL)
            "now.epoch" -> return Instant.now().toEpochMilli().toString()
        }
        if (name.startsWith("env.")) {
            val key = name.removePrefix("env.")
            return env[key] ?: System.getenv(key)
                ?: throw StepError("unknown environment variable {{$name}}", "put \"$key\" into tellij-env.json next to the file, or export it")
        }
        throw StepError("unknown variable {{$name}}", "declare it in the header: # @var $name = …")
    }

    companion object {
        val PATTERN = Regex("""\{\{\s*([A-Za-z_][\w.\-]*)\s*\}\}""")
        val ISO_LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
        fun timestamp(instant: Instant = Instant.now()): String =
            LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }
}
