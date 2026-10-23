package com.neex.deliberator.run

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.diff.contents.DocumentContent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.neex.deliberator.daemon.DaemonTerminal
import com.neex.deliberator.daemon.DeliberatorSettings
import com.neex.deliberator.testing.TempDaemon
import com.neex.deliberator.workspace.WorkspaceService
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.swing.KeyStroke

/**
 * Running from the editor against the real daemon (jar on a temp home): `wait` steps and `http` steps against a
 * local server, no estate. The scenario files are real files under the temp home (the daemon runs files; a light
 * fixture's in-memory file system is invisible to it).
 */
class RunFromEditorTest : BasePlatformTestCase() {
    private lateinit var daemon: TempDaemon
    private lateinit var server: HttpServer
    private lateinit var runner: ScnRunner
    private val base: String get() = "http://127.0.0.1:${server.address.port}"

    override fun setUp() {
        super.setUp()
        daemon = TempDaemon.create()
        DeliberatorSettings.getInstance().state.home = daemon.home.toString()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ok") { ex ->
            val b = "{\"ok\":true}".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.start()
        val svc = WorkspaceService.getInstance(project)
        svc.loadState(WorkspaceService.State())
        svc.bind("w1")
        bg { svc.call("session.start", JsonObject().apply { addProperty("key", "R-1") }) }
        runner = ScnRunner.getInstance(project)
    }

    override fun tearDown() {
        try {
            scenarioPaths.forEach { p -> if (runner.isRunning(p)) { runner.cancel(p) } }
            scenarioPaths.forEach { p -> val end = System.currentTimeMillis() + 15_000; while (runner.isRunning(p) && System.currentTimeMillis() < end) Thread.sleep(50) }
            server.stop(0)
            com.neex.deliberator.scn.ScnRegistry.getInstance().reset()
            DeliberatorSettings.getInstance().state.home = ""
            daemon.close()
        } finally {
            super.tearDown()
        }
    }

    private val scenarioPaths = ArrayList<String>()

    private fun <T> bg(f: () -> T): T = ApplicationManager.getApplication().executeOnPooledThread<T> { f() }.get(120, TimeUnit.SECONDS)

    private fun wait(what: String, cond: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out: $what", cond, 120)

    /** Four steps: wait, passing http, failing assert, wait. */
    private fun fourSteps() = """
        |### wait one
        |# @for 200ms
        |
        |### http ok
        |# @assert status == 200
        |GET $base/ok
        |
        |### http bad
        |# @assert status == 201
        |GET $base/ok
        |
        |### wait three
        |# @for 200ms
        |""".trimMargin()

    private fun open(text: String, name: String = "a.scn"): Pair<VirtualFile, RunView> {
        val path = daemon.scenario(name, text)
        scenarioPaths += path
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)!!
        myFixture.openFileInEditor(vf)
        val view = RunView.of(myFixture.editor)
        assertNotNull("the editor of a .scn file has a run view", view)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue() // the first paint of the view is a plain event, not part of creating the editor
        return vf to view!!
    }

    private fun runAndWait(view: RunView, vf: VirtualFile, steps: List<Int>?): RunSnapshot {
        val before = runner.state(vf.path).snapshot
        view.runSteps(steps)
        wait("run of ${vf.name} to end") { !runner.isRunning(vf.path) && runner.state(vf.path).snapshot.let { it != null && it !== before } }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return runner.state(vf.path).snapshot!!
    }

    private fun resultOf(s: RunSnapshot) = Files.readString(Path.of(s.resultPath!!))

    // ---------------------------------------------------------------------------------------------

