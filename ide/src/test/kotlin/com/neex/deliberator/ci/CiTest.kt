package com.neex.deliberator.ci

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.daemon.DeliberatorSettings
import com.neex.deliberator.testing.GithubStandIn
import com.neex.deliberator.testing.TempDaemon
import com.neex.deliberator.workspace.WorkspaceService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * M5.2/M5.3 against the real daemon on a temp home and the local GitHub stand-in (`github.edn :api`): runs per workflow as text, the default action of each kind of
 * run (a red one is the diff against the last green one, a running one is followed, a green one asks for its job), job logs, a followed job's console, and the tree.
 */
class CiTest : BasePlatformTestCase() {
    private lateinit var daemon: TempDaemon
    private lateinit var github: GithubStandIn
    private lateinit var ops: CiOps
    private val repo = "acme/widgets"

    override fun setUp() {
        super.setUp()
        daemon = TempDaemon.create()
        DeliberatorSettings.getInstance().state.home = daemon.home.toString()
        val svc = WorkspaceService.getInstance(project)
        svc.loadState(WorkspaceService.State())
        daemon.openWorkspace("w9")
        svc.bind("w9")
        github = GithubStandIn()
        github.point(Path.of(bg { svc.call("workspace.show").resultObject().get("path").asString }))
        bg { svc.startSession("S1", null) }
        ops = CiOps.getInstance(project)
    }

    override fun tearDown() {
        try {
            github.close()
            DeliberatorSettings.getInstance().state.home = ""
            daemon.close()
        } finally {
            super.tearDown()
        }
    }

