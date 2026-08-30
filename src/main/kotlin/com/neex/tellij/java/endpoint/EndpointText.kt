package com.neex.tellij.java.endpoint

/** The summary as aligned plain text — shared by the inline panel, results and tests. */
object EndpointText {

    fun headline(e: Endpoint): String {
        val p = e.primary
        return e.title + (p?.let { " → ${it.status}" + (it.type?.let { t -> " $t" } ?: "") } ?: "")
    }

    fun render(e: Endpoint, width: Int = 100): List<String> {
        val out = ArrayList<String>()
        out += headline(e)
        e.summary?.let { out += it }
        e.description?.let { out += wrap(it, width) }
        if (e.tags.isNotEmpty()) out += "tags " + e.tags.joinToString(", ")
        if (e.params.isNotEmpty()) {
            out += "params"
            out += table(e.params.map { p ->
                listOf(p.where.label, p.name, p.type, when { p.default != null -> "default=${p.default}"; p.required -> "required"; else -> "optional" }, p.description ?: "")
            })
        }
        if (e.responses.isNotEmpty()) {
            out += "responses"
            out += table(e.responses.map { r -> listOf(r.status.toString(), r.description ?: "", r.type ?: "") })
        }
        e.body?.let { shape ->
            out += "body ${shape.type}"
            out += shapeRows(shape, 1)
        }
        return out
    }

    private fun shapeRows(shape: DtoShape, depth: Int): List<String> {
        val rows = ArrayList<List<String>>()
        fun walk(s: DtoShape, d: Int) {
            for (f in s.fields) {
                val flags = (listOfNotNull(if (f.required) "required" else null) + f.constraints).joinToString(", ")
                rows += listOf("  ".repeat(d) + f.name, f.type, flags, f.description ?: "")
                f.nested?.let { walk(it, d + 1) }
            }
        }
        walk(shape, depth)
        return table(rows, indent = 0)
    }

    private fun table(rows: List<List<String>>, indent: Int = 2): List<String> {
        if (rows.isEmpty()) return emptyList()
        val cols = rows.maxOf { it.size }
        val widths = (0 until cols).map { c -> rows.maxOf { it.getOrNull(c)?.length ?: 0 } }
        return rows.map { r ->
            (" ".repeat(indent) + r.indices.joinToString("   ") { c -> if (c == r.lastIndex) r[c] else r[c].padEnd(widths[c]) }).trimEnd()
        }
    }

    private fun wrap(text: String, width: Int): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val lines = ArrayList<String>()
        val cur = StringBuilder()
        for (w in words) {
            if (cur.isNotEmpty() && cur.length + 1 + w.length > width) { lines += cur.toString(); cur.setLength(0) }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(w)
        }
        if (cur.isNotEmpty()) lines += cur.toString()
        return lines
    }
}
