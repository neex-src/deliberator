package com.neex.tellij.sources.sqs

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

class SqsSourceTest {
    private val header = "# @connect sqs local endpoint=http://localhost:9324 region=us-east-1 accessKey=x secretKey=x"

    private fun send(hdr: String, block: String, body: String): MemorySink {
        val model = WorkbenchParser.parse("$hdr\n$block\n$body\n")
        val src = SqsSource()
        val conn = src.connect(model.connections[0], ConnectContext(Vars(emptyMap()), null)) as SqsConnection
        val sink = MemorySink("s")
        try {
            src.step(StepRequest(model.blocks[0], body, model.blocks[0].name, Vars(emptyMap())), conn).run(TestContext(), sink)
        } finally {
            conn.close()
        }
        return sink
    }

    @Test
    fun sendsToElasticMqAndTheMessageIsThere() {
        assumeTrue("ElasticMQ not running", runCatching { Socket("localhost", 9324).close(); true }.getOrDefault(false))
        val model = WorkbenchParser.parse("$header\n")
        val conn = SqsSource().connect(model.connections[0], ConnectContext(Vars(emptyMap()), null)) as SqsConnection
        val url = conn.client().createQueue { it.queueName("tellij-test") }.queueUrl()
        conn.client().purgeQueue { it.queueUrl(url) }
        val sink = send(header, "### sqs tellij-test\n# @header kind=test", "{\"b\":2}")
        assertEquals("done", sink.terminal)
        assertTrue(sink.lines[0], Regex("sent queue=tellij-test messageId=[0-9a-f-]+ md5=[0-9a-f]+ bytes=7 attributes=kind").matches(sink.lines[0]))
        val got = conn.client().receiveMessage { it.queueUrl(url).waitTimeSeconds(2) }.messages()
        assertEquals("{\"b\":2}", got.single().body())
        conn.close()

        try { send(header, "### sqs no-such-queue-xyz", "x"); org.junit.Assert.fail() } catch (e: StepError) { assertTrue(e.message, e.message!!.startsWith("queue 'no-such-queue-xyz' does not exist")) }
    }

    @Test
    fun unreachableEndpointFails() {
        try {
            send("# @connect sqs down endpoint=http://localhost:1 timeout=1s", "### sqs q", "x")
            org.junit.Assert.fail()
        } catch (e: StepError) {
            assertTrue(e.message, e.message!!.startsWith("cannot reach http://localhost:1"))
        }
    }
}
