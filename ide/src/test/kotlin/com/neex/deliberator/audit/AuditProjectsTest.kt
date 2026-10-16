package com.neex.deliberator.audit

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.neex.deliberator.daemon.DeliberatorSettings
import com.neex.deliberator.logs.MarkerService
import com.neex.deliberator.repo.ReattachOnOpen
import com.neex.deliberator.repo.RepositoryAttacher
import com.neex.deliberator.testing.TempDaemon
import com.neex.deliberator.workspace.WorkspaceService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The independent audit of M2: two IDE projects on two workspaces in one JVM (one shared daemon connection, per-project services),
 * attach oddities (the same directory spelt three ways, nested and enclosing directories, a repository that vanished) and the
 * startup activity itself. Heavy platform tests, real daemon on a throwaway home.
 */
class AuditProjectsTest : HeavyPlatformTestCase() {
    private lateinit var daemon: TempDaemon
    private lateinit var work: Path

    override fun setUp() {
        super.setUp()
        // JDK-internal pollers of the virtual threads the daemon client uses outlive a test (as in AttachDetachTest)
        com.intellij.testFramework.common.ThreadLeakTracker.longRunningThreadCreated(ApplicationManager.getApplication(), "MasterPoller", "/127.0.0.1")
        daemon = TempDaemon.create()
        DeliberatorSettings.getInstance().state.home = daemon.home.toString()
        daemon.openWorkspace("w1")
        work = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "audit").toRealPath()
    }

    override fun tearDown() {
        try {
            DeliberatorSettings.getInstance().state.home = ""
            daemon.close()
            work.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun <T> bg(f: () -> T): T = PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread<T> { f() }, 180_000)
    private fun moduleNames(p: com.intellij.openapi.project.Project) = ModuleManager.getInstance(p).modules.map { it.name }
    private fun dir(vararg parts: String): Path = parts.fold(work) { a, b -> a.resolve(b) }.also { Files.createDirectories(it) }.also { if (it.resolve("main.tf").let { f -> !Files.exists(f) }) Files.writeString(it.resolve("main.tf"), "# x\n") }

    fun testTwoProjectsOnTwoWorkspacesInOneJvmDoNotSeeEachOther() {
        val p1 = project
        val p2dir = Files.createDirectories(work.resolve("p2"))
        val p2 = PlatformTestUtil.loadAndOpenProject(p2dir, testRootDisposable)
        try {
            val s1 = WorkspaceService.getInstance(p1).also { it.loadState(WorkspaceService.State()); it.bind("w1") }
            val s2 = WorkspaceService.getInstance(p2).also { it.loadState(WorkspaceService.State()); it.bind("w2") }
            assertNotSame(s1, s2)
            bg { s1.startSession("ONE-1", "first"); s2.startSession("TWO-1", "second") }
            assertEquals("ONE-1", bg { s1.refreshNow() }.currentSession)
            assertEquals("TWO-1", bg { s2.refreshNow() }.currentSession)
            assertEquals(listOf("ONE-1"), s1.model.sessions.map { it.key })
            assertEquals(listOf("TWO-1"), s2.model.sessions.map { it.key })

            // markers live per workspace
            val m1 = MarkerService.getInstance(p1)
            val m2 = MarkerService.getInstance(p2)
            bg { m1.addNow("/src/One.java", 3, "a.One") }
            assertEquals(listOf("/src/One.java"), bg { m1.refreshNow() }.map { it.file })
            assertEquals(emptyList<String>(), bg { m2.refreshNow() }.map { it.file })

            // repositories and modules per project
            val a1 = RepositoryAttacher.getInstance(p1)
            val a2 = RepositoryAttacher.getInstance(p2)
            bg { a1.attach(dir("r", "only-in-one").toString()) }
            assertTrue(moduleNames(p1).contains("only-in-one"))
            assertFalse(moduleNames(p2).contains("only-in-one"))
            assertEquals(emptyList<String>(), bg { a2.list() }.map { it.name })
            assertTrue("reconciling p2 does not pull in p1's repository", bg { a2.reattachAll() }.empty)
            assertEquals(listOf("only-in-one"), bg { a1.list() }.map { it.name })
            bg { a1.detach("only-in-one") }
        } finally {
            PlatformTestUtil.forceCloseProjectWithoutSaving(p2)
        }
    }

    fun testTheSameDirectorySpeltThreeWaysIsOneRepositoryAndOneModule() {
        val ws = WorkspaceService.getInstance(project).also { it.loadState(WorkspaceService.State()); it.bind("w1") }
        val attacher = RepositoryAttacher.getInstance(project)
        val d = dir("same")
        val link = work.resolve("link-to-same")
        Files.createSymbolicLink(link, d)
        assertNull(bg { attacher.attach(d.toString()) }.note)
        for (spelt in listOf("$d/", "$d/../same", link.toString())) {
            val o = bg { attacher.attach(spelt) }
            assertEquals(spelt, "already attached", o.note)
        }
        assertEquals(1, bg { attacher.list() }.size)
        assertEquals(1, moduleNames(project).count { it == "same" })
        assertNotNull(ws.ws)
        bg { attacher.detach("same") }
    }

    fun testANestedAndAnEnclosingDirectoryOfAnAttachedRepositoryAreRecordedWithoutTouchingTheModules() {
        WorkspaceService.getInstance(project).also { it.loadState(WorkspaceService.State()); it.bind("w1") }
        val attacher = RepositoryAttacher.getInstance(project)
        val outer = dir("outer")
        val inner = dir("outer", "inner")
        bg { attacher.attach(outer.toString()) }
        val before = moduleNames(project)
        val o = bg { attacher.attach(inner.toString()) }
        println("AUDIT-NESTED: attach of a subdirectory of an attached repository -> note=${o.note}, modules ${before} -> ${moduleNames(project)}")
        assertEquals(listOf("outer", "inner"), bg { attacher.list() }.map { it.name })
        // detaching the inner one must not take the outer module away
        bg { attacher.detach("inner") }
        assertTrue(moduleNames(project).contains("outer"))
        assertEquals(listOf("outer"), bg { attacher.list() }.map { it.name })
        // an enclosing directory
        val root = dir("root-of-all")
        val up = bg { attacher.attach(work.toString()) }
        println("AUDIT-NESTED: attach of the directory that encloses every repository -> note=${up.note}, modules ${moduleNames(project)}")
        bg { attacher.detach(up.repo.name) }
        bg { attacher.detach("outer") }
        assertTrue(root.toFile().exists())
        assertEquals(emptyList<String>(), bg { attacher.list() }.map { it.name })
    }

    fun testARepositoryDirectoryThatVanishedIsReportedOnOpenAndTheRestStillAttaches() {
        WorkspaceService.getInstance(project).also { it.loadState(WorkspaceService.State()); it.bind("w1") }
        val attacher = RepositoryAttacher.getInstance(project)
        val keep = dir("keep")
        val gone = dir("gone")
        bg { attacher.attach(keep.toString()); attacher.attach(gone.toString()) }
        bg { attacher.detachIdeOnly(keep); attacher.detachIdeOnly(gone) }
        gone.toFile().deleteRecursively()
        val r = bg { attacher.reattachAll() }
        assertEquals(listOf("keep"), r.attached)
        assertEquals(1, r.problems.size)
        assertTrue(r.problems[0], r.problems[0].startsWith("gone: ") && r.problems[0].contains("is not a directory any more"))
        bg { attacher.detach("keep") }
    }

    fun testTheStartupActivityReattachesTheRecordedRepositoriesOfAnOpenedProject() {
        val ws = WorkspaceService.getInstance(project).also { it.loadState(WorkspaceService.State()); it.bind("w1") }
        val d = dir("recorded")
        bg { daemon.client.call("repo.attach", JsonObject().apply { addProperty("ws", "w1"); addProperty("path", d.toString()) }) }
        assertFalse(moduleNames(project).contains("recorded"))
        bg { ReattachOnOpen.reattach(project) }
        assertTrue("the project opened with the record has the module", moduleNames(project).contains("recorded"))
        // an unbound project and an unreachable daemon are quiet
        ws.loadState(WorkspaceService.State())
        bg { ReattachOnOpen.reattach(project) }
        bg { daemon.launcher.stopDaemon(daemon.home) }
        ws.bind("w1")
        bg { ReattachOnOpen.reattach(project) } // restarts the daemon through ensure(), still no exception
        bg { RepositoryAttacher.getInstance(project).detach("recorded") }
    }

    /** Layer 3, first point: a project OPENED at `<workspace dir>/project` is bound by the convention, with nothing persisted and no setting. */
    fun testAProjectOpenedAtTheWorkspaceProjectDirectoryResolvesItsWorkspaceByTheDaemonsWord() {
        daemon.openWorkspace("w9")
        val shown = bg { daemon.client.call("workspace.show", JsonObject().apply { addProperty("ws", "w9") }) }.resultObject().get("path").asString
        val projectDir = Files.createDirectories(Path.of(shown).resolve("project"))
        val p = PlatformTestUtil.loadAndOpenProject(projectDir, testRootDisposable)
        try {
            val svc = WorkspaceService.getInstance(p)
            // the startup activity (ReattachOnOpen) resolves the convention on its own; asking again gives the same answer
            PlatformTestUtil.waitWithEventsDispatching("the opened project to bind itself", { svc.ws == "w9" }, 60)
            assertEquals("w9", bg { svc.resolve() })
            // the model of that project is the workspace's: a session started through it is that workspace's
            bg { svc.startSession("OPEN-1", null) }
            assertEquals("OPEN-1", bg { svc.refreshNow() }.currentSession)
            // a neighbour directory that merely looks the same is not bound
            val other = PlatformTestUtil.loadAndOpenProject(Files.createDirectories(work.resolve("w9").resolve("project")), testRootDisposable)
            try { assertNull(bg { WorkspaceService.getInstance(other).resolve() }) } finally { PlatformTestUtil.forceCloseProjectWithoutSaving(other) }
        } finally {
            PlatformTestUtil.forceCloseProjectWithoutSaving(p)
        }
    }
}
