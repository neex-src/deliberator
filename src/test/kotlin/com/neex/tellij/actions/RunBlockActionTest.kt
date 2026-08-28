package com.neex.tellij.actions

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.neex.tellij.Keys

class RunBlockActionTest : BasePlatformTestCase() {

    fun testRegisteredActionRunsBlockAtCaret() {
        myFixture.configureByText(
            "x.tellij",
            "# @connect kafka l simulate=true\n\n### kafka orders\n{ \"a\": 1 }<caret>\n\n### kafka other\n{ \"b\": 2 }\n"
        )
        val d = myFixture.editor.document
        myFixture.performEditorAction("com.neex.tellij.RunBlock")
        assertNotNull(d.getUserData(Keys.SESSION))
        PlatformTestUtil.waitWithEventsDispatching("run", { d.getUserData(Keys.SESSION) == null }, 15)
        assertTrue(d.text, d.text.contains("simulated send topic=orders"))
        assertFalse(d.text, d.text.contains("topic=other"))

        myFixture.performEditorAction("com.neex.tellij.ClearResults")
        assertEquals("# @connect kafka l simulate=true\n\n### kafka orders\n{ \"a\": 1 }\n\n### kafka other\n{ \"b\": 2 }\n", d.text)
    }

    fun testActionsDisabledOutsideTellij() {
        myFixture.configureByText("x.txt", "### kafka orders\nx<caret>\n")
        val p = myFixture.testAction(RunBlockAction())
        assertFalse(p.isEnabled)
    }
}
