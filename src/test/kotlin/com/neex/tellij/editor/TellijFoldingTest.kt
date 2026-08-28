package com.neex.tellij.editor

import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.neex.tellij.Keys
import com.neex.tellij.lang.TellijFile

class TellijFoldingTest : BasePlatformTestCase() {

    private val text = "# @connect kafka l simulate=true\n\n### kafka a\n{ \"a\": 1 }\n\n#> result kafka a · T · 1 ms · simulated\n# vars: (none)\nsimulated send topic=a\n#< end\n\n### kafka b\n{ \"b\": 2 }\n\n#> result kafka b · T · 1 ms\n# vars: (none)\nsent\n#< end\n"

    private fun open(t: String = text) {
        myFixture.configureByText("x.tellij", t)
        CodeFoldingManager.getInstance(project).updateFoldRegions(myFixture.editor)
    }

    fun testPsiIsOursAndEverySectionIsARegion() {
        open()
        assertTrue(myFixture.file is TellijFile)
        val regions = TellijFoldingBuilder.resultRegions(myFixture.editor)
        assertEquals(2, regions.size)
        assertEquals("kafka a · T · 1 ms · simulated  ⋯", regions[0].placeholderText)
        assertTrue(regions.all { it.isExpanded })
        assertEquals("#> result kafka a · T · 1 ms · simulated\n# vars: (none)\nsimulated send topic=a\n#< end", myFixture.editor.document.getText(com.intellij.openapi.util.TextRange(regions[0].startOffset, regions[0].endOffset)))
    }

    fun testEscCollapsesSectionAtCaretThenDelegates() {
        open()
        myFixture.editor.caretModel.moveToOffset(text.indexOf("simulated send"))
        myFixture.performEditorAction("EditorEscape")
        val regions = TellijFoldingBuilder.resultRegions(myFixture.editor)
        assertFalse(regions[0].isExpanded)
        assertTrue(regions[1].isExpanded)
        // Already collapsed: Esc is stock Esc again (no exception, nothing changes).
        myFixture.performEditorAction("EditorEscape")
        assertFalse(regions[0].isExpanded)
    }

    fun testCollapseAndExpandAllActions() {
        open()
        myFixture.performEditorAction("com.neex.tellij.CollapseResults")
        assertTrue(TellijFoldingBuilder.resultRegions(myFixture.editor).none { it.isExpanded })
        myFixture.performEditorAction("com.neex.tellij.ExpandResults")
        assertTrue(TellijFoldingBuilder.resultRegions(myFixture.editor).all { it.isExpanded })
    }

    fun testClearBlockResultOnlyTouchesTheCaretBlock() {
        open()
        myFixture.editor.caretModel.moveToOffset(text.indexOf("{ \"b\""))
        myFixture.performEditorAction("com.neex.tellij.ClearBlockResult")
        assertEquals("# @connect kafka l simulate=true\n\n### kafka a\n{ \"a\": 1 }\n\n#> result kafka a · T · 1 ms · simulated\n# vars: (none)\nsimulated send topic=a\n#< end\n\n### kafka b\n{ \"b\": 2 }\n", myFixture.editor.document.text)
    }

    fun testRunFromCaretRunsTheRest() {
        open("# @connect kafka l simulate=true\n\n### kafka a\n{ \"a\": 1 }\n\n### kafka b\n{ \"b\": 2 }\n\n### kafka c\n{ \"c\": 3 }\n")
        val d = myFixture.editor.document
        myFixture.editor.caretModel.moveToOffset(d.text.indexOf("{ \"b\""))
        myFixture.performEditorAction("com.neex.tellij.RunFromCaret")
        PlatformTestUtil.waitWithEventsDispatching("run", { d.getUserData(Keys.SESSION) == null }, 15)
        assertFalse(d.text.contains("topic=a"))
        assertTrue(d.text.contains("topic=b") && d.text.contains("topic=c"))
    }
}
