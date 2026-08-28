package com.neex.tellij.sources.sql

import com.neex.tellij.engine.MemorySink
import com.neex.tellij.engine.TestContext
import com.neex.tellij.model.WorkbenchParser
import com.neex.tellij.spi.ConnectContext
import com.neex.tellij.spi.StepError
import com.neex.tellij.spi.StepRequest
import com.neex.tellij.vars.Vars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Socket

/** Runs only when the demo Postgres (demo/up.sh) answers on localhost:5432. */
class PostgresSourceTest {
    private val url = "jdbc:postgresql://localhost:5432/demo"

    private fun pgUp() = runCatching { Socket("localhost", 5432).close(); true }.getOrDefault(false)

    private fun run(sql: String, limit: Int? = null): MemorySink {
        val text = "# @connect sql pg url=$url user=demo password=demo\n### sql pg\n" + (limit?.let { "# @limit $it\n" } ?: "") + sql + "\n"
        val model = WorkbenchParser.parse(text)
        val src = SqlSource()
        val conn = src.connect(model.connections[0], ConnectContext(Vars(emptyMap()), null))
        val sink = MemorySink("sql")
        try {
            src.step(StepRequest(model.blocks[0], sql, null, Vars(emptyMap())), conn).run(TestContext(), sink)
        } finally {
            conn.close()
        }
        return sink
    }

    @Test
    fun dmlThenSelectThenError() {
        assumeTrue("demo postgres not running", pgUp())
        run("drop table if exists tellij_t")
        assertEquals("(0 rows affected)", run("create table tellij_t(id int, name text)").lines[0])
        assertEquals("(3 rows affected)", run("insert into tellij_t values (1,'a'),(2,null),(3,'c')").lines[0])
        val sel = run("select id, name from tellij_t order by id", limit = 2)
        assertEquals(listOf("id | name", "---+-----", "1  | a", "2  | NULL", "… 1 more rows (limit 2, # @limit N to raise)"), sel.lines.take(5))
        assertTrue(sel.lines[5], sel.lines[5].startsWith("(2 rows · ") && sel.lines[5].endsWith("(more available)"))
        try { run("select * from no_such_table"); org.junit.Assert.fail() } catch (e: StepError) { assertTrue(e.message!!.contains("no_such_table")) }
        run("drop table tellij_t")
    }

    @Test
    fun badUrlAndDownServer() {
        val src = SqlSource()
        val decl = WorkbenchParser.parse("# @connect sql x url=jdbc:mysql://h/db\n").connections[0]
        try { src.connect(decl, ConnectContext(Vars(emptyMap()), null)); org.junit.Assert.fail() } catch (e: StepError) { assertTrue(e.message!!.contains("jdbc:postgresql")) }
        val down = WorkbenchParser.parse("# @connect sql x url=jdbc:postgresql://localhost:1/db timeout=1s\n### sql x\nselect 1\n")
        val conn = src.connect(down.connections[0], ConnectContext(Vars(emptyMap()), null))
        try {
            src.step(StepRequest(down.blocks[0], "select 1", null, Vars(emptyMap())), conn).run(TestContext(), MemorySink("d"))
            org.junit.Assert.fail()
        } catch (e: StepError) {
            assertTrue(e.message, e.message!!.startsWith("cannot connect"))
        }
    }
}
