package com.neex.tellij.render

/**
 * Query results as a monospace table that greps well: one row per line, `NULL` spelled out,
 * embedded newlines escaped, wide cells cut with an ellipsis. The trailer says what was cut.
 */
object TableRenderer {
    const val MAX_CELL = 40

    fun render(columns: List<String>, rows: List<List<String?>>, truncatedRows: Int = 0, limit: Int = 0): String {
        val cells = rows.map { r -> r.map { cell(it) } }
        val widths = columns.indices.map { i -> maxOf(columns[i].length, cells.maxOfOrNull { it.getOrNull(i)?.length ?: 0 } ?: 0) }
        val sb = StringBuilder()
        sb.append(columns.indices.joinToString(" | ") { columns[it].padEnd(widths[it]) }.trimEnd()).append('\n')
        sb.append(widths.joinToString("-+-") { "-".repeat(it) }).append('\n')
        for (r in cells) {
            sb.append(columns.indices.joinToString(" | ") { (r.getOrNull(it) ?: "").padEnd(widths[it]) }.trimEnd()).append('\n')
        }
        if (truncatedRows > 0) sb.append("… $truncatedRows more rows (limit $limit, # @limit N to raise)\n")
        return sb.toString().trimEnd('\n')
    }

    fun cell(value: String?): String {
        if (value == null) return "NULL"
        val flat = value.replace("\r\n", "\n").replace("\n", "\\n").replace('\t', ' ')
        return if (flat.length > MAX_CELL) flat.substring(0, MAX_CELL - 1) + "…" else flat
    }

    fun rowsAffected(n: Int): String = "($n row${if (n == 1) "" else "s"} affected)"
    fun rowCount(n: Int, elapsedMs: Long): String = "($n row${if (n == 1) "" else "s"} · $elapsedMs ms)"
}
