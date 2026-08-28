package com.neex.tellij.render

import org.junit.Assert.assertEquals
import org.junit.Test

class TableRendererTest {
    @Test
    fun rendersAlignedTableWithNullsAndEscapes() {
        val out = TableRenderer.render(
            listOf("id", "note", "n"),
            listOf(listOf("a", "line1\nline2", "1"), listOf("bb", null, "22")),
        )
        assertEquals(
            "id | note         | n\n" +
            "---+--------------+---\n" +
            "a  | line1\\nline2 | 1\n" +
            "bb | NULL         | 22", out
        )
    }

    @Test
    fun capsWideCellsAndAddsTrailer() {
        val wide = "x".repeat(60)
        val out = TableRenderer.render(listOf("c"), listOf(listOf(wide)), truncatedRows = 1, limit = 1)
        val lines = out.lines()
        assertEquals("x".repeat(39) + "…", lines[2])
        assertEquals("… 1 more rows (limit 1, # @limit N to raise)", lines[3])
    }

    @Test
    fun counts() {
        assertEquals("(1 row affected)", TableRenderer.rowsAffected(1))
        assertEquals("(0 rows affected)", TableRenderer.rowsAffected(0))
        assertEquals("(3 rows · 12 ms)", TableRenderer.rowCount(3, 12))
    }
}
