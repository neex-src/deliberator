package com.neex.tellij.java

import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.neex.tellij.engine.MemorySink
import com.neex.tellij.engine.TestContext
import com.neex.tellij.java.controller.ControllerSource
import com.neex.tellij.java.controller.JsonComments
import com.neex.tellij.java.controller.JsonKeyScanner
import com.neex.tellij.model.WorkbenchParser
import com.neex.tellij.spi.ConnectContext
import com.neex.tellij.spi.StepError
import com.neex.tellij.spi.StepRequest
import com.neex.tellij.vars.Vars
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

class ControllerSourceTest : LightJavaCodeInsightFixtureTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_LATEST

    private lateinit var server: HttpServer
    private var port = 0
    @Volatile private var lastPath: String? = null
    @Volatile private var lastBody: String? = null
    @Volatile private var lastMethod: String? = null
    @Volatile private var lastTrace: String? = null

    override fun setUp() {
        super.setUp()
        SpringStubs.project(myFixture)
        myFixture.addClass(SpringStubs.ORDERS_CONTROLLER)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.createContext("/") { ex ->
            lastPath = ex.requestURI.toString(); lastMethod = ex.requestMethod; lastTrace = ex.requestHeaders.getFirst("X-Trace")
            lastBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val json = """{"orderId":"o1","status":"NEW"}"""
            ex.responseHeaders.add("Content-Type", "application/json")
            val b = json.toByteArray(); ex.sendResponseHeaders(if (lastMethod == "POST") 201 else 200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.start()
    }

    override fun tearDown() {
        try { server.stop(0) } finally { super.tearDown() }
    }

    private fun run(text: String): MemorySink {
        val model = WorkbenchParser.parse(text)
        val src = ControllerSource()
        val decl = model.connections.firstOrNull() ?: src.defaultConnection()
        val conn = src.connect(decl, ConnectContext(Vars(emptyMap()), null))
        val block = model.blocks[0]
        val body = block.body?.substring(text) ?: ""
        val sink = MemorySink("c")
        src.step(StepRequest(block, body, block.name, Vars(emptyMap()), mapOf("project" to project), model), conn).run(TestContext(), sink)
        return sink
    }

    fun testCreateBuildsTheRequestFromTheController() {
        val sink = run("""
            # @connect controller api base=http://127.0.0.1:$port header.X-Trace=t1
            ### controller OrdersController.create
            # @capture id = ${'$'}.orderId
            {
              "orderId": "o1",   // comment
              /* "customer": "gone", */
              "amount": 42.5
            }
        """.trimIndent())
        assertEquals("done", sink.terminal)
        assertEquals("POST http://127.0.0.1:$port/api/orders   (OrdersController.create ← OrdersApi)", sink.lines[0])
        assertTrue(sink.lines.toString(), sink.lines.contains("X-Trace: t1") && sink.lines.contains("Content-Type: application/json") && sink.lines.contains("HTTP/1.1 201 Created"))
        assertEquals("POST", lastMethod); assertEquals("/api/orders", lastPath); assertEquals("t1", lastTrace)
        assertEquals("{\n  \"orderId\": \"o1\",   \n  \n  \"amount\": 42.5\n}", lastBody)
        assertEquals("POST /api/orders", sink.facts["endpoint"]); assertEquals(201, sink.facts["status"])
    }

    fun testGetWithPathAndQuery() {
        val sink = run("""
            # @connect controller api base=http://127.0.0.1:$port
            ### controller com.acme.api.OrdersController.get
            # @path id=o 1
            # @query verbose=true
            # @query q=a&b
        """.trimIndent())
        assertEquals("GET http://127.0.0.1:$port/api/orders/o+1?verbose=true&q=a%26b   (OrdersController.get ← OrdersApi)", sink.lines[0])
        assertEquals("GET", lastMethod); assertEquals("/api/orders/o+1?verbose=true&q=a%26b", lastPath)
        assertTrue(sink.lines.contains("HTTP/1.1 200 OK"))
    }

    fun testErrorsHaveHints() {
        fun err(text: String): StepError = try { run(text); fail("expected StepError"); throw IllegalStateException() } catch (e: StepError) { e }
        assertTrue(err("### controller OrdersController.get\n").message!!.startsWith("path variable 'id' not set"))
        assertTrue(err("### controller OrdersController.helper\n").message!!.contains("not a Spring MVC handler"))
        assertTrue(err("### controller Nope.x\n").message!!.startsWith("no class 'Nope'"))
        assertTrue(err("### controller OrdersController.bulk\n{}\n").hint!!.contains("bulk(CreateOrderRequest)"))
        // `# @method` picks the overload; bulk(String) carries no mapping, so it is the one that is not a handler
        assertTrue(err("### controller OrdersController.bulk\n# @method bulk(String)\n{}\n").message!!.contains("OrdersController.bulk is not a Spring MVC handler"))
    }

    fun testCommentsAndScanner() {
        assertEquals("{ \"a\": \"x//y\",  \"b\": 1 }", JsonComments.strip("{ \"a\": \"x//y\", /* gone */ \"b\": 1 // tail\n}".replace(" // tail\n}", " }")))
        val body = "{\n  \"orderId\": \"o1\",\n  \"items\": [ { \"sku\": \"a\", \"q"
        val pos = JsonKeyScanner.pathAt(body, body.length)!!
        assertEquals(listOf<Any>("items", 0), pos.path); assertTrue(pos.atKey); assertEquals("q", pos.partial); assertEquals(listOf("sku"), pos.siblings)
        val top = JsonKeyScanner.pathAt("{ \"orderId\": 1, ", 16)!!
        assertEquals(emptyList<Any>(), top.path); assertTrue(top.atKey); assertEquals(listOf("orderId"), top.siblings)
        assertFalse(JsonKeyScanner.pathAt("{ \"orderId\": ", 13)!!.atKey)
        val keys = JsonKeyScanner.keys("{ \"a\": 1, \"items\": [ { \"sku\": \"x\" }, { \"qty\": 2 } ] }")
        assertEquals(listOf("a" to emptyList<Any>(), "items" to emptyList(), "sku" to listOf<Any>("items", 0), "qty" to listOf<Any>("items", 1)), keys.map { it.name to it.path })
    }
}
