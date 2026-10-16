package com.neex.deliberator.daemon

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.neex.deliberator.testing.TempDaemon

/** The plugin as the platform loads it: service, settings and the `Daemon status` action are registered; the service honours the home setting. */
class PluginRegistrationTest : BasePlatformTestCase() {
    private var temp: TempDaemon? = null

    override fun tearDown() {
        try {
            DeliberatorSettings.getInstance().state.home = ""
            temp?.close()
        } finally {
            super.tearDown()
        }
    }

    fun testActionAndServicesAreRegistered() {
        assertNotNull(ActionManager.getInstance().getAction("deliberator.daemon.status"))
        assertNotNull(DaemonService.getInstance())
        assertNotNull(DeliberatorSettings.getInstance())
    }

    fun testServiceUsesTheHomeFromTheSettingAndTheShippedJar() {
        val t = TempDaemon.create().also { temp = it }
        DeliberatorSettings.getInstance().state.home = t.home.toString()
        val svc = DaemonService.getInstance()
        assertEquals(t.home, svc.home())
        // ensure() refuses the EDT (it blocks): the test thread is the EDT here
        val c = ApplicationManager.getApplication().executeOnPooledThread<DaemonConnection> { svc.ensure() }.get()
        assertTrue(c.started)
        assertEquals(t.home.toString(), c.hello.home)
        assertEquals(true, c.hello.compatible)
        val again = ApplicationManager.getApplication().executeOnPooledThread<DaemonConnection> { svc.ensure() }.get()
        assertFalse(again.started)
        assertEquals(c.hello.pid, again.hello.pid)
    }
}
