package com.neex.tellij.java

import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.neex.tellij.java.summary.EndpointSummaryInlay

class EndpointSummaryInlayTest : LightJavaCodeInsightFixtureTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_LATEST

    private fun blockInlays() = myFixture.editor.inlayModel.getBlockElementsInRange(0, myFixture.editor.document.textLength).size

    fun testToggleEscAndInvalidation() {
        SpringStubs.project(myFixture)
        myFixture.configureByText("OrdersController.java", SpringStubs.ORDERS_CONTROLLER.replace("int x = 1;", "int x = 1;<caret>"))
        myFixture.performEditorAction("com.neex.tellij.EndpointSummary")
        assertEquals(1, blockInlays())
        assertEquals(1, EndpointSummaryInlay.openCount(myFixture.editor))
        myFixture.performEditorAction("com.neex.tellij.EndpointSummary")
        assertEquals(0, blockInlays())

        myFixture.performEditorAction("com.neex.tellij.EndpointSummary")
        assertEquals(1, blockInlays())
        myFixture.performEditorAction("EditorEscape")
        assertEquals(0, blockInlays())
        assertEquals(0, EndpointSummaryInlay.openCount(myFixture.editor))

        // Not a handler → nothing opens, no exception.
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("helper()"))
        myFixture.performEditorAction("com.neex.tellij.EndpointSummary")
        assertEquals(0, blockInlays())
    }
}
