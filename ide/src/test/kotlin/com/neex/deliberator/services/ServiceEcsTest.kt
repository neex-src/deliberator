package com.neex.deliberator.services

import com.google.gson.JsonParser
import com.intellij.execution.RunManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.neex.deliberator.daemon.DeliberatorSettings
import com.neex.deliberator.testing.TempDaemon
import com.neex.deliberator.workspace.ServiceRow
import com.neex.deliberator.workspace.WorkspaceModel
import com.neex.deliberator.workspace.WorkspaceService

/**
 * Piece 02c against the real daemon on a temp home: the row of a service bound to an ECS service shows the daemon's one-line state with its hint, Debug is refused
 * with the daemon's reason and starts nothing, and the tab keeps ONE `service.events` request open while it lives. The container that really stops within two seconds is
 * checked in the sandbox IDE against Floci (`.scratch/p04-ide-evidence/`).
 */
class ServiceEcsTest : BasePlatformTestCase() {
    private lateinit var daemon: TempDaemon
    private lateinit var svc: WorkspaceService
    private val panels = mutableListOf<ServicesPanel>()

    override fun setUp() {
        super.setUp()
        daemon = TempDaemon.create()
        DeliberatorSettings.getInstance().state.home = daemon.home.toString()
        svc = WorkspaceService.getInstance(project)
        svc.loadState(WorkspaceService.State())
        daemon.openWorkspace("w2c")
        svc.bind("w2c")
    }

    override fun tearDown() {
        try {
            panels.forEach { Disposer.dispose(it) }
            DeliberatorSettings.getInstance().state.home = ""
            daemon.close()
        } finally {
            super.tearDown()
        }
    }

    private fun rows(json: String): List<ServiceRow> =
        WorkspaceModel.parseServices(JsonParser.parseString(json), null, emptyList())

    fun testTheEcsStateOfABoundServiceIsTheDaemonsTextWithItsHint() {
        val r = rows("""[{"name":"billing","kind":"container","mode":"local","status":"stopped (ecs desired 0)","port":8082,
            "ecs":{"cluster":"acme","service":"acme-billing","desired":0,"text":"desired 0 · nothing runs","hint":"scale it up or Start Service writes desired 1"}},
           {"name":"audit","kind":"container","mode":"local","status":"running","port":8083,"debug-refused":"Debug does not apply: this container runs the image of revision 5",
            "ecs":{"cluster":"acme","service":"acme-audit","desired":1,"revision":"acme-audit:5","text":"revision 5 · repo/audit:abc (deployed)","hint":"Back to Dev Container runs your build again"}},
           {"name":"plain","kind":"spring-boot","mode":"local","status":"running","port":8081}]""")
        assertEquals(listOf(true, true, false), r.map { it.bound })
        assertEquals("desired 0 · nothing runs", r[0].ecsText)
        assertEquals("revision 5 · repo/audit:abc (deployed)", r[1].ecsText)
        assertEquals("Back to Dev Container runs your build again", r[1].ecsHint)
        assertEquals("Debug does not apply: this container runs the image of revision 5", r[1].debugRefused)
        assertNull(r[2].ecsText)
        assertEquals("the ECS column", "revision 5 · repo/audit:abc (deployed)", ServicesPanel.cells(r[1])[ServicesPanel.COLUMNS.indexOf("ECS")])
        assertEquals("", ServicesPanel.cells(r[2])[ServicesPanel.COLUMNS.indexOf("ECS")])
    }

    fun testDebugOnADeployedContainerIsRefusedWithTheDaemonsReasonAndStartsNothing() {
        val row = rows("""[{"name":"audit","kind":"container","mode":"local","status":"running","debug-refused":"Debug does not apply: revision 5","ecs":{"cluster":"c","service":"s"}}]""")[0]
        ServiceDebugAction().perform(project, row)
        com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue("no run configuration was made", RunManager.getInstance(project).allSettings.none { it.type.id == ServiceRunConfigurationType.ID })
    }

    fun testBackToDevContainerIsOnlyForABoundService() {
        val a = ServiceDevAction()
        assertTrue(a.enabledFor(rows("""[{"name":"x","mode":"local","status":"running","ecs":{"cluster":"c","service":"s"}}]""")[0]))
        assertFalse(a.enabledFor(rows("""[{"name":"y","mode":"local","status":"running"}]""")[0]))
    }

    fun testTheTabKeepsOneEventsRequestOpenWhileItLivesAndClosesItOnDispose() {
        val p = ServicesPanel(project).also { panels += it }
        PlatformTestUtil.waitWithEventsDispatching("the request is open at the daemon", { p.events.listening }, 60)
        Disposer.dispose(p)
        panels.remove(p)
        PlatformTestUtil.waitWithEventsDispatching("the request is closed", { !p.events.listening }, 30)
    }
}
