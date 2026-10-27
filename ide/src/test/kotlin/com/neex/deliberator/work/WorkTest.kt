package com.neex.deliberator.work

import com.google.gson.JsonObject
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.common.ThreadLeakTracker
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.daemon.DeliberatorSettings
import com.neex.deliberator.repo.RepositoryAttacher
import com.neex.deliberator.testing.GithubStandIn
import com.neex.deliberator.testing.TempDaemon
import com.neex.deliberator.workspace.WorkspaceService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * M5.1 and M5.5 against the real daemon on a temp home: a bare clone of a local repository, worktrees of it attached to the project as modules and removed
 * again, B27 (the bare clone is never attached), and `pr.open` with a local `refs/pull/1/head` and the GitHub stand-in for the comments and the checks.
 */
class WorkTest : HeavyPlatformTestCase() {
    private lateinit var daemon: TempDaemon
    private lateinit var ops: WorkOps
    private lateinit var attacher: RepositoryAttacher
    private lateinit var src: Path
    private lateinit var origin: Path
    private var github: GithubStandIn? = null

    override fun setUp() {
        super.setUp()
        ThreadLeakTracker.longRunningThreadCreated(ApplicationManager.getApplication(), "MasterPoller", "/127.0.0.1")
        daemon = TempDaemon.create()
        DeliberatorSettings.getInstance().state.home = daemon.home.toString()
        daemon.openWorkspace("w1")
        WorkspaceService.getInstance(project).bind("w1")
        ops = WorkOps.getInstance(project)
        attacher = RepositoryAttacher.getInstance(project)
        src = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "work").toRealPath()
        // a repository with one commit on main, and its bare origin (the clone's "remote")
        val checkout = src.resolve("proj")
        Files.createDirectories(checkout)
        git(checkout, "init", "-q", "-b", "main")
        Files.writeString(checkout.resolve("README"), "hello\n")
        git(checkout, "add", "-A"); git(checkout, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init")
        origin = src.resolve("proj.git")
        git(src, "clone", "-q", "--bare", checkout.toString(), origin.toString())
    }

    override fun tearDown() {
        try {
            github?.close()
            DeliberatorSettings.getInstance().state.home = ""
            daemon.close()
            src.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun git(dir: Path, vararg a: String): String {
        val p = ProcessBuilder(listOf("git") + a).directory(dir.toFile()).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) { "git ${a.toList()} failed: $out" }
        return out.trim()
    }

    private fun <T> bg(f: () -> T): T = PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread<T> { f() }, 180_000)
    private fun moduleNames() = ModuleManager.getInstance(project).modules.map { it.name }
    /** The platform logs an exception of a pooled task as an error (which fails the task); the daemon's own is in its cause chain. */
    private fun typed(f: () -> Any?): DaemonException = try { bg(f); fail("expected a DaemonException"); error("unreachable") } catch (x: Throwable) {
        generateSequence(x) { it.cause }.filterIsInstance<DaemonException>().firstOrNull() ?: throw x
    }

    fun testActionsAndTabsAreRegistered() {
        for (id in listOf("deliberator.work.start", "deliberator.work.clone", "deliberator.pr.open", "deliberator.work.remove", "deliberator.work.refresh"))
            assertNotNull(id, ActionManager.getInstance().getAction(id))
        assertNotNull(ActionManager.getInstance().getAction("deliberator.work"))
    }

    fun testStartWorkingMakesTheWorktreeAndAttachesItWhileTheBareCloneIsNeverAttached() {
        val repo = bg { ops.clone(origin.toString()) }
        assertEquals("proj", repo.name)
        assertTrue(repo.bare)
        // B27: the project's repository list is checkouts only; the Start Working chooser (includeBare) sees the clone
        assertEquals(emptyList<String>(), bg { attacher.list() }.map { it.name })
        val all = bg { ops.repos() }
        assertEquals(listOf("proj" to true), all.map { it.name to it.bare })
        assertEquals(origin.toString(), all[0].url)

        val w = bg { ops.startWorking("proj", "feat-a") }
        assertEquals("feat-a", w.worktree.name)
        assertEquals("feat-a", w.worktree.branch)
        assertTrue(w.created)
        assertNull(w.problem)
        assertTrue(Files.isRegularFile(Path.of(w.worktree.path).resolve("README")))
        assertEquals("plain", w.attached!!.repo.kind)
        assertTrue(moduleNames().toString(), "feat-a" in moduleNames())

        // reopening the project (the reconcile) neither attaches the bare clone nor loses the worktree
        val report = bg { attacher.reattachAll() }
        assertEquals(emptyList<String>(), report.problems)
        assertEquals(listOf("feat-a"), bg { attacher.list() }.map { it.name })
        assertFalse(moduleNames().toString(), "proj" in moduleNames())

        // the tab's rows
        val snap = bg { ops.refreshNow() }
        assertEquals(listOf("proj"), snap.repos.map { it.name })
        assertEquals(listOf("proj", "feat-a", "feat-a", "clean"), WorkPanel.worktreeCells(snap.worktrees[0]).take(5).filterIndexed { i, _ -> i != 3 }.map { it.toString() })
        assertEquals(listOf("proj", "bare clone", origin.toString()), WorkPanel.repoCells(snap.repos[0]).map { it.toString() })
    }