    fun testRunAllShowsEverySectionOfResultTxtUnderItsStep() {
        val (vf, view) = open(fourSteps())
        assertEquals("a gutter icon on every step head", setOf(1, 2, 3, 4), view.gutterIcons().keys)
        val snap = runAndWait(view, vf, null)
        val file = resultOf(snap)
        assertEquals("failed", snap.status)
        assertEquals(setOf(1, 2, 3, 4), snap.sections.keys)
        val infos = view.inlayInfos().filter { it.step != null }
        assertEquals(listOf(1, 2, 3, 4), infos.map { it.step })
        for (i in infos) {
            assertTrue("section of step ${i.step} is a piece of result.txt:\n${i.section!!.text}", file.contains(i.section.text))
            assertEquals("collapsed to one line", 1, i.collapsed.size)
            assertEquals(i.section.summary(), i.collapsed.single())
        }
        val rebuilt = snap.sections.values.joinToString("\n\n") { it.text } + "\n\n" + snap.trailer.joinToString("\n") + "\n"
        assertEquals("sections and trailer are the whole file", file, rebuilt)
        val banner = view.inlayInfos().first { it.step == null }.lines.single()
        assertTrue(banner, banner.contains("RUN failed steps 4 failed 1"))
        assertEquals("the document was not written", fourSteps(), myFixture.editor.document.text)
        assertFalse("fresh view", view.stale)
        val icons = view.gutterIcons()
        assertSame(com.intellij.icons.AllIcons.RunConfigurations.TestPassed, icons.getValue(2).icon)
        assertSame(com.intellij.icons.AllIcons.RunConfigurations.TestFailed, icons.getValue(3).icon)
    }

    fun testAFailingAssertShowsTheCrossInline() {
        val (vf, view) = open(fourSteps())
        val snap = runAndWait(view, vf, null)
        val bad = view.inlayInfos().first { it.step == 3 }
        assertTrue(bad.collapsed.single(), bad.collapsed.single().startsWith("✗ failed"))
        assertTrue(bad.collapsed.single().contains("✗ status == 201"))
        assertTrue(bad.section!!.lines.any { it.startsWith("✗ status == 201 (was 200)") })
        assertTrue(resultOf(snap).contains("✗ status == 201 (was 200)"))
        assertTrue(snap.trailer.any { it.startsWith("FAILED 1/") })
    }

    fun testRunningOneStepSendsStepsAndShowsOnlyThatSection() {
        val (vf, view) = open(fourSteps())
        runAndWait(view, vf, null)
        val snap = runAndWait(view, vf, listOf(2))
        assertEquals(setOf(2), snap.sections.keys)
        assertEquals(listOf(2), view.inlayInfos().mapNotNull { it.step })
        assertEquals("ok", snap.sections.getValue(2).status)
        val shown = bg { WorkspaceService.getInstance(project).call("runs.show", JsonObject().apply { addProperty("run", snap.runDir) }).resultObject() }
        assertEquals(1, (shown.getAsJsonObject("run").get("steps") as JsonArray).size())
    }

    fun testRunFromHereRunsTheStepAndEveryStepAfterIt() {
        val (vf, view) = open(fourSteps())
        val snap = runAndWait(view, vf, view.run { (3..4).toList() })
        assertEquals(setOf(3, 4), snap.sections.keys)
        // the gutter menu builds exactly this from the step number
        assertEquals(listOf("Run This Step", "Run From Here", "Run All"), view.runActionsFor(3).map { it.templatePresentation.text })
    }

    fun testEscCancelsALongWaitAndTheInlineViewSaysCancelled() {
        val (vf, view) = open("### wait long\n# @for 60s\n\n### wait after\n# @for 1s\n")
        val st = runner.state(vf.path)
        view.runSteps(null)
        wait("the daemon names the run and the step starts") { st.runId != null && st.live[1]?.status == "running" }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue(runner.isRunning(vf.path))
        assertTrue("the banner says running", view.inlayInfos().any { it.step == null && it.lines.single().startsWith("▶ running") })
        assertEquals("running step", "running...".removeSuffix("...") , view.inlayInfos().first { it.step == 1 }.live!!.status)
        val icon = view.gutterIcons().getValue(1)
        assertSame(com.intellij.icons.AllIcons.Actions.Suspend, icon.icon)

        val esc = ActionManager.getInstance().getAction("deliberator.scenario.cancel")
        val ev = TestActionEvent.createTestEvent(esc, dataContext())
        esc.update(ev)
        assertTrue("Esc is ours while this file runs", ev.presentation.isEnabledAndVisible)
        esc.actionPerformed(ev)

        wait("the cancelled run to be read") { !runner.isRunning(vf.path) && runner.state(vf.path).snapshot?.status == "cancelled" }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val snap = runner.state(vf.path).snapshot!!
        assertEquals("cancelled", snap.status)
        val s1 = view.inlayInfos().first { it.step == 1 }
        assertTrue(s1.section!!.head, s1.section.head.endsWith("· cancelled") || s1.section.head.contains("· cancelled"))
        assertTrue(s1.section.lines.contains("cancelled"))
        assertTrue(s1.collapsed.single().contains("cancelled"))
        assertTrue(resultOf(snap).contains(s1.section.text))

        val ev2 = TestActionEvent.createTestEvent(esc, dataContext())
        esc.update(ev2)
        assertFalse("Esc is the stock Esc again once nothing runs", ev2.presentation.isEnabledAndVisible)
    }

