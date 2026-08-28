package com.neex.tellij.engine

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.neex.tellij.model.WorkbenchParser

class ResultSectionTest : BasePlatformTestCase() {

    private fun doc(text: String) = myFixture.configureByText("x.tellij", text).let { myFixture.editor.document }

    private fun <T> write(body: () -> T): T = WriteCommandAction.writeCommandAction(project).compute<T, RuntimeException>(body)

    fun testInsertAfterBodyAndReplaceOnRerun() {
        val d = doc("### kafka a\n# @key k\nbody\n\n### kafka b\nb\n")
        val block = WorkbenchParser.parse(d.immutableCharSequence).blocks[0]
        val range = write { ResultWriter.write(d, block, "#> result kafka a · t\n# vars: (none)\n#< end") }
        assertEquals("### kafka a\n# @key k\nbody\n\n#> result kafka a · t\n# vars: (none)\n#< end\n\n### kafka b\nb\n", d.text)
        assertEquals("#> result kafka a · t\n# vars: (none)\n#< end", range.substring(d.immutableCharSequence))

        val again = WorkbenchParser.parse(d.immutableCharSequence).blocks[0]
        write { ResultWriter.write(d, again, "#> result kafka a · t2\n#< end") }
        assertEquals("### kafka a\n# @key k\nbody\n\n#> result kafka a · t2\n#< end\n\n### kafka b\nb\n", d.text)
    }

    fun testInsertAfterDirectivesWhenNoBody() {
        val d = doc("### logs a\n# @for 10s\n\n### kafka b\nb\n")
        val block = WorkbenchParser.parse(d.immutableCharSequence).blocks[0]
        write { ResultWriter.write(d, block, "#> result logs a\n#< end") }
        assertEquals("### logs a\n# @for 10s\n\n#> result logs a\n#< end\n\n### kafka b\nb\n", d.text)
    }

    fun testRemoveAll() {
        val d = doc("### kafka a\nbody\n\n#> result kafka a\nx\n#< end\n\n### kafka b\nb\n\n#> result kafka b\n#< end\n")
        val n = WriteCommandAction.writeCommandAction(project).compute<Int, RuntimeException> { ResultWriter.removeAll(d) }
        assertEquals(2, n)
        assertEquals("### kafka a\nbody\n\n### kafka b\nb\n", d.text)
    }

    fun testStreamingAppendsAndHeaderRewrite() {
        val d = doc("### kafka a\nbody\n")
        val block = WorkbenchParser.parse(d.immutableCharSequence).blocks[0]
        val section = ResultWriter.initialSection(block, mapOf("id" to "1"), "T")
        val range = write { ResultWriter.write(d, block, section) }
        val sink = DocumentResultSink(testRootDisposable, d, block, range, mapOf("id" to "1"), 3)
        sink.started()
        sink.appendLine("one")
        sink.appendLine("two")
        sink.flushNow()
        assertEquals("### kafka a\nbody\n\n#> result kafka a · T · running\n# vars: id=1\none\ntwo\n#< end\n", d.text)
        sink.appendLine("three")
        sink.appendLine("four (dropped)")
        sink.done("sent")
        sink.flushNow()
        val text = d.text
        assertTrue(text, text.endsWith("\none\ntwo\nthree\n… 1 more lines (limit 3, # @limit N to raise)\n#< end\n"))
        assertTrue(text, Regex("#> result kafka a · \\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d · \\d+ ms · sent\n").containsMatchIn(text))
        assertTrue(sink.isTerminated)
    }

    fun testUserEditsAboveDoNotBreakStreaming() {
        val d = doc("### kafka a\nbody\n")
        val block = WorkbenchParser.parse(d.immutableCharSequence).blocks[0]
        val range = write { ResultWriter.write(d, block, ResultWriter.initialSection(block, emptyMap(), "T")) }
        val sink = DocumentResultSink(testRootDisposable, d, block, range, emptyMap(), 100)
        write { d.insertString(0, "# a comment on top\n") }
        sink.appendLine("late")
        sink.flushNow()
        assertTrue(d.text, d.text.endsWith("# vars: (none)\nlate\n#< end\n"))
    }

    fun testDeletedSectionIsRecreated() {
        val d = doc("### kafka a\nbody\n")
        val block = WorkbenchParser.parse(d.immutableCharSequence).blocks[0]
        val range = write { ResultWriter.write(d, block, ResultWriter.initialSection(block, emptyMap(), "T")) }
        val sink = DocumentResultSink(testRootDisposable, d, block, range, emptyMap(), 100)
        write { d.deleteString(range.start - 2, d.textLength) }
        assertEquals("### kafka a\nbody", d.text)
        sink.appendLine("back")
        sink.done()
        sink.flushNow()
        assertTrue(d.text, d.text.contains("#> result kafka a") && d.text.endsWith("\nback\n#< end"))
        assertFalse(sink.orphaned)
    }

    fun testGoneBlockMakesSinkSilent() {
        val d = doc("### kafka a\nbody\n")
        val block = WorkbenchParser.parse(d.immutableCharSequence).blocks[0]
        val range = write { ResultWriter.write(d, block, ResultWriter.initialSection(block, emptyMap(), "T")) }
        val sink = DocumentResultSink(testRootDisposable, d, block, range, emptyMap(), 100)
        write { d.setText("# nothing here\n") }
        sink.appendLine("lost")
        sink.done()
        sink.flushNow()
        assertEquals("# nothing here\n", d.text)
        assertTrue(sink.orphaned)
    }
}