    fun testTheDefaultBranchNameAndAnExistingBranch() {
        bg { ops.clone(origin.toString()) }
        val a = bg { ops.startWorking("proj") }
        assertTrue(a.worktree.name, Regex("work-\\d{8}-\\d{6}").matches(a.worktree.name))
        val b = bg { ops.startWorking("proj", "feat-b") }
        bg { ops.removeWorktree(b.worktree) }
        // the branch stays in the repository: starting it again checks it out as it is
        val again = bg { ops.startWorking("proj", "feat-b") }
        assertFalse(again.created)
    }

    fun testRemovingAWorktreeWithChangesIsRefusedUntilForcedAndTheModuleGoesWithIt() {
        bg { ops.clone(origin.toString()) }
        val w = bg { ops.startWorking("proj", "feat-c") }
        Files.writeString(Path.of(w.worktree.path).resolve("README"), "changed\n")
        assertEquals(1, bg { ops.worktrees("proj") }.single().dirty)
        assertEquals("worktree/dirty", typed { ops.removeWorktree(w.worktree) }.type)
        assertTrue(Files.isDirectory(Path.of(w.worktree.path)))
        assertTrue("feat-c" in moduleNames())

        bg { ops.removeWorktree(w.worktree, force = true) }
        assertFalse(Files.exists(Path.of(w.worktree.path)))
        assertEquals(emptyList<Worktree>(), bg { ops.worktrees() })
        assertFalse(moduleNames().toString(), "feat-c" in moduleNames())
        assertEquals(emptyList<String>(), bg { attacher.list() }.map { it.name })
    }

    fun testACloneThatFailsIsATypedError() {
        typed { ops.clone(src.resolve("nothing.git").toString()) }
        assertEquals(emptyList<String>(), bg { ops.repos() }.map { it.name })
    }

    fun testOpenPrAttachesTheWorktreeAndWritesTheCommentsAndTheChecksAsText() {
        github = GithubStandIn().also { it.point(Path.of(bg { WorkspaceService.getInstance(project).call("workspace.show").resultObject().get("path").asString })) }
        bg { WorkspaceService.getInstance(project).call("session.start", JsonObject().apply { addProperty("key", "S1") }) }
        git(origin, "update-ref", "refs/pull/1/head", "refs/heads/main")
        bg { ops.clone(origin.toString()) }

        val pr = bg { ops.openPr("proj", 1, "acme/widgets") }
        assertEquals("Add feature", pr.title)
        assertEquals("pr-1", pr.working.worktree.name)
        assertNull(pr.working.problem)
        assertEquals("plain", pr.working.attached!!.repo.kind)
        assertTrue("pr-1" in moduleNames())
        assertTrue(Files.isRegularFile(Path.of(pr.working.worktree.path).resolve("README")))
        val review = Files.readString(Path.of(pr.reviewPath!!))
        assertTrue(review, review.contains("needs tests") && review.contains("nit: say more") && review.contains("will fix"))
        assertTrue(Files.readString(Path.of(pr.checksPath!!)).contains("failure"))
        assertEquals("4 runs: 1 failure, 1 in_progress, 2 success", pr.checks)
        assertNull(pr.note)

        // opening it again changes nothing (the same worktree, still attached once)
        val again = bg { ops.openPr("proj", 1, "acme/widgets") }
        assertFalse(again.working.created)
        assertEquals(1, moduleNames().count { it == "pr-1" })
    }

    fun testOpenPrWithoutGithubStillGivesTheWorktreeAndSaysWhy() {
        bg { WorkspaceService.getInstance(project).call("session.start", JsonObject().apply { addProperty("key", "S1") }) }
        git(origin, "update-ref", "refs/pull/2/head", "refs/heads/main")
        bg { ops.clone(origin.toString()) }
        val pr = bg { ops.openPr("proj", 2, "none") }
        assertEquals("pr-2", pr.working.worktree.name)
        assertNull(pr.reviewPath)
        assertEquals("github skipped", pr.note)
        typed { ops.openPr("proj", 77, "none") }
    }
}
