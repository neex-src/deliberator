package com.neex.tellij.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkbenchParserTest {

    private val sample = """
        |# Tellij workbench
        |# @var orderId = ord-{{uuid}}
        |# @var customer = alice
        |# @connect kafka local bootstrap=localhost:9092 simulate=true
        |# @connect logs consumer file="/var/log/my app.log" podman=tellij-consumer verbose
        |
        |### kafka orders
        |# @key {{orderId}}
        |{ "orderId": "{{orderId}}",
        |  "customer": "{{customer}}" }
        |
        |#> result kafka orders · 2026-08-28 16:40:01 · 23 ms
        |# vars: orderId=ord-3f2a
        |sent topic=orders
        |#< end
        |
        |### logs consumer the app log
        |# @filter {{orderId}}
        |# @for 10s
        |
        |### sql pg
        |select 1
        |# @limit 20
        |""".trimMargin()

    @Test
    fun headerVarsAndConnections() {
        val m = WorkbenchParser.parse(sample)
        assertEquals(listOf("orderId", "customer"), m.vars.map { it.name })
        assertEquals("ord-{{uuid}}", m.vars[0].rawValue)
        assertEquals(2, m.connections.size)
        val kafka = m.connection("kafka", "local")!!
        assertEquals(mapOf("bootstrap" to "localhost:9092", "simulate" to "true"), kafka.options)
        val logs = m.connection("logs", "consumer")!!
        assertEquals("/var/log/my app.log", logs.option("file"))
        assertEquals("true", logs.option("verbose"))
        assertEquals(sample.indexOf("### kafka"), m.headerRange.end)
    }

    @Test
    fun blocksKindsNamesDirectivesBodies() {
        val m = WorkbenchParser.parse(sample)
        assertEquals(listOf("kafka", "logs", "sql"), m.blocks.map { it.kind })
        assertEquals(listOf("orders", "consumer", "pg"), m.blocks.map { it.name })
        assertEquals("the app log", m.blocks[1].label)
        val kafka = m.blocks[0]
        assertEquals("{{orderId}}", kafka.directive("key"))
        assertEquals("{ \"orderId\": \"{{orderId}}\",\n  \"customer\": \"{{customer}}\" }", kafka.body!!.substring(sample))
        assertNull(m.blocks[1].body)
        assertEquals(listOf("{{orderId}}"), m.blocks[1].directives("filter"))
        // Directive after the body still counts.
        assertEquals("20", m.blocks[2].directive("limit"))
        assertEquals("select 1", m.blocks[2].body!!.substring(sample))
    }

    @Test
    fun resultSectionIsAttachedAndSkipped() {
        val m = WorkbenchParser.parse(sample)
        val r = m.blocks[0].result!!
        assertTrue(r.substring(sample).startsWith("#> result kafka orders"))
        assertTrue(r.substring(sample).endsWith("#< end"))
        assertNull(m.blocks[1].result)
        // The section's lines are neither body nor directives.
        assertEquals(1, m.blocks[0].directives.size)
    }

    @Test
    fun missingEndMarkerIsLenient() {
        val text = "### kafka a\nbody\n\n#> result kafka a · x\nline1\nline2\n\n### kafka b\nbody2\n"
        val m = WorkbenchParser.parse(text)
        assertEquals(2, m.blocks.size)
        assertEquals("#> result kafka a · x\nline1\nline2", m.blocks[0].result!!.substring(text))
        assertEquals("body2", m.blocks[1].body!!.substring(text))
    }

    @Test
    fun blockAtOffsets() {
        val m = WorkbenchParser.parse(sample)
        assertNull(m.blockAt(0))
        assertEquals(0, m.blockAt(sample.indexOf("### kafka"))!!.index)
        assertEquals(0, m.blockAt(sample.indexOf("sent topic"))!!.index)
        assertEquals(1, m.blockAt(sample.indexOf("@for"))!!.index)
        assertEquals(2, m.blockAt(sample.length)!!.index)
    }

    @Test
    fun emptyAndHeaderOnlyFiles() {
        assertEquals(0, WorkbenchParser.parse("").blocks.size)
        val m = WorkbenchParser.parse("# @var a = 1\n")
        assertEquals(1, m.vars.size)
        assertEquals(0, m.blocks.size)
    }

    @Test
    fun optionsParsing() {
        assertEquals(mapOf("a" to "1", "b" to "x y", "c" to "true"), WorkbenchParser.parseOptions("a=1 b=\"x y\" c"))
        assertEquals(emptyMap<String, String>(), WorkbenchParser.parseOptions("   "))
    }
}
