package com.neex.tellij.sources.mongo

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

/** Runs only when the demo Mongo (demo/up.sh mongo) answers on localhost:27017. */
class MongoSourceTest {
    private val header = "# @connect mongo local uri=mongodb://localhost:27017/demo"

    private fun run(block: String, body: String, header: String = this.header, ctx: TestContext = TestContext()): MemorySink {
        val model = WorkbenchParser.parse("$header\n$block\n$body\n")
        val src = MongoSource()
        val conn = src.connect(model.connections[0], ConnectContext(Vars(emptyMap()), null))
        val sink = MemorySink("m")
        try {
            src.step(StepRequest(model.blocks[0], body, model.blocks[0].name, Vars(emptyMap())), conn).run(ctx, sink)
        } finally {
            conn.close()
        }
        return sink
    }

    @Test
    fun insertFindCountAggregateCapture() {
        assumeTrue("demo mongo not running", runCatching { Socket("localhost", 27017).close(); true }.getOrDefault(false))
        val conn = MongoSource().connect(WorkbenchParser.parse("$header\n").connections[0], ConnectContext(Vars(emptyMap()), null)) as MongoConnection
        conn.db().getCollection("tellij_t").drop()
        try {
            assertTrue(run("### mongo tellij_t\n# @op insert", """[{"k": 1, "tag": "a"}, {"k": 2, "tag": "b"}, {"k": 3, "tag": "a"}]""").lines[0].startsWith("inserted 3 documents:"))
            val ctx = TestContext()
            val find = run("### mongo tellij_t\n# @limit 1\n# @sort { \"k\": -1 }\n# @capture k = \$.k", """{"tag": "a"}""", ctx = ctx)
            assertEquals("done", find.terminal)
            assertTrue(find.lines.toString(), find.lines[0] == "{" && find.lines.any { it.trim() == "\"k\": 3," })
            assertTrue(find.lines.toString(), find.lines.contains("… more docs (limit 1, # @limit N to raise)"))
            assertTrue(find.lines.last(), find.lines.last().startsWith("(1 doc · "))
            assertEquals("3", ctx.captures["k"])
            assertEquals("2 documents match", run("### mongo tellij_t\n# @op count", """{"tag": "a"}""").lines[0])
            val agg = run("### mongo tellij_t\n# @op aggregate", """[{"${'$'}group": {"_id": "${'$'}tag", "n": {"${'$'}sum": 1}}}, {"${'$'}sort": {"_id": 1}}]""")
            assertTrue(agg.lines.toString(), agg.lines.any { it.trim() == "\"_id\": \"a\"," } && agg.lines.any { it.trim() == "\"n\": 2" })
            try { run("### mongo tellij_t", "not json"); org.junit.Assert.fail() } catch (e: StepError) { assertTrue(e.message!!.startsWith("filter is not a JSON document")) }
        } finally {
            conn.db().getCollection("tellij_t").drop()
            conn.close()
        }
    }

    @Test
    fun badUriAndDownServer() {
        try { run("### mongo x", "{}", header = "# @connect mongo x uri=mongodb://localhost:27017"); org.junit.Assert.fail() } catch (e: StepError) { assertTrue(e.message!!.startsWith("no database")) }
        try { run("### mongo x", "{}", header = "# @connect mongo x uri=mongodb://localhost:1/db timeout=1s"); org.junit.Assert.fail() } catch (e: StepError) { assertTrue(e.message, e.message!!.startsWith("cannot reach localhost:1")) }
    }
}