    fun testTwoRunsAreInTheHistoryAndTheirDiffOpensTheNormalisedTexts() {
        val (vf, view) = open("### http ok\n# @assert status == 200\nGET $base/ok\n\n### wait w\n# @for 100ms\n")
        val first = runAndWait(view, vf, null)
        val second = runAndWait(view, vf, null)
        val rows = bg { runner.history(vf.path) }
        assertEquals(listOf(second.runId, first.runId), rows.map { it.runId })
        assertTrue(rows.all { it.path.isNotBlank() })

        val texts = bg { RunDiff.fetch(WorkspaceService.getInstance(project), rows[1], rows[0]) }
        assertTrue("two runs of the same scenario differ in nothing normalised", texts.equal)
        assertEquals(texts.a, texts.b)
        assertTrue(texts.a, texts.a.contains("RUN ok steps 2 failed 0"))
        assertTrue("elapsed is normalised", texts.a.contains("<ms> ms"))
        val req = RunDiff.request(project, texts)
        assertEquals(listOf(first.runId, second.runId), req.contentTitles)
        assertEquals(listOf(texts.a, texts.b), req.contents.map { (it as DocumentContent).document.text })
    }

    fun testADifferenceBetweenRunsShowsInTheTexts() {
        val (vf, view) = open("### wait w\n# @for 100ms\n")
        val first = runAndWait(view, vf, null)
        val second = runAndWait(view, vf, listOf(1)) // same; then change the scenario
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText("### wait w\n# @for 100ms\n# @assert elapsed >= 99999\n") }
        val third = runAndWait(view, vf, null)
        val rows = bg { runner.history(vf.path) }
        assertEquals(3, rows.size)
        val t = bg { RunDiff.fetch(WorkspaceService.getInstance(project), rows[1], rows[0]) }
        assertFalse(t.equal)
        assertTrue(t.b.contains("FAILED 1/1"))
        assertEquals(setOf(first.runId, second.runId, third.runId), rows.map { it.runId }.toSet())
    }

    fun testTheLatestRunsViewReappearsOnReopen() {
        val (vf, view) = open(fourSteps())
        val snap = runAndWait(view, vf, null)
        val editor = myFixture.editor
        RunView.detach(editor)
        runner.state(vf.path).snapshot = null
        val again = RunView.attach(editor, project, vf)
        wait("the latest run to be read from the run directory") { again.inlayInfos().count { it.step != null } == 4 }
        assertEquals(snap.runId, runner.state(vf.path).snapshot!!.runId)
        assertFalse("the file is unchanged since the run", again.stale)
        assertEquals(snap.sections.values.map { it.text }, again.inlayInfos().mapNotNull { it.section?.text })
    }

    fun testEditingAfterARunMarksTheViewStaleAndKeepsSectionsWhereTheyWere() {
        val (vf, view) = open(fourSteps())
        runAndWait(view, vf, null)
        val before = view.inlayInfos().map { it.section?.text }
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "# @var x = 1\n\n") }
        assertTrue(view.stale)
        wait("the gutter to go neutral after the edit") { view.gutterIcons().values.all { it.icon === com.intellij.icons.AllIcons.RunConfigurations.TestState.Run } }
        assertEquals("nothing is re-attached by index", before, view.inlayInfos().map { it.section?.text })
    }

    fun testAnUnsavedBufferIsSavedBeforeTheRunAndWhatRanIsWhatTheEditorHad() {
        val (vf, view) = open("### wait one\n# @for 100ms\n")
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.setText("### wait one\n# @for 100ms\n\n### wait two\n# @for 100ms\n")
        }
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(myFixture.editor.document))
        val snap = runAndWait(view, vf, null)
        assertEquals("the run saw the unsaved second step", setOf(1, 2), snap.sections.keys)
        assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(myFixture.editor.document))
        assertFalse("the run read the buffer that is shown", view.stale)
    }

    fun testConfirmRequiredAsksOnceAndRerunsThatStepWithTheToken() {
        val (vf, view) = open(fourSteps())
        val first = runAndWait(view, vf, null)
        // the error shape of daemon_test's confirm-required test
        val err = JsonObject().apply {
            addProperty("type", "confirm-required"); addProperty("message", "step 2 needs confirmation: SendMessage on dev")
            addProperty("token", "k9tok"); addProperty("summary", "SendMessage queue/inbox on dev (account 1234)"); addProperty("step", 2)
            add("confirmations", JsonArray().also { a -> a.add(JsonObject().apply { addProperty("step", 2); addProperty("token", "k9tok"); addProperty("summary", "SendMessage queue/inbox on dev (account 1234)"); addProperty("expires", "2026-10-16T10:20:00Z") }) })
            add("result", JsonObject().apply {
                addProperty("status", "failed")
                add("paths", JsonObject().apply { addProperty("run", first.runDir); addProperty("result", first.resultPath) })
            })
        }
        val terminal = DaemonTerminal(false, null, err, "{}")
        assertEquals(listOf(Confirmation(2, "k9tok", "SendMessage queue/inbox on dev (account 1234)")), Confirmation.parse(err))
        val rerun = Confirmation.parse(err).single().rerunArgs(vf.path, "dev")
        assertEquals("k9tok", rerun.get("confirm").asString)
        assertEquals("[2]", rerun.get("steps").toString())

        val asked = ArrayList<Confirmation>()
        runner.confirmer = { asked += it; true }
        val snap = bg { runner.afterTerminal(vf.path, terminal, emptyList()) }!!
        assertEquals("the dialog saw the summary once", 1, asked.size)
        assertTrue(asked.single().summary.contains("SendMessage"))
        assertEquals("the repeat ran that one step", setOf(2), snap.sections.keys)
        assertEquals(2, bg { runner.history(vf.path) }.size)

        runner.confirmer = { false }
        bg { runner.afterTerminal(vf.path, terminal, emptyList()) }
        assertEquals("declining runs nothing", 2, bg { runner.history(vf.path) }.size)
    }

    fun testActionsAndShortcutsAreRegistered() {
        val am = ActionManager.getInstance()
        for (id in listOf("runStep", "runFrom", "runAll", "cancel", "toggleResults", "runs")) assertNotNull(id, am.getAction("deliberator.scenario.$id"))
        val km = KeymapManager.getInstance().activeKeymap
        fun has(id: String, ks: String) = km.getShortcuts(id).any { it is KeyboardShortcut && it.firstKeyStroke == KeyStroke.getKeyStroke(ks) }
        assertTrue(has("deliberator.scenario.runStep", "control ENTER"))
        assertTrue(has("deliberator.scenario.cancel", "ESCAPE"))
        val (_, view) = open(fourSteps())
        val ev = TestActionEvent.createTestEvent(am.getAction("deliberator.scenario.cancel"), dataContext())
        am.getAction("deliberator.scenario.cancel").update(ev)
        assertFalse("Esc does nothing while nothing runs", ev.presentation.isEnabledAndVisible)
        val run = TestActionEvent.createTestEvent(am.getAction("deliberator.scenario.runStep"), dataContext())
        am.getAction("deliberator.scenario.runStep").update(run)
        assertTrue(run.presentation.isEnabledAndVisible)
        assertNotNull(view)
    }

    fun testCtrlEnterRunsTheStepAtTheCaret() {
        val (vf, view) = open(fourSteps())
        val text = myFixture.editor.document.text
        myFixture.editor.caretModel.moveToOffset(text.indexOf("GET $base/ok") + 2) // inside step 2
        val before = runner.state(vf.path).snapshot
        val act = ActionManager.getInstance().getAction("deliberator.scenario.runStep")
        act.actionPerformed(TestActionEvent.createTestEvent(act, dataContext()))
        wait("run") { !runner.isRunning(vf.path) && runner.state(vf.path).snapshot.let { it != null && it !== before } }
        assertEquals(setOf(2), runner.state(vf.path).snapshot!!.sections.keys)
        assertNotNull(view)
    }

    /** 06b: during the run the view takes the finished steps' sections from the progressive result.txt, not from the events. */
    fun testTheViewFollowsTheProgressiveResultTxtDuringTheRun() {
        val (vf, view) = open("### wait first\n# @for 100ms\n\n### wait second\n# @for 60s\n")
        val st = runner.state(vf.path)
        view.runSteps(null)
        wait("step 1's section from the file while step 2 runs") {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            st.progress?.sections?.containsKey(1) == true && view.inlayInfos().any { it.step == 1 && it.section != null }
        }
        assertTrue("still running", runner.isRunning(vf.path))
        val prog = st.progress!!
        val onDisk = Files.readString(Path.of(prog.resultPath!!))
        assertTrue("the section is a piece of the file as it is now:\n$onDisk", onDisk.contains(view.inlayInfos().first { it.step == 1 }.section!!.text))
        assertTrue(onDisk, onDisk.trimEnd().endsWith("RUN running steps 1 failed 0"))
        assertTrue(view.bannerText!!, view.bannerText!!.contains("RUN running steps 1 failed 0"))
        assertNull("step 2 has no section yet, only the live line", view.inlayInfos().first { it.step == 2 }.section)
        assertEquals("running", view.inlayInfos().first { it.step == 2 }.live?.status)
        runner.cancel(vf.path)
        wait("the cancelled run to end") { !runner.isRunning(vf.path) && st.snapshot != null }
        assertNull("the progress is dropped when the run ends", st.progress)
        assertEquals("cancelled", st.snapshot!!.status)
    }

    /** F12: the history popup's actions, driven without a screen. */
    fun testTheHistoryMenuOpensInlineAndDiffsWithThePreviousRun() {
        val (vf, view) = open("### wait one\n# @for 50ms\n")
        val first = runAndWait(view, vf, null)
        val second = runAndWait(view, vf, null)
        val rows = bg { runner.history(vf.path) }
        assertEquals(listOf(second.runId, first.runId), rows.map { it.runId })
        assertNull("the oldest run has no earlier one", RunHistory.olderThan(rows[1], rows))
        assertEquals(rows[1], RunHistory.olderThan(rows[0], rows))
        assertEquals("older on the left", rows[1] to rows[0], RunHistory.diffOrder(rows[0], rows[1], rows))
        assertEquals("older on the left, either way round", rows[1] to rows[0], RunHistory.diffOrder(rows[1], rows[0], rows))

        RunHistory.perform(project, vf.path, rows[1], rows, "Show inline")
        wait("the older run shown inline") { runner.state(vf.path).snapshot?.runId == first.runId }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(first.runId, runner.state(vf.path).snapshot!!.runId)

        var texts: DiffTexts? = null
        RunHistory.perform(project, vf.path, rows[0], rows, "Diff with previous run", showDiff = { _, t -> texts = t })
        wait("the diff texts") { PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); texts != null }
        assertEquals(first.runId, texts!!.aRun)
        assertEquals(second.runId, texts!!.bRun)
        assertTrue(texts!!.a.isNotEmpty() && texts!!.b.isNotEmpty())

        var none: DiffTexts? = null
        RunHistory.perform(project, vf.path, rows[1], rows, "Diff with previous run", showDiff = { _, t -> none = t })
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertNull("nothing to diff against", none)

        RunHistory.perform(project, vf.path, rows[0], rows, "Open result.txt")
        wait("result.txt opened") { PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor?.virtualFile?.name == "result.txt" }
    }

    private fun dataContext() = SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project)
        .add(CommonDataKeys.EDITOR, myFixture.editor)
        .add(CommonDataKeys.PSI_FILE, PsiManager.getInstance(project).findFile(FileDocumentManager.getInstance().getFile(myFixture.editor.document)!!))
        .build()
}
