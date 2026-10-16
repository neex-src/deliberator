package com.neex.deliberator.audit

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.neex.deliberator.daemon.DeliberatorSettings
import com.neex.deliberator.run.RunSnapshot
import com.neex.deliberator.run.RunView
import com.neex.deliberator.run.ScnRunner
import com.neex.deliberator.scn.ScnRegistry
import com.neex.deliberator.testing.TempDaemon
import com.neex.deliberator.workspace.WorkspaceService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** The independent audit of M2, running from the editor: files that vanish, a daemon that goes away mid-run. `wait` steps only, no estate. */
class AuditRunTest : BasePlatformTestCase() {
    private lateinit var daemon: TempDaemon
    private lateinit var runner: ScnRunner
    private val scenarioPaths = ArrayList<String>()

    override fun setUp() {
        super.setUp()
        daemon = TempDaemon.create()
        DeliberatorSettings.getInstance().state.home = daemon.home.toString()
        val svc = WorkspaceService.getInstance(project)
        svc.loadState(WorkspaceService.State())
        svc.bind("w1")
        bg { svc.call("session.start", JsonObject().apply { addProperty("key", "AR-1") }) }
        runner = ScnRunner.getInstance(project)
    }

    override fun tearDown() {
        try {
            scenarioPaths.forEach { p -> if (runner.isRunning(p)) runner.cancel(p) }
            scenarioPaths.forEach { p -> val end = System.currentTimeMillis() + 15_000; while (runner.isRunning(p) && System.currentTimeMillis() < end) Thread.sleep(50) }
            ScnRegistry.getInstance().reset()
            DeliberatorSettings.getInstance().state.home = ""
            daemon.close()
        } finally {
            super.tearDown()
        }
    }

    private fun <T> bg(f: () -> T): T = ApplicationManager.getApplication().executeOnPooledThread<T> { f() }.get(120, TimeUnit.SECONDS)
    private fun wait(what: String, cond: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out: $what", cond, 120)

    private fun open(text: String, name: String = "a.scn"): Pair<VirtualFile, RunView> {
        val path = daemon.scenario(name, text)
        scenarioPaths += path
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)!!
        myFixture.openFileInEditor(vf)
        return vf to RunView.of(myFixture.editor)!!
    }

    private fun runAndWait(view: RunView, vf: VirtualFile): RunSnapshot {
        val before = runner.state(vf.path).snapshot
        view.runSteps(null)
        wait("run of ${vf.name} to end") { !runner.isRunning(vf.path) && runner.state(vf.path).snapshot.let { it != null && it !== before } }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return runner.state(vf.path).snapshot!!
    }

    fun testAResultTxtDeletedBetweenTheRunAndTheReopenGivesANamedProblemNotAnError() {
        val (vf, view) = open("### wait one\n# @for 100ms\n\n### wait two\n# @for 100ms\n")
        val snap = runAndWait(view, vf)
        Files.delete(Path.of(snap.resultPath!!))
        val editor = myFixture.editor
        RunView.detach(editor)
        runner.state(vf.path).snapshot = null
        val again = RunView.attach(editor, project, vf)
        wait("the latest run to be read again") { runner.state(vf.path).snapshot != null }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val s = runner.state(vf.path).snapshot!!
        assertEquals(snap.runId, s.runId)
        assertEquals("the daemon's run record still says how it ended", "ok", s.status)
        assertEquals("no result.txt for this run", s.problem)
        assertTrue(s.sections.isEmpty())
        val banner = again.inlayInfos().first { it.step == null }.lines.single()
        assertTrue(banner, banner.contains("no result.txt for this run"))
        assertTrue("no section is invented", again.inlayInfos().none { it.step != null })
        // and the scenario still runs again
        val second = runAndWait(again, vf)
        assertEquals(setOf(1, 2), second.sections.keys)
    }

    fun testAWholeRunDirectoryRemovedLeavesAnEditorWithoutResultsAndNoError() {
        val (vf, view) = open("### wait one\n# @for 100ms\n")
        val snap = runAndWait(view, vf)
        Path.of(snap.runDir).toFile().deleteRecursively()
        val editor = myFixture.editor
        RunView.detach(editor)
        runner.state(vf.path).snapshot = null
        val again = RunView.attach(editor, project, vf)
        Thread.sleep(1500)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertNull(runner.state(vf.path).snapshot)
        assertTrue(again.inlayInfos().isEmpty())
    }

