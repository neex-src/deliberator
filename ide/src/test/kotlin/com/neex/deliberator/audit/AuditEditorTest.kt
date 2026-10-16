package com.neex.deliberator.audit

import com.google.gson.JsonObject
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.daemon.DeliberatorSettings
import com.neex.deliberator.logs.FetchRequest
import com.neex.deliberator.logs.LogMatcher
import com.neex.deliberator.logs.LogService
import com.neex.deliberator.logs.MarkerService
import com.neex.deliberator.logs.ToggleMarkerAction
import com.neex.deliberator.scn.ScnRegistry
import com.neex.deliberator.testing.KnownBug
import com.neex.deliberator.testing.TempDaemon
import org.junit.experimental.categories.Category
import com.neex.deliberator.ui.DeliberatorPanel
import com.neex.deliberator.workspace.WorkspaceService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** The independent audit of M2: odd inputs at the editor/service level against the real daemon on a throwaway home. */
class AuditEditorTest : BasePlatformTestCase() {
    private lateinit var daemon: TempDaemon
    private lateinit var svc: WorkspaceService

    override fun setUp() {
        super.setUp()
        daemon = TempDaemon.create()
        DeliberatorSettings.getInstance().state.home = daemon.home.toString()
        ScnRegistry.getInstance().reset()
        svc = WorkspaceService.getInstance(project)
        svc.loadState(WorkspaceService.State())
        LogMatcher.getInstance(project).reset()
    }

    override fun tearDown() {
        try {
            ScnRegistry.getInstance().reset()
            DeliberatorSettings.getInstance().state.home = ""
            daemon.close()
        } finally {
            super.tearDown()
        }
    }

    private fun <T> bg(seconds: Long = 120, f: () -> T): T = ApplicationManager.getApplication().executeOnPooledThread<T> { f() }.get(seconds, TimeUnit.SECONDS)

    private fun online() {
        svc.bind("w1")
        bg { ScnRegistry.getInstance().refresh(); ScnRegistry.getInstance().refreshTargets(project) }
    }

    // --- a .scn of 5 000 steps ---------------------------------------------------------------------

    private fun bigScenario(steps: Int, broken: Boolean) = buildString {
        append("# @env local\n\n")
        for (i in 1..steps) {
            if (broken && i == steps) append("### nosuchkind last\n")
            else append("### wait s$i\n# @for 1ms\n")
            append("\n")
        }
    }

    fun testFiveThousandStepsAnnotateAndCompleteInReasonableTime() {
        online()
        val steps = 5000
        myFixture.configureByText("big.scn", bigScenario(steps, broken = true))
        val doc = myFixture.editor.document
        val t0 = System.nanoTime()
        val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
        val annotateMs = (System.nanoTime() - t0) / 1_000_000
        // the error of the last step lands on its own line, 3 lines per step + 2 header lines
        assertEquals(errors.toString(), 1, errors.size)
        assertEquals((steps - 1) * 3 + 2, doc.getLineNumber(errors[0].startOffset))

        // completion at the very end of the file
        myFixture.editor.caretModel.moveToOffset(doc.textLength)
        WriteActionRunner.insert(project, doc, "### ")
        myFixture.editor.caretModel.moveToOffset(doc.textLength)
        val t1 = System.nanoTime()
        val items = myFixture.completeBasic()
        val completeMs = (System.nanoTime() - t1) / 1_000_000
        val strings = items?.map { it.lookupString } ?: myFixture.lookupElementStrings.orEmpty()
        println("AUDIT-5000-STEPS: ${doc.textLength} chars; doHighlighting (incl. scenario.parse round trip) $annotateMs ms; completeBasic after '### ' at the end $completeMs ms; ${strings.size} items")
        assertTrue(strings.toString(), strings.containsAll(listOf("http", "sqs", "wait")))
        assertTrue("annotating 5000 steps took $annotateMs ms", annotateMs < 20_000)
        assertTrue("completing in a 5000-step file took $completeMs ms", completeMs < 10_000)
    }

    // --- the project's workspace binding edited by hand ------------------------------------------

