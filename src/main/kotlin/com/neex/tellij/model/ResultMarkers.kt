package com.neex.tellij.model

/**
 * The plugin-owned result section syntax lives here and nowhere else:
 *
 *     #> result kafka orders · 2026-08-28 16:40:01 · 23 ms · ERROR
 *     # vars: orderId=ord-3f2a
 *     …
 *     #< end
 */
object ResultMarkers {
    const val START = "#> result"
    const val END = "#< end"
    const val SEP = " · "
    const val VARS_PREFIX = "# vars:"

    fun isStart(line: CharSequence): Boolean = line.startsWith(START)
    fun isEnd(line: CharSequence): Boolean = line.trimEnd().contentEquals(END)

    fun header(kind: String, name: String?, at: String, elapsed: String? = null, status: String? = null): String =
        buildString {
            append(START).append(' ').append(kind)
            if (name != null) append(' ').append(name)
            append(SEP).append(at)
            if (elapsed != null) append(SEP).append(elapsed)
            if (status != null) append(SEP).append(status)
        }

    fun varsLine(values: Map<String, String>): String =
        if (values.isEmpty()) "$VARS_PREFIX (none)"
        else values.entries.joinToString(", ", prefix = "$VARS_PREFIX ") { "${it.key}=${it.value}" }

    fun elapsed(ms: Long): String = when {
        ms < 1_000 -> "$ms ms"
        ms < 60_000 -> String.format(java.util.Locale.ROOT, "%.1f s", ms / 1000.0)
        else -> String.format(java.util.Locale.ROOT, "%.1f min", ms / 60_000.0)
    }
}
