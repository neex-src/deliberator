package com.neex.tellij.sources.kafka

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

class KafkaSourceTest {
    private fun up(port: Int) = runCatching { Socket("localhost", port).close(); true }.getOrDefault(false)

    private fun send(header: String, block: String, body: String): MemorySink {
        val text = "$header\n$block\n$body\n"
        val model = WorkbenchParser.parse(text)
        val src = KafkaSource()
        val conn = src.connect(model.connections[0], ConnectContext(Vars(emptyMap()), null))
        val sink = MemorySink("k")
        try {
            src.step(StepRequest(model.blocks[0], body, model.blocks[0].name, Vars(emptyMap())), conn).run(TestContext(), sink)
        } finally {
            conn.close()
        }
        return sink
    }

    @Test
    fun realSendReportsOffset() {
        assumeTrue("demo kafka not running", up(9092))
        val sink = send("# @connect kafka local bootstrap=localhost:9092", "### kafka tellij-test\n# @key k1\n# @header src=test", "{\"a\":1}")
        assertEquals("done", sink.terminal)
        assertTrue(sink.lines[0], Regex("sent topic=tellij-test partition=0 offset=\\d+ key=k1 bytes=7 headers=src").matches(sink.lines[0]))
    }

    @Test
    fun missingBrokerFailsFast() {
        val t0 = System.currentTimeMillis()
        try {
            send("# @connect kafka down bootstrap=localhost:1 timeout=1s", "### kafka t", "x")
            org.junit.Assert.fail()
        } catch (e: StepError) {
            assertTrue(e.message, e.message!!.startsWith("cannot send") || e.message!!.startsWith("no acknowledgement"))
        }
        assertTrue("failed within a few seconds", System.currentTimeMillis() - t0 < 15_000)
    }

    @Test
    fun simulateStillWorksAndBadHeaderIsRejected() {
        val sink = send("# @connect kafka sim simulate=true", "### kafka t", "x")
        assertEquals("simulated send topic=t partition=0 offset=0 bytes=1", sink.lines[0])
        try { send("# @connect kafka sim simulate=true", "### kafka t\n# @header nope", "x"); org.junit.Assert.fail() } catch (e: StepError) { assertTrue(e.message!!.startsWith("bad header")) }
    }
}
