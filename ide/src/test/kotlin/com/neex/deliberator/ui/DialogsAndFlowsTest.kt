package com.neex.deliberator.ui

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.neex.deliberator.actions.OpenWorkspaceAction
import com.neex.deliberator.actions.StartSessionDialog
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.daemon.DaemonService
import com.neex.deliberator.daemon.DeliberatorSettings
import com.neex.deliberator.logs.FetchForm
import com.neex.deliberator.logs.FetchLogsDialog
import com.neex.deliberator.logs.FetchRequest
import com.neex.deliberator.logs.LogService
import com.neex.deliberator.scn.ScnAutoPopup
import com.neex.deliberator.testing.TempDaemon
import com.neex.deliberator.workspace.OperationForm
import com.neex.deliberator.workspace.WorkspaceService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The dialogs and flows of the IDE that have a model part, driven without a screen (audit F12) and the transcript of the Operations
 * dialog (03c). The daemon is real, on a temp home.
 */
class DialogsAndFlowsTest : BasePlatformTestCase() {
    private lateinit var daemon: TempDaemon
    private lateinit var svc: WorkspaceService

    override fun setUp() {
        super.setUp()
        daemon = TempDaemon.create()
        DeliberatorSettings.getInstance().state.home = daemon.home.toString()
        svc = WorkspaceService.getInstance(project)
        svc.loadState(WorkspaceService.State())
        daemon.openWorkspace("w1")
        svc.bind("w1")
    }

    override fun tearDown() {
        try {
            DeliberatorSettings.getInstance().state.home = ""
            daemon.close()
        } finally {
            super.tearDown()
        }
    }

    private fun <T> bg(f: () -> T): T = ApplicationManager.getApplication().executeOnPooledThread<T> { f() }.get(120, TimeUnit.SECONDS)

    // --- 03c: the Operations transcript ------------------------------------------------------------

    private fun spec(op: String) = bg { OperationForm.load(svc.client()) }.first { it.op == op }

    fun testTheOperationsTranscriptIsKeptAsASessionNoteAndTheTabShowsThePath() {
        bg { svc.call("session.start", JsonObject().apply { addProperty("key", "T-1") }) }
        val dlg = OperationsDialog(project, svc)
        try {
            val s = spec("session.list")
            val saved = bg { dlg.execute(s, OperationForm.build(s, emptyMap(), "w1").args!!) }
            assertNotNull("saved", saved)
            val p = Path.of(saved!!)
            assertTrue(p.toString(), p.fileName.toString().startsWith("session.list-") && p.toString().contains("T-1"))
            val text = Files.readString(p)
            assertTrue(text, text.startsWith("> session.list "))
            assertTrue("the terminal line is in the transcript: $text", text.contains("\"sessions\""))
            assertFalse("the saved-path line is not part of what is saved", text.contains("transcript saved"))
            assertEquals(saved, dlg.savedNote)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertTrue(dlg.outputText().trimEnd().endsWith("transcript saved: $saved"))
            // an error ends a call as well: its transcript is kept too
            val bad = spec("runs.show")
            val savedErr = bg { dlg.execute(bad, OperationForm.build(bad, mapOf("run" to "no-such-run"), "w1").args!!) }
            assertNotNull(savedErr)
            assertTrue(Files.readString(Path.of(savedErr!!)).contains("ERROR "))
            assertTrue(saved != savedErr)
        } finally {
            Disposer.dispose(dlg.disposable)
        }
    }

    fun testWithoutASessionTheTranscriptCannotBeKeptAndThePaneSaysSo() {
        val dlg = OperationsDialog(project, svc)
        try {
            val s = spec("session.list")
            val saved = bg { dlg.execute(s, OperationForm.build(s, emptyMap(), "w1").args!!) }
            assertNull(saved)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertTrue(dlg.outputText(), dlg.outputText().contains("transcript not saved"))
            assertTrue("the result itself is still shown", dlg.outputText().contains("\"sessions\""))
        } finally {
            Disposer.dispose(dlg.disposable)
        }
    }

    // --- F7 / F12: Fetch Logs --------------------------------------------------------------------------