    fun testAWorkspaceNamedByHandThatDoesNotExistIsShownAsMissingAndOnlyOpenWorkspaceCreatesIt() {
        svc.loadState(WorkspaceService.State().apply { ws = "no-such-ws" })
        val dir = daemon.home.resolve("workspaces").resolve("no-such-ws")
        val m = bg { svc.refreshNow() }
        assertEquals("no-such-ws", m.ws)
        assertTrue("the daemon does not know it: the model says so", m.missing)
        assertNull("not an error, a state of its own: ${m.error}", m.error)
        assertTrue(m.sessions.isEmpty() && m.envs.isEmpty())
        assertTrue(DeliberatorPanel.headerText(m), DeliberatorPanel.headerText(m).contains("not found"))
        // the tool window offers the way out, and refreshing (any number of times) creates nothing
        val panel = DeliberatorPanel(project).also { Disposer.register(testRootDisposable, it) }
        bg { svc.refreshNow() }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        panel.render(svc.model)
        assertTrue("Open Workspace is visible", panel.openButton.isVisible)
        assertFalse("a refresh must not materialise the workspace", Files.exists(dir))
        assertNull("no other op was asked", svc.model.currentSession)
        // Open Workspace is the one thing that creates it
        bg { svc.openWorkspace() }
        assertFalse(svc.model.missing)
        assertTrue(Files.isDirectory(dir.resolve("environments")))
        assertTrue(Files.isDirectory(dir.resolve("project")))
        panel.render(svc.model)
        assertFalse(panel.openButton.isVisible)
    }

    fun testAnInvalidWorkspaceNameByHandIsATypedErrorInTheModel() {
        for (bad in listOf("two words", "../evil", "a/b")) {
            svc.loadState(WorkspaceService.State().apply { ws = bad })
            val m = bg { svc.refreshNow() }
            assertNotNull("$bad: the model must say what is wrong", m.error)
            assertTrue("$bad: ${m.error}", m.error!!.contains("invalid name"))
            assertTrue(DeliberatorPanel.headerText(m).isNotBlank())
            val e = try { bg { svc.call("session.list") }; null } catch (x: Throwable) { generateSequence(x) { it.cause }.filterIsInstance<DaemonException>().firstOrNull() }
            assertEquals(bad, "store/invalid-name", e?.type)
        }
        assertFalse(Files.exists(daemon.home.resolve("evil")))
    }

    // --- a daemon of a newer version on the workspace's home ---------------------------------------

    fun testAProjectOnAHomeWithANewerDaemonShowsTheRefusalAndLeavesTheDaemonAlone() {
        val newer = TempDaemon.jarWithVersion("9.9.9", daemon.home)
        val running = TempDaemon.launcherFor(newer).connectCompatible(daemon.home)
        svc.bind("w1")
        val m = bg { svc.refreshNow() }
        assertNotNull("the model carries the refusal", m.error)
        assertTrue(m.error!!, m.error!!.contains("newer than this plugin"))
        assertTrue("the hint names the way out: ${m.error}", m.error!!.contains("dlb daemon stop"))
        assertTrue(ProcessHandle.of(running.hello.pid).get().isAlive)
        assertEquals("exactly one daemon, no restart attempt", 1, TempDaemon.survivors(daemon.home).size)
        assertEquals(0, daemon.log().count { it.contains("daemon.stop") })
    }

    // --- a marker on a line of a file outside every attached repository -----------------------------

    fun testAMarkerOutsideEveryAttachedRepositoryIsStoredAndMatched() {
        svc.bind("w1")
        val repo = daemon.home.resolve("repos").resolve("attached").also { Files.createDirectories(it) }
        bg { svc.call("repo.attach", JsonObject().apply { addProperty("path", repo.toString()) }) }
        val outside = daemon.home.resolve("elsewhere").resolve("Lonely.java").also { Files.createDirectories(it.parent) }
        Files.writeString(outside, "package x.y;\npublic class Lonely {\n  void f() {}\n}\n")
        myFixture.openFileInEditor(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(outside)!!)
        ToggleMarkerAction.toggle(project, myFixture.editor, 2)
        PlatformTestUtil.waitWithEventsDispatching("marker stored", { MarkerService.getInstance(project).markers.size == 1 }, 60)
        val m = MarkerService.getInstance(project).markers.single()
        assertEquals(outside.toString(), m.file)
        assertEquals("x.y.Lonely", m.logger)
        assertEquals(3, m.line)
        // and the daemon matches a log against it like any other marker
        val log = daemon.home.resolve("lonely.log")
        Files.writeString(log, "2026-10-16T10:00:00.000+02:00 INFO APP [c] x.y.Lonely:3 - hit\n2026-10-16T10:00:00.001+02:00 INFO APP [c] x.y.Lonely:4 - miss\n")
        bg { svc.call("session.start", JsonObject().apply { addProperty("key", "M-1") }) }
        val r = bg { svc.call("logs.match", JsonObject().apply { addProperty("path", log.toString()) }).resultObject() }
        assertEquals(1, r.getAsJsonArray("lines").size())
    }

