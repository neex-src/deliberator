package com.neex.tellij.sources.http

import com.neex.tellij.engine.MemorySink
import com.neex.tellij.engine.TestContext
import com.neex.tellij.model.WorkbenchParser
import com.neex.tellij.spi.ConnectContext
import com.neex.tellij.spi.StepError
import com.neex.tellij.spi.StepRequest
import com.neex.tellij.vars.Vars
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class HttpSourceTest {
    private lateinit var server: HttpServer
    private var port = 0
    @Volatile private var lastBody: String? = null
    @Volatile private var lastHeader: String? = null

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.createContext("/orders/") { ex ->
            lastHeader = ex.requestHeaders.getFirst("X-Test")
            lastBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val id = ex.requestURI.path.removePrefix("/orders/")
            val (code, json) = if (id == "missing") 404 to """{"error":"not found"}""" else 200 to """{"orderId":"$id","items":[{"sku":"a","qty":2}],"status":"PAID","note":null}"""
            ex.responseHeaders.add("Content-Type", "application/json")
            val b = json.toByteArray(); ex.sendResponseHeaders(code, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.createContext("/text") { ex -> val b = "plain\ntext".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun run(header: String, block: String, body: String, ctx: TestContext = TestContext()): MemorySink {
        val model = WorkbenchParser.parse("$header\n$block\n$body\n")
        val src = HttpSource()
        val decl = model.connections.firstOrNull() ?: src.defaultConnection()
        val conn = src.connect(decl, ConnectContext(Vars(emptyMap()), null))
        val sink = MemorySink("h")
        src.step(StepRequest(model.blocks[0], body, null, Vars(emptyMap())), conn).run(ctx, sink)
        return sink
    }

    @Test
    fun getRendersStatusHeadersAndPrettyJsonAndCaptures() {
        val ctx = TestContext()
        val sink = run("", "### http\n# @capture status = \$.status\n# @capture sku = \$.items[0].sku\n# @capture note = \$.note", "GET http://127.0.0.1:$port/orders/o1\nX-Test: yes", ctx)
        assertEquals("done", sink.terminal)
        assertEquals("HTTP/1.1 200 OK", sink.lines[0])
        assertTrue(sink.lines.toString(), sink.lines.contains("content-type: application/json"))
        val blank = sink.lines.indexOf("")
        assertEquals(listOf("{", "  \"orderId\": \"o1\",", "  \"items\": [", "    {", "      \"sku\": \"a\",", "      \"qty\": 2", "    }", "  ],", "  \"status\": \"PAID\",", "  \"note\": null", "}"), sink.lines.drop(blank + 1))
        assertEquals("yes", lastHeader)
        assertEquals(mapOf("status" to "PAID", "sku" to "a", "note" to "null"), ctx.captures)
    }

    @Test
    fun postWithBodyAndBaseUrlAndSharedHeader() {
        val sink = run("# @connect http api base=http://127.0.0.1:$port header.X-Test=shared", "### http", "POST /orders/o2\nContent-Type: application/json\n\n{\"x\":1}")
        assertEquals("HTTP/1.1 200 OK", sink.lines[0])
        assertEquals("{\"x\":1}", lastBody)
        assertEquals("shared", lastHeader)
    }

    @Test
    fun errorsAreExplicit() {
        assertEquals("HTTP/1.1 404 Not Found", run("", "### http", "http://127.0.0.1:$port/orders/missing").lines[0])
        assertEquals(listOf("HTTP/1.1 200 OK"), run("", "### http", "GET http://127.0.0.1:$port/text").lines.take(1))
        assertTrue(run("", "### http", "GET http://127.0.0.1:$port/text").lines.containsAll(listOf("plain", "text")))
        try { run("", "### http", "GET /relative"); org.junit.Assert.fail() } catch (e: StepError) { assertTrue(e.message!!.contains("needs a base")) }
        try { run("", "### http\n# @capture x = \$.nope", "GET http://127.0.0.1:$port/orders/o3"); org.junit.Assert.fail() } catch (e: StepError) { assertTrue(e.message, e.message!!.startsWith("capture: no 'nope'")) }
        server.stop(0)
        try { run("", "### http", "GET http://127.0.0.1:$port/orders/o4"); org.junit.Assert.fail() } catch (e: StepError) { assertTrue(e.message, e.message!!.startsWith("cannot reach 127.0.0.1")) }
    }

    @Test
    fun requestParserShapes() {
        val r = HttpRequestParser.parse("\n\nPOST http://h/p HTTP/1.1\nA: 1\nB: x: y\n\nline1\nline2\n\n")
        assertEquals(HttpRequestSpec("POST", "http://h/p", listOf("A" to "1", "B" to "x: y"), "line1\nline2"), r)
        assertEquals(HttpRequestSpec("GET", "http://h/q", emptyList(), null), HttpRequestParser.parse("http://h/q"))
        assertNull(HttpRequestParser.parse("DELETE http://h/q\n").body)
        try { HttpRequestParser.parse("GET http://h\nnot a header\n"); org.junit.Assert.fail() } catch (e: StepError) { assertTrue(e.message!!.startsWith("bad header line")) }
        assertEquals("2", JsonPathLite.get("""{"a":{"b":[{"c":2}]}}""", "$.a.b[0].c"))
        assertEquals("{\"c\":2}", JsonPathLite.get("""{"a":{"b":[{"c":2}]}}""", "a.b[0]"))
    }
}
