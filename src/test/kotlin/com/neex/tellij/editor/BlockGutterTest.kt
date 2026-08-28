package com.neex.tellij.editor

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.neex.tellij.Keys

class BlockGutterTest : BasePlatformTestCase() {

    fun testOneIconPerBlockFollowsEdits() {
        myFixture.configureByText("x.tellij", "# @connect kafka l simulate=true\n### kafka a\nbody\n\n### kafka b\nbody\n")
        val gutter = myFixture.editor.getUserData(Keys.GUTTER)
        assertNotNull("gutter installed by the editor factory listener", gutter)
        assertEquals(listOf(0, 1), gutter!!.renderers().map { it.blockIndex })
        assertTrue(gutter.renderers().none { it.running })

        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.textLength)
        myFixture.type("\n### kafka c\nbody\n")
        gutter.refreshNow()
        assertEquals(3, gutter.renderers().size)
    }

    fun testNoGutterOnOtherFiles() {
        myFixture.configureByText("x.txt", "### kafka a\n")
        assertNull(myFixture.editor.getUserData(Keys.GUTTER))
    }
}