    fun testADaemonStoppedByHandMidRunEndsTheRunStateAndTheEditorStaysUsable() {
        val (vf, view) = open("### wait long\n# @for 60s\n")
        val st = runner.state(vf.path)
        view.runSteps(null)
        wait("the step starts") { st.runId != null && st.live[1]?.status == "running" }
        bg { daemon.launcher.stopDaemon(daemon.home) }
        wait("the run state to end after the daemon went away") { !runner.isRunning(vf.path) }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue("the gutter is neutral again, not stuck on 'running'", view.gutterIcons().values.none { it.icon === com.intellij.icons.AllIcons.Actions.Suspend })
        // a new run starts a fresh daemon on the same home and works
        val tinyPath = daemon.scenario("tiny.scn", "### wait t\n# @for 50ms\n")
        scenarioPaths += tinyPath
        val tiny = LocalFileSystem.getInstance().refreshAndFindFileByPath(tinyPath)!!
        myFixture.openFileInEditor(tiny)
        val s = runAndWait(RunView.of(myFixture.editor)!!, tiny)
        assertEquals("ok", s.status)
    }

    /**
     * The daemon finalises the cancelled run on disk but the stream ends in `daemon/closed` (balloon only); the editor still ends up
     * showing that run, because an empty view reloads the latest run of the file from the daemon (which `ensure()` has restarted).
     */
    fun testADaemonStoppedMidRunLeavesTheCancelledRunVisibleInTheEditor() {
        val (vf, view) = open("### wait long\n# @for 60s\n")
        val st = runner.state(vf.path)
        view.runSteps(null)
        wait("the step starts") { st.runId != null && st.live[1]?.status == "running" }
        val runId = st.runId!!
        bg { daemon.launcher.stopDaemon(daemon.home) }
        wait("the run state to end") { !runner.isRunning(vf.path) }
        wait("the cancelled run to be read") { PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); runner.state(vf.path).snapshot != null }
        val onDisk = Files.walk(daemon.home).use { s -> s.filter { it.fileName.toString() == "result.txt" && it.toString().contains(runId) }.toList() }
        assertEquals("the daemon wrote the cancelled run: $onDisk", 1, onDisk.size)
        assertEquals("cancelled", runner.state(vf.path).snapshot!!.status)
        assertEquals(runId, runner.state(vf.path).snapshot!!.runId)
    }

    fun testAScenarioWhoseFileNameHasSpacesAndNonAsciiRunsFromTheEditor() {
        val (vf, view) = open("### wait one\n# @for 50ms\n", "my scénario ü.scn")
        assertTrue(vf.path, vf.path.contains("my scénario ü.scn"))
        val snap = runAndWait(view, vf)
        assertEquals("ok", snap.status)
        assertEquals(setOf(1), snap.sections.keys)
        assertEquals(snap.runId, bg { runner.latest(vf.path) }?.runId)
    }

    fun testRunningAnUnsavedInMemoryFileEndsInANotificationNotAHang() {
        val psi = myFixture.configureByText("mem.scn", "### wait one\n# @for 50ms\n")
        val vf = psi.virtualFile
        assertEquals("a light fixture file lives only in memory", "temp", vf.fileSystem.protocol)
        runner.run(vf, null)
        wait("the failed run to end") { !runner.isRunning(vf.path) && runner.state(vf.path).runId == null }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertNull(runner.state(vf.path).snapshot)
    }

    fun testClosingTheTempDaemonUnderARunningScenarioEndsTheRunWithoutAHang() {
        val (vf, view) = open("### wait long\n# @for 60s\n")
        val st = runner.state(vf.path)
        view.runSteps(null)
        wait("the step starts") { st.runId != null && st.live[1]?.status == "running" }
        val t0 = System.nanoTime()
        bg { daemon.close() }
        wait("the run state to end after TempDaemon.close()") { !runner.isRunning(vf.path) }
        println("AUDIT close() under a running editor run: ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertTrue(TempDaemon.survivors(daemon.home).isEmpty())
    }
}
