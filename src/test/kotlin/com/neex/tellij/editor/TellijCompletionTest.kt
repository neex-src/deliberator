package com.neex.tellij.editor

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TellijCompletionTest : BasePlatformTestCase() {

    private fun complete(text: String): List<String>? {
        myFixture.configureByText("x.tellij", text)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings
    }

    fun testKindsAfterSeparatorAndConnect() {
        val kinds = complete("### <caret>")!!
        assertTrue(kinds.toString(), kinds.containsAll(listOf("kafka", "sqs", "logs", "sql", "http", "mongo")))
        assertNull(complete("### ka<caret>"))
        assertEquals("### kafka", myFixture.editor.document.text)
        assertTrue(complete("# @connect <caret>")!!.contains("logs"))
    }

    fun testDirectivesDependOnTheBlockKind() {
        val logs = complete("# @connect logs a file=x\n### logs a\n# @<caret>\n")!!
        assertTrue(logs.toString(), logs.containsAll(listOf("filter ", "for ", "conn ", "delay ", "capture ")))
        assertFalse(logs.contains("key "))
        val kafka = complete("### kafka t\n# @<caret>\n")!!
        assertTrue(kafka.toString(), kafka.contains("key ") && !kafka.contains("filter "))
        val header = complete("# @<caret>\n### kafka t\n")!!
        assertEquals(listOf("connect ", "var "), header.sorted())
    }

    fun testDirectiveValuesAndConnectOptions() {
        assertEquals(listOf("aggregate", "count", "find", "insert"), complete("### mongo c\n# @op <caret>\n")!!.sorted())
        assertTrue(complete("### logs a\n# @for <caret>\n")!!.contains("10s"))
        val opts = complete("# @connect logs a <caret>\n")!!
        assertEquals(listOf("file=", "podman="), opts.sorted())
        assertNull(complete("# @connect logs a fi<caret>\n"))
        assertEquals("# @connect logs a file=\n", myFixture.editor.document.text)
    }

    fun testAssertTemplatesPerKind() {
        val http = complete("### http\n# @assert <caret>\n")!!
        assertTrue(http.toString(), http.containsAll(listOf("status == 200", "elapsed < 500", "body contains \"PAID\"")))
        val sql = complete("### sql pg\n# @assert <caret>\n")!!
        assertTrue(sql.toString(), sql.contains("rows == 1") && !sql.contains("status == 200"))
    }

    fun testVariablesInsideBraces() {
        val vars = complete("# @var orderId = 1\n# @var customer = a\n### http\n# @capture status = \$.s\n\n### sql pg\nselect '{{<caret>\n")!!
        assertTrue(vars.toString(), vars.containsAll(listOf("orderId", "customer", "status", "uuid", "now", "now.epoch")))
        assertNull(complete("# @var orderId = 1\n### kafka t\n{{ord<caret>\n"))
        assertEquals("# @var orderId = 1\n### kafka t\n{{orderId}}\n", myFixture.editor.document.text)
        assertNull(complete("# @var orderId = 1\n### kafka t\n{{ord<caret>}}\n"))
        assertEquals("# @var orderId = 1\n### kafka t\n{{orderId}}\n", myFixture.editor.document.text)
    }

    fun testNothingElsewhere() {
        assertTrue(complete("### kafka t\n{ \"a\": <caret> }\n").isNullOrEmpty())
        assertEquals("### kafka t\n{ \"a\":  }\n", myFixture.editor.document.text)
    }
}
