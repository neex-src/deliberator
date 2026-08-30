package com.neex.tellij.asserts

import com.google.gson.JsonParser
import com.neex.tellij.spi.StepError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AssertExprTest {
    private val facts = Facts(mapOf(
        "status" to 201, "rows" to 1, "elapsed" to 42L, "body" to "{\"status\":\"PAID\"}",
        "$" to JsonParser.parseString("""{"status":"PAID","amount":42.5,"items":[{"sku":"a"}],"nothing":null}"""),
        "doc" to JsonParser.parseString("""{"kind":"order"}"""),
        "headers.content-type" to "application/json", "col.Status" to "PAID", "messageId" to "7d1c-ab",
    ))

    private fun check(expr: String) = AssertExpr.parse(expr).eval(facts)

    @Test
    fun comparisons() {
        assertTrue(check("status == 201").ok)
        assertTrue(check("status != 200").ok)
        assertTrue(check("status >= 201").ok && check("status > 200").ok && check("status < 300").ok && check("status <= 201").ok)
        assertFalse(check("status == 200").ok)
        assertEquals("✗ status == 200 (was 201)", check("status == 200").line)
        assertTrue(check("$.amount == 42.50").ok)
        assertTrue(check("$.status == \"PAID\"").ok && check("$.status == 'PAID'").ok && check("$.status == PAID").ok)
        assertEquals("✗ $.status == \"NEW\" (was \"PAID\")", check("$.status == \"NEW\"").line)
        assertTrue(check("$.items[0].sku == a").ok)
        assertTrue(check("doc.kind == order").ok)
        assertTrue(check("col.status == \"PAID\"").ok)
        assertTrue(check("headers.Content-Type contains json").ok)
        assertTrue(check("body contains PAID").ok)
        assertTrue(check("messageId matches /^[0-9a-f]+-/").ok)
        assertTrue(check("messageId matches /AB$/i").ok)
        assertTrue(check("$.items[0].sku exists").ok && check("$.nothing missing").ok && check("$.nope missing").ok)
        assertFalse(check("$.nothing exists").ok)
        assertEquals("✗ $.status > 1 (was not a number: \"PAID\")", check("$.status > 1").line)
        assertEquals("✗ nope == 1 (was no fact 'nope'; facts: status, rows, elapsed, body, $, doc, headers.content-type, col.Status, messageId)", check("nope == 1").line)
    }

    @Test
    fun parseErrorsHaveHints() {
        for (bad in listOf("status", "status === 1", "status ==", "x exists 1", "x matches /(/")) {
            try { AssertExpr.parse(bad); fail(bad) } catch (e: StepError) { assertTrue(e.message!!.startsWith("bad")) }
        }
        assertEquals("✓ status == 201", check("  status ==   201  ").line)
    }

    @Test
    fun factTexts() {
        assertEquals("42.5", Facts.text(42.5))
        assertEquals("1", Facts.text(1.0))
        assertEquals("a,b", Facts.text(listOf("a", "b")))
        assertEquals("null", Facts.text(null))
        assertEquals("{\"a\":1}", Facts.text(JsonParser.parseString("{\"a\":1}")))
    }
}
