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
            assertTrue(panel.lines().toString(), panel.lines().any { it.trim().startsWith("#20  failure  feat") })
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
}
