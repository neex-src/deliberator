package com.neex.deliberator.ci

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.neex.deliberator.daemon.DeliberatorSettings
import com.neex.deliberator.testing.TempDaemon
import com.neex.deliberator.workspace.WorkspaceService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Piece 03 in the IDE, against the real daemon and the real `act` on the sandbox's container engine (the test returns, saying so, when act is not there): the workflow
 * node of a checkout is in the tree before any run, a run started from it appears on top of its workflow as `local #n` with its steps moving and ending, a step's
 * log opens, a cancelled run ends cancelled and leaves no container.
 */
class CiLocalTest : BasePlatformTestCase() {
    private lateinit var daemon: TempDaemon
    private lateinit var ops: CiOps
    private lateinit var proj: Path

    private val workflow = """name: fixture
on:
  workflow_dispatch:
    inputs:
      code: {description: exit code, default: "0"}
jobs:
  say:
    runs-on: ubuntu-latest
    steps:
      - name: First
        run: echo first-step-output
      - name: Pause
        run: sleep 4
      - name: Second
        run: echo second-step-output
  slow:
    runs-on: ubuntu-latest
    steps:
      - run: echo sleeping now; sleep 300
"""

    private fun sh(dir: Path, vararg a: String) {
        val p = ProcessBuilder(*a).directory(dir.toFile()).redirectErrorStream(true).start()
        check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) { a.joinToString(" ") + ": " + String(p.inputStream.readAllBytes()) }
    }

    private fun actThere(): Boolean = try {
        val p = ProcessBuilder("act", "--version").redirectErrorStream(true).start()
        p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (e: Exception) { false }

    override fun setUp() {
        super.setUp()
        daemon = TempDaemon.create()
        DeliberatorSettings.getInstance().state.home = daemon.home.toString()
        val svc = WorkspaceService.getInstance(project)
        svc.loadState(WorkspaceService.State())
        daemon.openWorkspace("w7")
        svc.bind("w7")
        bg { svc.startSession("S1", null) }
        proj = Files.createTempDirectory(daemon.home, "proj")
        Files.createDirectories(proj.resolve(".github/workflows"))
        Files.writeString(proj.resolve(".github/workflows/fixture.yml"), workflow)
        sh(proj, "git", "init", "-q", "-b", "main"); sh(proj, "git", "add", "."); sh(proj, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "fixture")
        bg { svc.call("repo.attach", com.google.gson.JsonObject().apply { addProperty("path", proj.toString()) }) }
        ops = CiOps.getInstance(project)
    }

    override fun tearDown() {
        try {
            DeliberatorSettings.getInstance().state.home = ""
            daemon.close()
        } finally {
            super.tearDown()
        }
    }

    private fun <T> bg(f: () -> T): T = ApplicationManager.getApplication().executeOnPooledThread<T> { f() }.get(180, TimeUnit.SECONDS)
    private fun wait(what: String, secs: Int = 120, cond: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out: $what", cond, secs)
    private fun repoName() = proj.fileName.toString()

    fun testTheWorkflowNodeIsThereBeforeAnyRunAndItsInputsAreKnown() {
        val wfs = bg { ops.workflows(repoName()) }
        assertEquals(listOf("fixture"), wfs.map { it.name })
        assertEquals(listOf("workflow_dispatch"), wfs[0].events)
        assertEquals(listOf("code"), wfs[0].inputs.map { it.name })
        assertEquals("0", wfs[0].inputs[0].default)
        assertEquals(listOf("say", "slow"), wfs[0].jobs.map { it.id })
        val panel = CiPanel(project)
        try {
            panel.addRepo(repoName())
            wait("the workflow node is shown") { panel.lines().any { it.trim() == "fixture (0)" } }
            val sel = CiWorkflowSelection(repoName(), "fixture", wfs[0])
            assertEquals(".github/workflows/fixture.yml", sel.workflow!!.file)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
            com.intellij.ide.util.PropertiesComponent.getInstance(project).unsetValue(CiPanel.KEY)
        }
    }

    fun testALocalRunAppearsOnTopOfItsWorkflowWithItsStepsMovingAndItsStepLogOpens() {
        if (!actThere()) { println("SKIP ci-local ide: act is not runnable here"); return }
        val panel = CiPanel(project)
        try {
            panel.addRepo(repoName())
            wait("the workflow node is shown") { panel.lines().any { it.trim() == "fixture (0)" } }
            val h = bg { ops.local(repoName(), ".github/workflows/fixture.yml", "say") }
            h.onEvent = { n, j -> if (n == "ci.run" && j.get("status")?.asString == "in_progress") ApplicationManager.getApplication().invokeLater { panel.reveal(repoName(), j.get("run").asString) } }
            h.begin()
            wait("the run is in the tree, running") { panel.lines().any { it.trim().startsWith("local #1  in_progress") } }
            val before = panel.expandedKeys()
            wait("its steps move: Pause is running") { panel.tick(true); panel.lines().any { it.trim().startsWith("3  Pause  in_progress") } }
            assertTrue(panel.lines().toString(), panel.lines().any { it.trim().startsWith("2  First  success") })
            wait("the run ends green") { panel.tick(true); panel.load(repoName(), false); h.isProcessTerminated && panel.lines().any { it.trim().startsWith("local #1  success") } }
            assertEquals(0, h.exit)
            assertTrue("nothing folded: ${panel.expandedKeys()}", panel.expandedKeys().containsAll(before))
            val steps = panel.lines().map { it.trim() }
            assertTrue(steps.toString(), steps.any { it.contains("First  success") })
            val log = bg { ops.log(repoName(), "local-1", "say", "First") }
            assertTrue(Files.readString(Path.of(log.path)).contains("first-step-output"))
            assertFalse("only that step's lines", Files.readString(Path.of(log.path)).contains("second-step-output"))
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
            com.intellij.ide.util.PropertiesComponent.getInstance(project).unsetValue(CiPanel.KEY)
        }
    }

    fun testCancellingALocalRunStopsItAndItEndsCancelled() {
        if (!actThere()) { println("SKIP ci-local ide: act is not runnable here"); return }
        val panel = CiPanel(project)
        try {
            panel.addRepo(repoName())
            wait("the workflow node is shown") { panel.lines().any { it.trim() == "fixture (0)" } }
            val h = bg { ops.local(repoName(), ".github/workflows/fixture.yml", "slow", "workflow_dispatch") }
            CiLocal.wire(project, repoName(), h)
            h.begin()
            try { wait("the run is running and cancellable from the tree", 60) { CiLocal.cancellable(repoName(), "local-1") } } catch (e: AssertionError) { throw AssertionError(e.message + "\n" + h.transcript(), e) }
            wait("the row is there") { panel.load(repoName(), false); panel.lines().any { it.trim().startsWith("local #1  in_progress") } }
            CiLocal.cancel(repoName(), "local-1")
            wait("the run ends cancelled") { panel.load(repoName(), false); panel.lines().any { it.trim().startsWith("local #1  cancelled") } }
            assertFalse(CiLocal.cancellable(repoName(), "local-1"))
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
            com.intellij.ide.util.PropertiesComponent.getInstance(project).unsetValue(CiPanel.KEY)
        }
    }
}