    fun testFetchLogsHidesSinceForAFileTargetAndSendsNone() {
        val dlg = FetchLogsDialog(project, listOf("log/service-a", "log/service-b"))
        try {
            assertTrue(dlg.sinceVisible())
            assertEquals(FetchRequest("log/service-a", "10m", null, null), dlg.request())
            dlg.setTarget("/var/log/x.log")
            assertFalse("a file has no 'since'", dlg.sinceVisible())
            assertEquals(FetchRequest("/var/log/x.log", null, null, null), dlg.request())
            dlg.setTarget("log/service-b")
            assertTrue(dlg.sinceVisible())
            dlg.setSince("2h")
            assertEquals("2h", dlg.request()!!.since)
            dlg.setTarget("  ")
            assertNull("no target, no request", dlg.request())
        } finally {
            Disposer.dispose(dlg.disposable)
        }
        assertTrue(FetchForm.isFileTarget("/a/b.log"))
        assertTrue(FetchForm.isFileTarget("C:\\logs\\a.log"))
        assertFalse(FetchForm.isFileTarget("log/service-a"))
        assertNull(FetchForm.request("/a.log", "10m", "", "")!!.since)
        assertEquals("5s", FetchForm.request("log/x", "", "5s", "")!!.`for`)
    }

    fun testFetchingAFileSendsNoSinceEvenIfTheRequestHasOne() {
        bg { svc.call("session.start", JsonObject().apply { addProperty("key", "F-1") }) }
        val src = daemon.home.resolve("f7.log").also { Files.writeString(it, "2026-10-16T10:00:00.000+02:00 INFO APP [c] a.B:1 - one\n") }
        val r = bg(300) { LogService.getInstance(project).fetchNow(FetchRequest(src.toString(), since = "10m")) }
        assertEquals("the whole file", 1, r.get("lines").asInt)
        assertTrue("no since in the daemon's log of the request: ${daemon.log().filter { it.contains("logs.fetch") }}", daemon.log().none { it.contains("logs.fetch") && it.contains("since") })
    }

    private fun <T> bg(seconds: Long, f: () -> T): T = ApplicationManager.getApplication().executeOnPooledThread<T> { f() }.get(seconds, TimeUnit.SECONDS)

    // --- F12: Start Session ----------------------------------------------------------------------------

    fun testStartSessionNeedsAKeyAndTrimsWhatItSends() {
        val dlg = StartSessionDialog(project)
        try {
            assertNotNull("blank key", dlg.validation())
            dlg.type("   ")
            assertNotNull("a key of spaces", dlg.validation())
            dlg.type(" K-7 ", " a title ")
            assertNull(dlg.validation())
            assertEquals("K-7" to "a title", dlg.answer())
            dlg.type("K-8", "")
            assertEquals("K-8" to null, dlg.answer())
        } finally {
            Disposer.dispose(dlg.disposable)
        }
        // and the key reaches the daemon the way the action sends it
        bg { svc.startSession("K-7", "a title") }
        assertEquals("K-7", svc.model.currentSession)
        assertEquals("a title", svc.model.sessions.single { it.key == "K-7" }.title)
    }

    // --- F8 / F12: Open Workspace ----------------------------------------------------------------------

    fun testOpenWorkspaceCreatesThroughTheDaemonAndAnswersTheProjectDirectory() {
        val dir = bg { OpenWorkspaceAction.projectDirOf("fresh-ws") }
        assertEquals(daemon.home.resolve("workspaces").resolve("fresh-ws").resolve("project").toRealPath(), dir.toRealPath())
        assertTrue(Files.isDirectory(dir))
        // the daemon knows it now, and the convention finds it from the project directory
        assertEquals("fresh-ws", bg { WorkspaceService.conventionWs(dir.toString(), DaemonService.getInstance().client()) })
        val e = try { bg { OpenWorkspaceAction.projectDirOf("two words") }; null } catch (x: Throwable) { generateSequence(x) { it.cause }.filterIsInstance<DaemonException>().firstOrNull() }
        assertEquals("store/invalid-name", e?.type)
    }

    fun testAProjectDirectoryOfAWorkspaceThatWasNeverOpenedIsNotBound() {
        val ghost = daemon.home.resolve("elsewhere").resolve("never-opened").resolve("project").also { Files.createDirectories(it) }
        assertNull("the daemon does not know it: unbound", bg { WorkspaceService.conventionWs(ghost.toString(), DaemonService.getInstance().client()) })
        assertFalse("and asking created nothing", Files.exists(daemon.home.resolve("workspaces").resolve("never-opened")))
    }

    // --- 05 follow-up: the auto popup trigger of the .scn editor ---------------------------------------

    fun testTheScnAutoPopupTriggers() {
        assertTrue(ScnAutoPopup.triggers('@', "# @"))
        assertTrue(ScnAutoPopup.triggers('{', "x {{"))
        assertTrue(ScnAutoPopup.triggers(' ', "### "))
        assertTrue("before the space is inserted", ScnAutoPopup.triggers(' ', "###"))
        assertFalse(ScnAutoPopup.triggers(' ', "# @for "))
        assertFalse(ScnAutoPopup.triggers('a', "### a"))
    }
}
