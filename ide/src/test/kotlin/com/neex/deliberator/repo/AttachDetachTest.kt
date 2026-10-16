package com.neex.deliberator.repo

import com.google.gson.JsonObject
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.common.ThreadLeakTracker
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.daemon.DeliberatorSettings
import com.neex.deliberator.testing.TempDaemon
import com.neex.deliberator.workspace.WorkspaceService
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Piece 04 against the real daemon and a real (heavy) project: attach and detach per kind without reopening, "reopen"
 * (a fresh reconcile of the project with the record), the record being the truth both ways, and the missing-plugin path.
 * The blocking attacher runs on a pooled thread while the test thread (the EDT) pumps events, so write actions complete.
 */
class AttachDetachTest : HeavyPlatformTestCase() {
    private lateinit var daemon: TempDaemon
    private lateinit var attacher: RepositoryAttacher
    private lateinit var work: Path
    private var jdksBefore: List<com.intellij.openapi.projectRoots.Sdk> = emptyList()

    override fun setUp() {
        super.setUp()
        jdksBefore = ProjectJdkTable.getInstance().allJdks.toList()
        // JDK-internal pollers of the virtual threads the daemon client uses, and the Maven server's RMI workers, outlive a test
        ThreadLeakTracker.longRunningThreadCreated(ApplicationManager.getApplication(), "MasterPoller", "/127.0.0.1")
        daemon = TempDaemon.create()
        DeliberatorSettings.getInstance().state.home = daemon.home.toString()
        WorkspaceService.getInstance(project).bind("w1")
        attacher = RepositoryAttacher.getInstance(project)
        work = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "attach").toRealPath()
        val data = Path.of(System.getProperty("deliberator.testdata"), "attach")
        for (k in listOf("gradle", "maven", "plain")) data.resolve(k).toFile().copyRecursively(work.resolve(k).toFile())
    }

    override fun tearDown() {
        try {
            // a Gradle import registers the IDE's runtime as an SDK; the platform's leak check wants it gone
            WriteAction.runAndWait<RuntimeException> {
                for (j in ProjectJdkTable.getInstance().allJdks) if (j !in jdksBefore) ProjectJdkTable.getInstance().removeJdk(j)
            }
            DeliberatorSettings.getInstance().state.home = ""
            daemon.close()
            work.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun <T> bg(f: () -> T): T = PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread<T> { f() }, 180_000)

    private fun until(what: String, seconds: Int = 120, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what; modules ${moduleNames()}; mavenized ${MavenProjectsManager.getInstance(project).isMavenizedProject}" }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); Thread.sleep(100)
        }
    }

    private fun moduleNames() = ModuleManager.getInstance(project).modules.map { it.name }
    private fun recorded() = bg { attacher.list() }.map { it.name to it.kind }
    private fun vf(p: Path) = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(p)!!

    fun testActionsAreRegisteredWithTheirIds() {
        for (id in listOf("deliberator.repo.attach", "deliberator.repo.detach")) assertNotNull(id, ActionManager.getInstance().getAction(id))
        assertEquals(setOf("plain", "gradle", "maven"), RepositoryKind.EP.extensionList.map { it.kind }.toSet())
    }

    fun testAPlainDirectoryBecomesAModuleAndGoesAwayAgain() {
        val dir = work.resolve("plain")
        val o = bg { attacher.attach(dir.toString()) }
        assertEquals("plain", o.repo.kind)
        assertNull(o.note)
        assertEquals(listOf("plain" to "plain"), recorded())
        // the module exists at once, with the directory as its content root, named after the repository
        val m = ModuleManager.getInstance(project).findModuleByName("plain")!!
        assertEquals(listOf(dir), ModuleRootManager.getInstance(m).contentRoots.map { it.toNioPath() })
        assertTrue(ProjectFileIndex.getInstance(project).isInContent(vf(dir.resolve("main.tf"))))
        // nothing was written into the repository, and no .iml anywhere
        assertEquals(setOf("main.tf", "variables.tf"), dir.toFile().list()!!.toSet())
        val base = Path.of(project.basePath!!)
        if (Files.exists(base)) assertFalse(Files.walk(base).use { s -> s.anyMatch { it.toString().endsWith("plain.iml") } })

        // attaching again changes nothing
        assertEquals("already attached", bg { attacher.attach(dir.toString()) }.note)
        assertEquals(1, moduleNames().count { it == "plain" })

        bg { attacher.detach("plain") }
        assertNull(ModuleManager.getInstance(project).findModuleByName("plain"))
        assertFalse(ProjectFileIndex.getInstance(project).isInContent(vf(dir.resolve("main.tf"))))
        assertEquals(emptyList<Pair<String, String>>(), recorded())
        assertTrue(dir.resolve("main.tf").toFile().exists())
    }

    fun testReopenReattachesWhatTheProjectLacksAndDropsWhatTheRecordLost() {
        val plain = work.resolve("plain")
        bg { attacher.attach(plain.toString()) }
        // "reopen": the non-persistent module is gone, the record is not
        bg { attacher.detachIdeOnly(plain) }
        assertNull(ModuleManager.getInstance(project).findModuleByName("plain"))
        val r = bg { attacher.reattachAll() }
        assertEquals(listOf("plain"), r.attached)
        assertNotNull(ModuleManager.getInstance(project).findModuleByName("plain"))
        assertTrue(bg { attacher.reattachAll() }.empty)

        // a detach done through the daemon while the IDE was closed takes effect at the next open
        bg { daemon.client.call("repo.detach", JsonObject().apply { addProperty("ws", "w1"); addProperty("name", "plain") }) }
        val r2 = bg { attacher.reattachAll() }
        assertEquals(listOf("plain"), r2.detached)
        assertNull(ModuleManager.getInstance(project).findModuleByName("plain"))
    }

    fun testADirectoryThatIsAlreadyAModuleIsRecordedAndLeftAlone() {
        val dir = work.resolve("plain")
        ModuleRootModificationUtil.addContentRoot(module, dir.toString())
        val before = moduleNames()
        val o = bg { attacher.attach(dir.toString()) }
        assertEquals("already part of module ${module.name}", o.note)
        assertEquals(before, moduleNames())
        assertEquals(listOf("plain" to "plain"), recorded())
        bg { attacher.detach("plain") }
        assertEquals(before, moduleNames())
        assertEquals(listOf(dir), ModuleRootManager.getInstance(module).contentRoots.map { it.toNioPath() })
    }

    fun testAKindWithoutItsPluginIsRefusedAndNothingIsRecorded() {
        val only = RepositoryKind.EP.extensionList.filter { it.kind == "plain" }
        ExtensionTestUtil.maskExtensions(RepositoryKind.EP, only, testRootDisposable)
        val e = try { bg { attacher.attach(work.resolve("gradle").toString()) }; null } catch (x: Throwable) {
            generateSequence(x) { it.cause }.filterIsInstance<DaemonException>().first()
        }
        assertEquals("Gradle plugin not available", e!!.message)
        assertEquals("repo/plugin-not-available", e.type)
        assertEquals(emptyList<Pair<String, String>>(), recorded())
        // plain still works, and a recorded entry of the missing kind is reported on open, not thrown
        bg { daemon.client.call("repo.attach", JsonObject().apply { addProperty("ws", "w1"); addProperty("path", work.resolve("maven").toString()) }) }
        val r = bg { attacher.reattachAll() }
        assertEquals(listOf("maven: Maven plugin not available"), r.problems)
    }

    fun testAMavenRepositoryBecomesAManagedProjectAndGoesAway() {
        val dir = work.resolve("maven")
        val pom = vf(dir.resolve("pom.xml"))
        val o = bg { attacher.attach(dir.toString()) }
        assertEquals("maven", o.repo.kind)
        assertTrue(MavenProjectsManager.getInstance(project).isManagedFile(pom))
        assertTrue(WorkspaceService.getInstance(project).state.repos.contains("maven\t$dir"))
        // the import of a dependency-free pom needs no network: the module arrives without reopening
        until("the Maven module") { moduleNames().contains("maven-fixture") }

        bg { attacher.detach("maven") }
        assertFalse(MavenProjectsManager.getInstance(project).isManagedFile(pom))
        until("the Maven module to go") { !moduleNames().contains("maven-fixture") }
        assertTrue(WorkspaceService.getInstance(project).state.repos.isEmpty())
        assertEquals(emptyList<Pair<String, String>>(), recorded())
    }

    fun testAGradleRepositoryIsLinkedAndGoesAway() {
        val dir = work.resolve("gradle")
        val o = bg { attacher.attach(dir.toString()) }
        assertEquals("gradle", o.repo.kind)
        val ps = GradleSettings.getInstance(project).getLinkedProjectSettings(dir.toString())!!
        assertEquals(dir.toString(), ps.externalProjectPath)
        assertTrue(WorkspaceService.getInstance(project).state.repos.contains("gradle\t$dir"))
        assertEquals("already linked", bg { attacher.attach(dir.toString()) }.note)
        // the real import (the IDE's own runtime, the bundled Gradle distribution, no network): the modules arrive without reopening
        until("the Gradle modules", 240) { moduleNames().containsAll(listOf("gradle-fixture", "gradle-fixture.main", "gradle-fixture.test")) }

        bg { attacher.detach("gradle") }
        assertNull(GradleSettings.getInstance(project).getLinkedProjectSettings(dir.toString()))
        assertTrue(moduleNames().none { it.startsWith("gradle-fixture") })
        assertTrue(WorkspaceService.getInstance(project).state.repos.isEmpty())
        assertEquals(emptyList<Pair<String, String>>(), recorded())
    }
}
