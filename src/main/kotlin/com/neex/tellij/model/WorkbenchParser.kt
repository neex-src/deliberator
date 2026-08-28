package com.neex.tellij.model

/**
 * Line-based parser for `.tellij` text. One pass, no PSI: every construct is decided by a line's
 * prefix, so the grammar stays trivially hand-editable and result sections can be skipped over
 * wholesale. Lenient on purpose — a half-deleted result section or a stray `#< end` never
 * prevents the rest of the file from parsing.
 */
object WorkbenchParser {

    private val VAR_LINE = Regex("""^#\s*@var\s+([A-Za-z_][\w.\-]*)\s*=\s*(.*)$""")
    private val CONNECT_LINE = Regex("""^#\s*@connect\s+(\S+)\s+(\S+)\s*(.*)$""")
    private val DIRECTIVE_LINE = Regex("""^#\s*@([A-Za-z_][\w\-]*)\s*(.*)$""")

    fun parse(text: CharSequence): WorkbenchModel {
        val lines = splitLines(text)
        val vars = ArrayList<VarDecl>()
        val connections = ArrayList<ConnectDecl>()
        val blocks = ArrayList<Block>()

        var i = 0
        // Header: everything before the first separator.
        while (i < lines.size && !isSeparator(lines[i].text)) {
            val l = lines[i]
            VAR_LINE.matchEntire(l.text)?.let { vars += VarDecl(it.groupValues[1], it.groupValues[2].trim(), i) }
                ?: CONNECT_LINE.matchEntire(l.text)?.let {
                    connections += ConnectDecl(it.groupValues[1], it.groupValues[2], parseOptions(it.groupValues[3]), i)
                }
            i++
        }
        val headerEnd = if (i < lines.size) lines[i].start else text.length

        while (i < lines.size) {
            val sepLine = lines[i]
            var j = i + 1
            while (j < lines.size && !isSeparator(lines[j].text)) j++
            blocks += parseBlock(blocks.size, sepLine, lines.subList(i + 1, j), blockEnd = if (j < lines.size) lines[j].start else text.length)
            i = j
        }
        return WorkbenchModel(vars, connections, blocks, TextRange(0, headerEnd))
    }

    private fun parseBlock(index: Int, sep: Line, body: List<Line>, blockEnd: Int): Block {
        val words = sep.text.removePrefix("###").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val kind = words.getOrNull(0) ?: ""
        val name = words.getOrNull(1)
        val label = if (words.size > 2) words.drop(2).joinToString(" ") else null

        val directives = ArrayList<Directive>()
        var bodyStart = -1
        var bodyEnd = -1
        var result: TextRange? = null

        var k = 0
        while (k < body.size) {
            val l = body[k]
            val t = l.text
            when {
                ResultMarkers.isStart(t) -> {
                    // Skip to the end marker; lenient: without one, the section runs to the last
                    // non-blank line of the block.
                    var e = k
                    while (e < body.size && !ResultMarkers.isEnd(body[e].text)) e++
                    val endLine = if (e < body.size) body[e] else body.subList(k, body.size).lastOrNull { it.text.isNotBlank() } ?: l
                    result = TextRange(l.start, endLine.end)
                    k = maxOf(e, k) + 1
                    continue
                }
                t.startsWith("#") -> {
                    DIRECTIVE_LINE.matchEntire(t)?.let { directives += Directive(it.groupValues[1], it.groupValues[2].trim(), l.line) }
                }
                t.isNotBlank() -> {
                    if (bodyStart < 0) bodyStart = l.start
                    bodyEnd = l.end
                }
            }
            k++
        }
        return Block(
            index = index,
            kind = kind,
            name = name,
            label = label,
            separatorLine = sep.line,
            range = TextRange(sep.start, blockEnd),
            separator = TextRange(sep.start, sep.end),
            directives = directives,
            body = if (bodyStart >= 0) TextRange(bodyStart, bodyEnd) else null,
            result = result,
        )
    }

    fun isSeparator(line: CharSequence): Boolean = line.startsWith("###")

    /** `k=v k="quoted value" flag` → map; a bare word is a flag with value "true". */
    fun parseOptions(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < s.length) {
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) break
            val keyStart = i
            while (i < s.length && s[i] != '=' && !s[i].isWhitespace()) i++
            val key = s.substring(keyStart, i)
            if (i < s.length && s[i] == '=') {
                i++
                val value: String
                if (i < s.length && s[i] == '"') {
                    val close = s.indexOf('"', i + 1).let { if (it < 0) s.length else it }
                    value = s.substring(i + 1, close)
                    i = close + 1
                } else {
                    val vs = i
                    while (i < s.length && !s[i].isWhitespace()) i++
                    value = s.substring(vs, i)
                }
                out[key] = value
            } else if (key.isNotEmpty()) {
                out[key] = "true"
            }
        }
        return out
    }

    class Line(val line: Int, val start: Int, val end: Int, val text: String)

    /** Lines without their terminators; [Line.end] is the offset of the `\n` (or EOF). */
    fun splitLines(text: CharSequence): List<Line> {
        val out = ArrayList<Line>()
        var start = 0
        var n = 0
        while (start <= text.length) {
            var end = start
            while (end < text.length && text[end] != '\n') end++
            val raw = text.subSequence(start, end).toString()
            val t = if (raw.endsWith('\r')) raw.dropLast(1) else raw
            out += Line(n++, start, start + t.length, t)
            if (end >= text.length) break
            start = end + 1
        }
        return out
    }
}
