package com.neex.tellij.java.controller

/**
 * A tolerant walk over (possibly unfinished) JSON that answers two questions without parsing:
 * where is the caret — under which keys/indices, and is it at a key position — and which keys
 * exist at which paths. Comments count as whitespace.
 */
object JsonKeyScanner {

    /** [path] like `["items", 0]`; [atKey] when a key may be typed here; [siblings] keys already at that level. */
    data class Position(val path: List<Any>, val atKey: Boolean, val partial: String, val siblings: List<String>)

    data class KeyAt(val path: List<Any>, val name: String, val start: Int, val end: Int)

    private class Frame(val obj: Boolean) {
        var expectingKey = true
        var currentKey: String? = null
        var index = 0
        val keys = ArrayList<String>()
    }

    fun pathAt(text: String, offset: Int): Position? {
        val stack = ArrayList<Frame>()
        var i = 0
        var partialStart = -1
        var lastKeyStart = -1
        val n = minOf(offset, text.length)
        while (i < n) {
            val c = text[i]
            when {
                c == '/' && i + 1 < n && text[i + 1] == '/' -> { while (i < n && text[i] != '\n') i++; continue }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> { val e = text.indexOf("*/", i + 2); i = if (e < 0 || e + 2 > n) n else e + 2; continue }
                c == '"' -> {
                    val start = i
                    var j = i + 1
                    while (j < n && text[j] != '"') { if (text[j] == '\\') j++; j++ }
                    if (j >= n) { partialStart = start; break } // unterminated: the caret is inside this string
                    val s = text.substring(i + 1, j)
                    val top = stack.lastOrNull()
                    if (top != null && top.obj && top.expectingKey) { lastKeyStart = start; top.currentKey = s }
                    i = j + 1
                    continue
                }
                c == '{' -> stack += Frame(true)
                c == '[' -> stack += Frame(false)
                c == '}' || c == ']' -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                c == ':' -> stack.lastOrNull()?.let { if (it.obj) { it.expectingKey = false; it.currentKey?.let { k -> it.keys += k } } }
                c == ',' -> stack.lastOrNull()?.let { if (it.obj) { it.expectingKey = true; it.currentKey = null } else it.index++ }
            }
            i++
        }
        if (stack.isEmpty()) return null
        val top = stack.last()
        val path = ArrayList<Any>()
        for ((k, f) in stack.withIndex()) {
            if (k == stack.lastIndex) break
            if (f.obj) f.currentKey?.let { path += it } else path += f.index
        }
        val atKey = top.obj && top.expectingKey
        val partial = if (partialStart >= 0) text.substring(partialStart + 1, n) else ""
        return Position(path, atKey, partial, top.keys.toList())
    }

    /** Every completed key (`"name":`) with its path and text range. */
    fun keys(text: String): List<KeyAt> {
        val out = ArrayList<KeyAt>()
        val stack = ArrayList<Frame>()
        var pending: Triple<String, Int, Int>? = null
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '/' && i + 1 < n && text[i + 1] == '/' -> { while (i < n && text[i] != '\n') i++; continue }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> { val e = text.indexOf("*/", i + 2); i = if (e < 0) n else e + 2; continue }
                c == '"' -> {
                    var j = i + 1
                    while (j < n && text[j] != '"') { if (text[j] == '\\') j++; j++ }
                    if (j >= n) break
                    val top = stack.lastOrNull()
                    if (top != null && top.obj && top.expectingKey) pending = Triple(text.substring(i + 1, j), i, j + 1)
                    i = j + 1
                    continue
                }
                c == '{' -> stack += Frame(true)
                c == '[' -> stack += Frame(false)
                c == '}' || c == ']' -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                c == ':' -> {
                    val top = stack.lastOrNull()
                    val p = pending
                    if (top != null && top.obj && p != null) {
                        val path = ArrayList<Any>()
                        for ((k, f) in stack.withIndex()) { if (k == stack.lastIndex) break; if (f.obj) f.currentKey?.let { path += it } else path += f.index }
                        out += KeyAt(path, p.first, p.second, p.third)
                        top.currentKey = p.first
                        top.expectingKey = false
                    }
                    pending = null
                }
                c == ',' -> stack.lastOrNull()?.let { if (it.obj) { it.expectingKey = true; it.currentKey = null } else it.index++ }
            }
            i++
        }
        return out
    }
}
