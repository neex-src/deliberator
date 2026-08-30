package com.neex.tellij.engine

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.neex.tellij.Keys
import com.neex.tellij.sources.kafka.KafkaSource
import com.neex.tellij.spi.SourceRegistry
import com.neex.tellij.spi.StepError

class RunSessionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        ExtensionTestUtil.maskExtensions(SourceRegistry.EP, listOf(FakeSource(), KafkaSource()), testRootDisposable)
    }

    private fun await(session: RunSession) {
        PlatformTestUtil.waitWithEventsDispatching("session did not finish", { session.finished }, 15)
    }

    fun testRunAllStreamsAndReportsFailure() {
        myFixture.configureByText("x.tellij", "# @var id = {{uuid}}\n# @connect fake f\n\n### fake one\nlines 3\n\n### fake two\n# @key {{id}}\nfail boom\n\n### fake three\nlines 1\n")
        val d = myFixture.editor.document
        val session = RunSession.start(project, d, { it.blocks }, freshVars = true)!!
        await(session)
        val text = d.text
        assertTrue(text, Regex("### fake one\nlines 3\n\n#> result fake one · [^\n]* · \\d+ ms · 3 lines\n# vars: \\(none\\)\none line 0\none line 1\none line 2\n#< end\n").containsMatchIn(text))
        assertTrue(text, Regex("#> result fake two · [^\n]* · ERROR\n# vars: id=[0-9a-f]{8}\nerror: boom\nhint: try harder\n#< end").containsMatchIn(text))
        assertTrue(text, text.contains("#> result fake three · ") && text.contains("skipped: earlier step failed"))
        assertNull(d.getUserData(Keys.SESSION))
        assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(d))
    }

    fun testRerunReplacesSectionAndKeepsVars() {
        myFixture.configureByText("x.tellij", "# @var id = {{uuid}}\n# @connect kafka l simulate=true\n\n### kafka orders\n# @key {{id}}\n{ \"id\": \"{{id}}\" }\n")
        val d = myFixture.editor.document
        val first = RunSession.start(project, d, { listOf(it.blocks[0]) }, freshVars = false)!!
        await(first)
        val id1 = Regex("# vars: id=([0-9a-f]{8})").find(d.text)!!.groupValues[1]
        assertTrue(d.text, d.text.contains("simulated send topic=orders partition=0 offset=0 key=$id1 bytes="))
        val second = RunSession.start(project, d, { listOf(it.blocks[0]) }, freshVars = false)!!
        await(second)
        assertEquals(1, Regex("#> result").findAll(d.text).count())
        assertTrue(d.text.contains("# vars: id=$id1"))
        val third = RunSession.start(project, d, { it.blocks }, freshVars = true)!!
        await(third)
        assertFalse("run-all takes a fresh snapshot", d.text.contains("# vars: id=$id1"))
    }

    fun testFailedAssertionMarksHeaderAndContinues() {
        myFixture.configureByText("x.tellij", "# @connect fake f\n\n### fake one\n# @assert n == 2\n# @assert s == x\nfacts n=1 s=x\n\n### fake two\nlines 1\n")
        val d = myFixture.editor.document
        val session = RunSession.start(project, d, { it.blocks }, freshVars = true)!!
        await(session)
        val text = d.text
        assertTrue(text, Regex("#> result fake one · [^\n]* · \\d+ ms · FAILED 1/2\n# vars: \\(none\\)\nfacts published\n✗ n == 2 \\(was 1\\)\n✓ s == x\n#< end").containsMatchIn(text))
        assertTrue(text, text.contains("#> result fake two · ") && text.contains("two line 0"))
        assertFalse(text.contains("ERROR"))
    }

    fun testCancelMarksStopped() {
        myFixture.configureByText("x.tellij", "# @connect fake f\n### fake t\ntail\n")
        val d = myFixture.editor.document
        val session = RunSession.start(project, d, { it.blocks }, freshVars = true)!!
        assertSame(session, RunSession.liveFor(d))
        PlatformTestUtil.waitWithEventsDispatching("no ticks", { d.text.contains("tick") }, 10)
        session.cancel()
        await(session)
        assertTrue(d.text, Regex("#> result fake t · [^\n]* · stopped\n").containsMatchIn(d.text))
        assertNull(RunSession.liveFor(d))
    }

    fun testSecondStartWhileRunningIsRefused() {
        myFixture.configureByText("x.tellij", "# @connect fake f\n### fake t\ntail\n")
        val d = myFixture.editor.document
        val session = RunSession.start(project, d, { it.blocks }, freshVars = true)!!
        try {
            RunSession.start(project, d, { it.blocks }, freshVars = true)
            fail()
        } catch (e: StepError) {
            assertEquals("a run is in progress", e.message)
        }
        session.cancel()
        await(session)
    }
}
