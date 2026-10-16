package com.neex.deliberator.daemon

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.neex.deliberator.testing.TempDaemon
import java.nio.file.Files
import java.nio.file.Path

/** Decision 36: after `TempDaemon.close()` a late background `ensure()` cannot start a daemon, and no process of the home survives. */
class NoOrphanDaemonTest : BasePlatformTestCase() {
    override fun tearDown() {
        try { DeliberatorSettings.getInstance().state.home = "" } finally { super.tearDown() }
    }

    private fun ensureOnPooledThread(): DaemonConnection =
        ApplicationManager.getApplication().executeOnPooledThread<DaemonConnection> { DaemonService.getInstance().ensure() }.get()

    fun testALateWarmUpAfterCloseStartsNothing() {
        val t = TempDaemon.create()
        val home = t.home
        DeliberatorSettings.getInstance().state.home = home.toString()
        val c = ensureOnPooledThread()
        assertTrue(c.started)
        assertEquals(1, TempDaemon.survivors(home).size)

        t.close()
        assertTrue("daemon survived close", TempDaemon.survivors(home).isEmpty())

        // the settings still name the closed home, as they do while a background refresh is late
        // caught inside the pooled task: the platform logs (and the test framework fails on) exceptions that escape one
        val late = ApplicationManager.getApplication().executeOnPooledThread<Throwable?> {
            try { DaemonService.getInstance().ensure(); null } catch (e: Throwable) { e }
        }.get()
        assertEquals("daemon/home-closed", (late as DaemonException).type)
        assertTrue("a late ensure started a daemon", TempDaemon.survivors(home).isEmpty())
        assertFalse("a late ensure recreated the home", Files.exists(home))
    }

    fun testSweepPredicateMatchesOnlyTheExactHome() {
        val h = Path.of("/x/build/th/ab")
        val jar = "/java -jar /core/target/deliberatord.jar --home"
        assertTrue(TempDaemon.commandOwnsHome("$jar $h --idle-minutes 1", h))
        assertTrue(TempDaemon.commandOwnsHome("$jar $h", h))
        assertFalse(TempDaemon.commandOwnsHome("$jar ${h}x", h))
        assertFalse(TempDaemon.commandOwnsHome("/java -jar other.jar --home $h", h))
    }
}