    private fun <T> bg(f: () -> T): T = ApplicationManager.getApplication().executeOnPooledThread<T> { f() }.get(180, TimeUnit.SECONDS)
    private fun wait(what: String, secs: Int = 60, cond: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out: $what", cond, secs)
    private fun waitTree(panel: CiPanel, what: String, cond: () -> Boolean) = try { wait(what, 60, cond) } catch (e: AssertionError) { throw AssertionError(e.message + "\n" + panel.lines().joinToString("\n"), e) }
    /** The platform logs an exception of a pooled task as an error (which fails the task); the daemon's own is in its cause chain. */
    private fun typed(f: () -> Any?): DaemonException? = try { bg(f); null } catch (x: Throwable) {
        generateSequence(x) { it.cause }.filterIsInstance<DaemonException>().firstOrNull() ?: throw x
    }

    fun testActionsAreRegistered() {
        for (id in listOf("deliberator.ci.open", "deliberator.ci.jobLog", "deliberator.ci.diff", "deliberator.ci.follow", "deliberator.ci.runsText", "deliberator.ci.refresh",
            "deliberator.ci.addRepo", "deliberator.ci.removeRepo", "deliberator.ci.local")) assertNotNull(id, ActionManager.getInstance().getAction(id))
        assertNotNull(ActionManager.getInstance().getAction("deliberator.ci"))
    }

    fun testRunsComeAsRowsAndAsATextFileNewestFirst() {
        val r = bg { ops.runs(repo) }
        assertEquals(listOf("210", "200", "195", "190"), r.rows.map { it.id })
        assertEquals(setOf("ci"), r.byWorkflow().keys)
        assertEquals(listOf("in_progress", "failure", "success", "success"), r.rows.map { it.result })
        val text = Files.readString(Path.of(r.path!!))
        assertTrue(text, Regex("(?m)^200\\s+#20\\s+failure\\s+feat").containsMatchIn(text))
        assertTrue(r.path!!, "/ci/runs-" in r.path!!)
        assertEquals(r.rows.size, bg { ops.runs(repo, "ci", limit = 2) }.total.coerceAtMost(r.rows.size))
    }

    fun testARedRunOpensTheDiffAgainstTheLastGreenRun() {
        val run = bg { ops.runs(repo) }.rows.first { it.id == "200" }
        val o = bg { ops.openDefault(repo, run) } as CiOpen.Text
        assertTrue(o.path, o.path.endsWith(".diff"))
        assertTrue(o.what, o.what.contains("green run 195"))
        val diff = Files.readString(Path.of(o.path))
        assertTrue(diff, Regex("(?m)^\\+##\\[error\\]").containsMatchIn(diff))
        assertFalse("no raw runner timestamps", Regex("(?m)^[ +-]\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{7}Z").containsMatchIn(diff))
        assertTrue(o.note!!, o.note!!.contains("lines only in the red run"))
    }

    fun testAGreenRunAsksForItsJobAndALoneJobOpensItsLog() {
        val rows = bg { ops.runs(repo) }.rows
        val two = bg { ops.openDefault(repo, rows.first { it.id == "195" }) } as CiOpen.Choose
        assertEquals(listOf("test (ubuntu)", "lint"), two.jobs)
        assertEquals(CiDefault.LOG, two.then)
        val log = bg { ops.openJob(repo, "195", "lint") } as CiOpen.Text
        assertTrue(log.path.endsWith(".log"))
        val text = Files.readString(Path.of(log.path))
        assertTrue(text, text.contains("12 tests, 0 failures"))
        assertFalse("CRLF and BOM are gone", text.contains("\r"))
        // main's run has one job: no question
        val one = bg { ops.openDefault(repo, rows.first { it.id == "190" }) } as CiOpen.Text
        assertTrue(one.what, one.what.contains("test (ubuntu)"))
        assertEquals(listOf("test (ubuntu)"), bg { ops.jobNames(repo, "190") })
    }

    fun testARunningRunIsFollowedAndItsJobHasNoLogYet() {
        val run = bg { ops.runs(repo) }.rows.first { it.id == "210" }
        assertEquals(CiOpen.Follow(repo, "210", "test (ubuntu)"), bg { ops.openDefault(repo, run) })
        // the log of a job that has not completed is a typed refusal, which the view turns into a follow
        assertEquals("ci/log-not-available", typed { ops.log(repo, "210", "test (ubuntu)") }!!.type)
        assertEquals(CiOpen.Follow(repo, "210", "test (ubuntu)"), bg { ops.openJob(repo, "210", "test (ubuntu)") })
    }

    fun testFollowingAJobStreamsItsLinesIntoTheConsoleAndEndsWithTheFile() {
        github.runningPolls = 2
        val h = bg { ops.tail(repo, "210", "test (ubuntu)") }
        h.begin()
        wait("the follow ends") { h.isProcessTerminated }
        val t = h.transcript()
        assertEquals(t, 0, h.exit)
        assertTrue(t, t.contains("> ci.tail"))
        assertTrue(t, t.contains("[deliberator] step 2"))            // the daemon's step transitions while it runs
        assertTrue(t, t.contains("12 tests, 0 failures"))              // then the job's own log
        val path = Regex("-- text: (\\S+)").find(t)!!.groupValues[1]
        assertTrue(Files.readString(Path.of(path)).contains("12 tests, 0 failures"))
        assertTrue(t, t.contains("-- followed test (ubuntu) of run 210"))
        // the printed path is a link to the file (the console's filter)
        val line = t.lines().first { it.startsWith("-- text: ") }
        val hit = bg { DaemonPathFilter(project).applyFilter(line, line.length) }
        assertNotNull(line, hit)
        assertEquals(path, line.substring(hit!!.resultItems[0].highlightStartOffset, hit.resultItems[0].highlightEndOffset))
    }

    fun testAnUnknownRepositoryOrRunIsATypedErrorNotACrash() {
        assertEquals("ci/not-found", typed { ops.log(repo, "1") }!!.type)
        assertNotNull(typed { ops.runs("not a repo") })
    }

    fun testTheTreeShowsTheRunsPerWorkflowAndKeepsTheRepositoriesItWasGiven() {
        val panel = CiPanel(project)
        try {
            panel.addRepo(repo)
            wait("the runs are shown") { panel.lines().any { it.contains("#20") } }
            assertEquals(listOf(repo, "  ci (4)"), panel.lines().take(2))
            assertTrue(panel.lines().toString(), panel.lines().any { it.trim().startsWith("#20  failure  push feat") })
            assertTrue(panel.statusText(), panel.statusText().startsWith("$repo: 4 of 4 runs"))
            assertEquals(listOf(repo), panel.typedRepos())
            // a repository that cannot be read shows why, in the tree
            panel.addRepo("nobody/nothing")
            wait("the error is shown") { panel.lines().indexOf("nobody/nothing").let { i -> i >= 0 && panel.lines().getOrNull(i + 1)?.contains("expand to load") == false } }
            panel.removeRepo("nobody/nothing")
            assertEquals(listOf(repo), panel.typedRepos())
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
            com.intellij.ide.util.PropertiesComponent.getInstance(project).unsetValue(CiPanel.KEY)
        }
    }

    fun testJobsAreStepsWithDurationsAndAFinishedRunIsAnsweredFromTheKeptCopy() {
        val d = bg { ops.jobs(repo, "200") }
        assertEquals("fetched", d.source)
        assertTrue(d.final)
        assertEquals(listOf("test (ubuntu)", "lint"), d.jobs.map { it.name })
        val steps = d.jobs[0].steps
        assertEquals(listOf("Set up job", "make test"), steps.map { it.name })
        assertEquals(2L, steps[0].durationS)
        assertEquals(5L, steps[1].durationS)
        assertEquals(CiState.RED, steps[1].state)
        assertEquals(CiState.RED, d.jobs[0].state)
        assertEquals("push", d.run.event)
        val before = github.requests.size
        val again = bg { ops.jobs(repo, "200") }
        assertEquals("kept", again.source)
        assertNotNull(again.keptAt)
        assertEquals(before, github.requests.size)
        assertTrue(d.path!!, "/ci/jobs-" in d.path!!)
    }

    fun testAStepLogIsThatStepsLinesOnlyAndTheWholeJobLogStaysAsItIs() {
        val step = bg { ops.log(repo, "200", "2001", "2") }
        val text = Files.readString(Path.of(step.path))
        assertTrue(step.what, step.what.contains("step 2 make test"))
        assertTrue(text, text.contains("FAIL in (match-test)"))
        assertFalse("the setup lines are not in the step", text.contains("Current runner version"))
        val job = Files.readString(Path.of(bg { ops.log(repo, "200", "2001") }.path))
        assertTrue(job.contains("Current runner version") && job.contains("FAIL in (match-test)"))
        assertEquals("ci/no-step", typed { ops.log(repo, "200", "2001", "9") }!!.type)
    }

    fun testARedRunOpensDownToItsFirstFailedStepAndARefreshKeepsExpansionAndSelection() {
        val panel = CiPanel(project)
        try {
            panel.addRepo(repo)
            wait("the runs are shown") { panel.lines().any { it.contains("#20") } }
            panel.expandRun(repo, "200")
            waitTree(panel, "the jobs are shown") { panel.lines().any { it.trim().startsWith("2  make test  failure") } }
            val lines = panel.lines()
            assertTrue(lines.toString(), lines.any { it.trim() == "test (ubuntu)  failure  9m 00s" })
            assertTrue(panel.expandedKeys().toString(), panel.expandedKeys().any { it.endsWith("u:200/j:2001") })
            assertTrue("the first failed step is visible", panel.isVisible(repo, "200", "s:2"))
            panel.select(repo, "200", "s:2")
            val sel = panel.selection()!!
            assertEquals("make test", sel.step!!.name)
            assertEquals("test (ubuntu)", sel.job!!.name)
            val expanded = panel.expandedKeys()
            panel.load(repo)
            waitTree(panel, "the refresh is done") { !panel.busy() }
            assertEquals(expanded, panel.expandedKeys())
            assertEquals("make test", panel.selection()!!.step!!.name)
            assertTrue(panel.statusText(), panel.statusText().contains("kept"))
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
            com.intellij.ide.util.PropertiesComponent.getInstance(project).unsetValue(CiPanel.KEY)
        }
    }

    fun testARunningRunMovesToDoneInPlaceWithoutFoldingTheTree() {
        val panel = CiPanel(project)
        try {
            panel.addRepo(repo)
            wait("the runs are shown") { panel.lines().any { it.contains("#21") } }
            panel.expandRun(repo, "210")
            waitTree(panel, "the running step is shown") { panel.lines().any { it.trim().startsWith("2  make test  in_progress") } }
            assertTrue(panel.lines().toString(), panel.lines().any { it.trim().startsWith("#21  in_progress") })
            panel.select(repo, "210", "s:2")
            val expanded = panel.expandedKeys()
            assertTrue(expanded.toString(), expanded.any { it.endsWith("u:210/j:2101") })
            github.finishRunning()
            waitTree(panel, "the poll shows it done") { panel.tick(true); panel.lines().any { it.trim().startsWith("#21  success") } && panel.lines().any { it.trim().startsWith("2  make test  success") } }
            assertTrue(panel.expandedKeys().containsAll(expanded))
            assertEquals("make test", panel.selection()!!.step!!.name)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
            com.intellij.ide.util.PropertiesComponent.getInstance(project).unsetValue(CiPanel.KEY)
        }
    }

    private fun closeTheNetwork() {
        val ws = Path.of(bg { WorkspaceService.getInstance(project).call("workspace.show").resultObject().get("path").asString })
        Files.writeString(ws.resolve("github.edn"), "{:token-source :none :api \"http://127.0.0.1:1\"}\n")
    }

    fun testTheLastKnownRunsShowAtOnceWithTheirAgeWhenGithubCannotBeReached() {
        bg { ops.runs(repo) }                              // an answer from GitHub: kept on disk
        closeTheNetwork()
        val r = bg { ops.runs(repo, cached = true) }
        assertEquals("kept", r.source)
        assertEquals(listOf("210", "200", "195", "190"), r.rows.map { it.id })
        assertNotNull(r.ageS)
        val stale = bg { ops.runs(repo) }
        assertTrue(stale.stale)
        assertTrue(stale.error!!, stale.error!!.contains("cannot reach"))
        val panel = CiPanel(project)
        try {
            panel.addRepo(repo)
            waitTree(panel, "the kept runs are shown") { panel.lines().any { it.contains("#20") } }
            wait("the status line says they are the last known ones") { panel.statusText().contains("last known runs") }
            assertTrue(panel.statusText(), panel.statusText().contains("GitHub did not answer") || panel.statusText().contains("last known"))
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
            com.intellij.ide.util.PropertiesComponent.getInstance(project).unsetValue(CiPanel.KEY)
        }
    }

    fun testANewRunAppearsByItselfAndALowRateLimitIsInTheStatusLine() {
        val panel = CiPanel(project)
        try {
            panel.addRepo(repo)
            wait("the runs are shown") { panel.lines().any { it.contains("#21") } }
            assertFalse(panel.lines().any { it.contains("#22") })
            val expanded = panel.expandedKeys()
            github.newRun = true
            github.rateRemaining = 37
            panel.pollRuns(true)                          // what the 30 s timer does while the tab shows
            waitTree(panel, "the new run is in the tree without a refresh") { panel.lines().any { it.contains("#22") && it.contains("pushed meanwhile") } }
            assertTrue(panel.statusText(), panel.statusText().contains("rate limit: 37"))
            assertTrue("nothing folded", panel.expandedKeys().containsAll(expanded))
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
            com.intellij.ide.util.PropertiesComponent.getInstance(project).unsetValue(CiPanel.KEY)
        }
    }

    private fun updated(place: String, file: com.intellij.openapi.vfs.VirtualFile?): com.intellij.openapi.actionSystem.Presentation {
        val a = ActionManager.getInstance().getAction("deliberator.ci.local")
        val dc = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
            .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
            .also { b -> if (file != null) b.add(com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE, file) }.build()
        val e = com.intellij.openapi.actionSystem.AnActionEvent.createEvent(dc, a.templatePresentation.clone(), place, com.intellij.openapi.actionSystem.ActionUiKind.NONE, null)
        a.update(e)
        return e.presentation
    }

    fun testRunJobLocallyIsOnlyOfferedOnAWorkflowFile() {
        val wf = myFixture.addFileToProject(".github/workflows/ci.yml", "name: ci\non: push\njobs:\n  test:\n    runs-on: ubuntu-latest\n").virtualFile
        val other = myFixture.addFileToProject("src/App.java", "class App {}").virtualFile
        val onWorkflow = updated(com.intellij.openapi.actionSystem.ActionPlaces.EDITOR_POPUP, wf)
        assertTrue(onWorkflow.isVisible && onWorkflow.isEnabled)
        // the editor popup of any other file does not carry it; the menu shows it disabled
        assertFalse(updated(com.intellij.openapi.actionSystem.ActionPlaces.EDITOR_POPUP, other).isVisible)
        val menu = updated(com.intellij.openapi.actionSystem.ActionPlaces.MAIN_MENU, other)
        assertTrue(menu.isVisible)
        assertFalse(menu.isEnabled)
        assertEquals(listOf("test"), WorkflowFiles.jobs(String(wf.contentsToByteArray())).map { it.id })
    }
}