    // --- logs.fetch of a 50 MB file opened in the editor --------------------------------------------

    fun testAFiftyMegabyteLogIsFetchedAndTheEditorEitherOpensItOrSaysSo() {
        svc.bind("w1")
        val src = daemon.home.resolve("huge.log")
        val line = "2026-10-16T10:00:00.123+02:00 INFO  APP [run1-001] com.example.demo.Inbox:6 - " + "x".repeat(60) + "\n"
        Files.newBufferedWriter(src).use { w -> repeat(((50L * 1024 * 1024) / line.length).toInt() + 1) { w.write(line) } }
        assertTrue(Files.size(src) >= 50L * 1024 * 1024)
        bg { svc.call("session.start", JsonObject().apply { addProperty("key", "BIG-1") }) }
        val t0 = System.nanoTime()
        val r = bg(300) { LogService.getInstance(project).fetchNow(FetchRequest(src.toString())) }
        val fetchMs = (System.nanoTime() - t0) / 1_000_000
        val path = Path.of(r.get("path").asString)
        assertEquals(Files.size(src), Files.size(path))
        assertTrue("the daemon wrote its own copy", path != src)
        val t1 = System.nanoTime()
        val ed = LogMatcher.getInstance(project).open(path.toString(), 1)
        val openMs = (System.nanoTime() - t1) / 1_000_000
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        println("AUDIT-50MB: fetch $fetchMs ms (${r.get("lines")} lines); open in editor $openMs ms -> ${if (ed == null) "NO EDITOR (file type ${vf.fileType.name}, length ${vf.length})" else "editor with ${ed.document.lineCount} lines"}")
        // either way the IDE must not hang or throw; the truncation is the known bug in testAFetchedHugeLogIsShownWhole below
        assertTrue("fetch took $fetchMs ms", fetchMs < 120_000)
        assertTrue("opening took $openMs ms", openMs < 60_000)
        // a marker match over the huge file stays the daemon's job and answers
        val t2 = System.nanoTime()
        val match = bg(300) { svc.call("logs.match", JsonObject().apply { addProperty("path", path.toString()) }).resultObject() }
        println("AUDIT-50MB: logs.match with no marker ${(System.nanoTime() - t2) / 1_000_000} ms, scanned ${match.get("scanned")}")
        FileDocumentManager.getInstance().saveAllDocuments()
    }

    /** F-big: the platform loads a file above its content limit truncated; the plugin neither says so nor keeps the highlights it cannot show. */
    @Category(KnownBug::class)
    fun testAFetchedHugeLogIsShownWholeOrTheTruncationIsReported() {
        svc.bind("w1")
        val src = daemon.home.resolve("huge2.log")
        val line = "2026-10-16T10:00:00.123+02:00 INFO  APP [run1-001] com.example.demo.Inbox:6 - " + "x".repeat(60) + "\n"
        val n = ((50L * 1024 * 1024) / line.length).toInt() + 1
        Files.newBufferedWriter(src).use { w -> repeat(n) { w.write(line) } }
        bg { svc.call("session.start", JsonObject().apply { addProperty("key", "BIG-2") }) }
        val r = bg(300) { LogService.getInstance(project).fetchNow(FetchRequest(src.toString())) }
        val ed = LogMatcher.getInstance(project).open(r.get("path").asString, 1)
        assertNotNull(ed)
        val vf0 = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(ed!!.document)!!
        println("EXPERIMENT: len=${vf0.length} textLength=${ed.document.textLength} lines=${ed.document.lineCount} LARGE=${com.intellij.openapi.util.io.FileUtilRt.LARGE_FOR_CONTENT_LOADING} tooLarge=${com.intellij.openapi.util.io.FileUtilRt.isTooLarge(vf0.length)} fileType=${vf0.fileType.name}")
        assertTrue("the editor shows ${ed!!.document.lineCount} of ${r.get("lines")} lines and the plugin does not say so", ed.document.lineCount >= r.get("lines").asInt)
    }
}

private object WriteActionRunner {
    fun insert(project: com.intellij.openapi.project.Project, doc: com.intellij.openapi.editor.Document, text: String) =
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { doc.insertString(doc.textLength, text) }
}
