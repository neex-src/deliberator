package com.neex.tellij.java.controller

/** Strips `// …` and `/* … */` from JSON so a field can be commented out; strings are honoured. */
object JsonComments {
    fun strip(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inString = false
        while (i < text.length) {
            val c = text[i]
            when {
                inString -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < text.length) { out.append(text[i + 1]); i++ }
                    else if (c == '"') inString = false
                }
                c == '"' -> { inString = true; out.append(c) }
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> { while (i < text.length && text[i] != '\n') i++; continue }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    val end = text.indexOf("*/", i + 2)
                    i = if (end < 0) text.length else end + 2
                    continue
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }
}
