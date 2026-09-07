package com.neex.tellij.java

import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class JsonBodyCompletionTest : LightJavaCodeInsightFixtureTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_LATEST

    override fun setUp() {
        super.setUp()
        SpringStubs.project(myFixture)
        myFixture.addClass(SpringStubs.ORDERS_CONTROLLER)
    }

    private fun complete(body: String): List<String>? {
        myFixture.configureByText("w.tellij", "### controller OrdersController.create\n$body\n")
        myFixture.completeBasic()
        return myFixture.lookupElementStrings
    }

    fun testFieldsAtTheCaretLevel() {
        val top = complete("{\n  \"orderId\": \"o1\",\n  <caret>\n}")!!
        assertEquals(listOf("\"amount\": ", "\"customer\": ", "\"gift_note\": ", "\"items\": "), top.sorted())
        val nested = complete("{ \"items\": [ { \"sku\": \"a\", <caret> } ] }")!!
        assertEquals(listOf("\"qty\": "), nested)
        assertNull(complete("{ \"items\": [ { \"sku\": \"a\", \"q<caret> } ] }"))
        assertEquals("### controller OrdersController.create\n{ \"items\": [ { \"sku\": \"a\", \"qty\":  } ] }\n", myFixture.editor.document.text)
        assertTrue(complete("{ \"orderId\": <caret> }").isNullOrEmpty())
    }

    fun testUnknownAndMissingKeysAreAnnotated() {
        myFixture.configureByText("w.tellij", "### controller OrdersController.create\n<weak_warning descr=\"CreateOrderRequest requires customer, amount\">{</weak_warning>\n  \"orderId\": \"o1\",\n  <weak_warning descr=\"no field 'custmer' in CreateOrderRequest\">\"custmer\"</weak_warning>: \"a\",\n  \"items\": [ { <weak_warning descr=\"no field 'skoo' in Item\">\"skoo\"</weak_warning>: \"x\" } ]\n}\n")
        myFixture.checkHighlighting(false, false, true)
    }
}
