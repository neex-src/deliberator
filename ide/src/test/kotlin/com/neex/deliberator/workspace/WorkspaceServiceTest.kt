package com.neex.deliberator.workspace

import com.google.gson.JsonObject
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.daemon.DeliberatorSettings
import com.neex.deliberator.testing.TempDaemon
import com.neex.deliberator.ui.DeliberatorPanel
import java.util.concurrent.TimeUnit

/**
 * Piece 03 against the real daemon (jar on a temp home): the project's workspace binding, the model
 * after session start/end, the persisted environment selection, the generic operations form and the
 * registered action ids. The blocking service calls run on a pooled thread (the test thread is the EDT).
 */
class WorkspaceServiceTest : BasePlatformTestCase() {
    private lateinit var daemon: TempDaemon
    private lateinit var svc: WorkspaceService

    override fun setUp() {
        super.setUp()
        daemon = TempDaemon.create()
        DeliberatorSettings.getInstance().state.home = daemon.home.toString()
        svc = WorkspaceService.getInstance(project)
        svc.loadState(WorkspaceService.State())
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

    private fun args(vararg kv: Pair<String, String>) = JsonObject().also { o -> kv.forEach { o.addProperty(it.first, it.second) } }

    fun testActionsAreRegisteredWithTheirIds() {
        for (id in listOf("workspace.open", "workspace.bind", "workspace.refresh", "session.start", "session.end", "runtime.up",
            "runtime.down", "runtime.logs", "env.select", "runs.open", "ops.show", "daemon.status")) {
            assertNotNull("deliberator.$id", ActionManager.getInstance().getAction("deliberator.$id"))
        }
        assertNotNull(ActionManager.getInstance().getAction("deliberator.toolwindow.actions"))
    }

    fun testAnUnboundProjectHasNoWorkspaceAndCallsAreRefused() {
        assertNull(svc.ws)
        // a plain light project (not under a workspace directory) does not resolve by convention either
        assertNull(bg { svc.resolve() })
        val e = try { bg { svc.call("session.list") }; null } catch (x: Throwable) { generateSequence(x) { it.cause }.filterIsInstance<DaemonException>().firstOrNull() ?: x }
        assertEquals(e.toString(), "workspace/unbound", (e as? DaemonException)?.type)
        assertTrue(DeliberatorPanel.headerText(WorkspaceModel.unbound()).contains("Open workspace"))
    }

    fun testTheDirectoryConventionIsConfirmedByTheDaemon() {
        val client = daemon.client
        daemon.openWorkspace("w1")
        val shown = client.call("workspace.show", args("ws" to "w1")).resultObject().get("path").asString
        // <workspace directory>/project is the window of w1 ...
        assertEquals("w1", WorkspaceService.conventionWs("$shown/project", client))
        // ... any other directory named project is not, even when its parent's name is a valid workspace name
        assertNull(WorkspaceService.conventionWs("${daemon.home}/elsewhere/project", client))
        assertNull(WorkspaceService.conventionWs("$shown/notproject", client))
    }

    fun testSessionStartAndEndAreReflectedInTheModel() {
        daemon.openWorkspace("w2")
        svc.bind("w2")
        assertEquals("w2", svc.ws)
        val seen = mutableListOf<WorkspaceModel>()
        svc.addListener(testRootDisposable) { seen += it }
        val first = bg { svc.refreshNow() }
        assertEquals("w2", first.ws)
        assertNull(first.currentSession)
        assertTrue(first.sessions.isEmpty())
        assertNotNull(first.path)
        assertNotNull(first.engineReachable)
        assertTrue("local env listed: ${first.envs}", first.envs.any { it.name == "local" })
        assertNull(first.error)

        val started = bg { svc.startSession("T-1", "first ticket") }
        assertEquals("T-1", started.currentSession)
        assertEquals(listOf("T-1"), started.sessions.map { it.key })
        assertEquals("first ticket", started.sessions[0].title)
        assertFalse(started.sessions[0].isEnded)
        assertTrue(started.runs.isEmpty())

        val ended = bg { svc.endSession() }
        assertNull(ended.currentSession)
        assertTrue(ended.sessions[0].isEnded)

        // the listeners heard every change on the EDT
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue("listener saw the started session", seen.any { it.currentSession == "T-1" })
        assertTrue(seen.last().sessions[0].isEnded)
        assertTrue(DeliberatorPanel.headerText(started).contains("session T-1"))
    }

    fun testTheEnvironmentSelectionAndTheBindingSurviveReopen() {
        svc.bind("w3")
        assertEquals("local", svc.selectedEnv)
        bg { svc.refreshNow() }
        svc.selectEnv("staging")
        assertEquals("staging", svc.model.selectedEnv)

        // what the platform writes to .idea/deliberator.xml and reads back when the project reopens
        val xml = XmlSerializer.serialize(svc.state)
        val reopened = WorkspaceService(project)
        reopened.loadState(XmlSerializer.deserialize(xml, WorkspaceService.State::class.java))
        assertEquals("w3", reopened.ws)
        assertEquals("staging", reopened.selectedEnv)
        // and the reopened service finds the same workspace's session through the daemon
        bg { svc.startSession("T-9", null) }
        val m = bg { reopened.refreshNow() }
        assertEquals("T-9", m.currentSession)
        assertEquals("staging", m.selectedEnv)
    }

    fun testTheOperationsFormListsEveryOpAndCallsThem() {
        svc.bind("w4")
        val specs = bg { OperationForm.load(svc.client()) }
        val byName = specs.associateBy { it.op }
        for (op in listOf("session.start", "runtime.status", "env.list", "workspace.show", "scenario.run", "op.describe", "runtime.logs")) {
            assertNotNull(op, byName[op])
        }
        val start = byName.getValue("session.start")
        assertEquals(listOf("key", "title"), start.args.map { it.name })
        assertTrue(start.args[0].required)
        assertTrue(start.takesWs)
        assertTrue(start.mutating)
        assertFalse(byName.getValue("runtime.status").mutating)

        // required field missing -> nothing is sent
        val bad = OperationForm.build(start, mapOf("title" to "x"), "w4")
        assertNull(bad.args)
        assertEquals(listOf("key is required"), bad.problems)

        // typed conversion
        val logs = byName.getValue("runtime.logs")
        val r = OperationForm.build(logs, mapOf("role" to "floci", "tail" to "5", "follow" to "false"), "w4")
        assertEquals(listOf<String>(), r.problems)
        assertEquals(5, r.args!!.get("tail").asInt)
        assertEquals(false, r.args!!.get("follow").asBoolean)
        assertEquals("w4", r.args!!.get("ws").asString)
        assertEquals(listOf("tail: not an integer: five"), OperationForm.build(logs, mapOf("role" to "x", "tail" to "five"), "w4").problems)
        val scenarioRun = byName.getValue("scenario.run")
        assertEquals(listOf(1L, 3L), OperationForm.build(scenarioRun, mapOf("path" to "a.scn", "steps" to "1, 3"), "w4").args!!.getAsJsonArray("steps").map { it.asLong })

        // the form drives real ops: session.start, then runtime.status of that workspace
        val events = mutableListOf<String>()
        bg {
            val a = OperationForm.build(start, mapOf("key" to "FORM-1", "title" to "from the form"), svc.ws).args!!
            val t = OperationForm.run(svc.client(), start, a) { events += it.name }
            assertTrue(t.ok)
            assertEquals("FORM-1", t.resultObject().getAsJsonObject("session").get("key").asString)
            val st = OperationForm.run(svc.client(), byName.getValue("runtime.status"), OperationForm.build(byName.getValue("runtime.status"), emptyMap(), svc.ws).args!!)
            assertTrue(st.ok)
            assertEquals("w4", st.resultObject().get("ws").asString)
        }
        assertEquals("FORM-1", bg { svc.refreshNow() }.currentSession)
    }

    fun testTheToolWindowPanelBuildsAndRenders() {
        svc.bind("w5")
        bg { svc.startSession("P-1", "panel") }
        val panel = DeliberatorPanel(project)
        try {
            val deadline = System.currentTimeMillis() + 60_000
            while (svc.model.fetchedAt == null && System.currentTimeMillis() < deadline) {
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); Thread.sleep(50)
            }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            panel.render(bg { svc.refreshNow() })
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
        }
    }
}
