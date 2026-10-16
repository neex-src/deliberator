package com.neex.deliberator.daemon

import com.google.gson.JsonObject
import com.neex.deliberator.testing.TempDaemon
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Layer 2 of `docs/plans/m2-test.md`: the client and the lifecycle against the real jar on temp homes, no mocks. */
class DaemonLifecycleTest {
    private lateinit var d: TempDaemon

    @Before fun setUp() { d = TempDaemon.create() }
    @After fun tearDown() { d.close() }

    private fun args(vararg kv: Pair<String, String>) = JsonObject().apply { kv.forEach { (k, v) -> addProperty(k, v) } }

    private fun waitScenario(duration: String) = d.scenario("w.scn", "### wait pause\n# @for $duration\n")

    @Test fun startsTheShippedJarOnTheTestJavaHomeAndSaysHello() {
        val defaultHome = Path.of(System.getProperty("user.home"), ".local", "share", "deliberator")
        val before = Files.exists(defaultHome) to (if (Files.exists(defaultHome)) Files.getLastModifiedTime(defaultHome) else null)
        val c = d.connect()
        assertTrue(c.started)
        assertFalse(c.restarted)
        assertEquals(true, c.hello.compatible)
        assertEquals(d.launcher.jarVersion(), c.hello.version)
        assertEquals(d.home.toString(), c.hello.home)
        assertEquals(d.lockPid(), c.hello.pid)
        val cmd = ProcessHandle.of(c.hello.pid).get().info().command().orElse("")
        assertEquals(System.getProperty("java.home") + "/bin/java", cmd)
        println("LIVE: daemon ${c.hello.version} pid ${c.hello.pid} runs on $cmd endpoint ${c.hello.endpoint}")
        assertTrue(c.hello.endpoint.startsWith("unix://" + d.home))
        // nothing outside the temp home: the default home was neither created nor touched
        val after = Files.exists(defaultHome) to (if (Files.exists(defaultHome)) Files.getLastModifiedTime(defaultHome) else null)
        assertEquals(before, after)
    }

    @Test fun aRunningDaemonIsReused() {
        val first = d.connect()
        val second = d.connect()
        assertTrue(first.started)
        assertFalse(second.started)
        assertEquals(first.hello.pid, second.hello.pid)
    }

    @Test fun aRunStreamsEventsInOrderThenOneTerminal() {
        val client = d.client
        assertTrue(client.call("session.start", args("key" to "J-1")).ok)
        val names = CopyOnWriteArrayList<String>()
        val t = client.call("scenario.run", args("path" to waitScenario("200ms"))) { names += it.name }
        assertTrue(t.raw, t.ok)
        assertEquals("ok", t.resultObject().get("status").asString)
        assertEquals("run.started", names.first())
        assertEquals("run.finished", names.last())
        assertTrue(names.indexOf("step.started") < names.indexOf("step.finished"))
    }

    @Test fun cancelThroughTheOpEndsTheStreamWithATerminal() {
        val client = d.client
        client.call("session.start", args("key" to "J-2"))
        val runId = java.util.concurrent.CompletableFuture<String>()
        val call = client.start("scenario.run", args("path" to waitScenario("30s"))) {
            if (it.name == "run.started") runId.complete(it.json.get("run-id").asString)
        }
        val id = runId.get(10, TimeUnit.SECONDS)
        val cancel = client.call("scenario.cancel", args("run-id" to id))
        assertTrue(cancel.raw, cancel.ok)
        val t = call.terminal.get(10, TimeUnit.SECONDS)
        assertEquals("cancelled", t.resultObject().get("status").asString)
    }

    @Test fun closingTheConnectionCancelsTheRunOnTheDaemon() {
        val client = d.client
        client.call("session.start", args("key" to "J-3"))
        val started = java.util.concurrent.CompletableFuture<Boolean>()
        val call = client.start("scenario.run", args("path" to waitScenario("30s"))) { if (it.name == "run.started") started.complete(true) }
        started.get(10, TimeUnit.SECONDS)
        assertEquals(1, client.hello().runs)
        call.cancel()
        assertTrue(call.terminal.isCancelled)
        val deadline = System.currentTimeMillis() + 10_000
        while (client.hello().runs != 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(0, client.hello().runs)
    }

    @Test fun anOlderDaemonIsReplacedOnceByTheShippedJar() {
        val old = d.connect()
        val newer = TempDaemon.jarWithVersion("9.9.9", d.home)
        val c = TempDaemon.launcherFor(newer).connectCompatible(d.home)
        assertTrue(c.restarted)
        assertEquals("9.9.9", c.hello.version)
        assertNotEquals(old.hello.pid, c.hello.pid)
        assertFalse(ProcessHandle.of(old.hello.pid).map { it.isAlive }.orElse(false))
        assertEquals(1, d.log().count { it.contains("daemon.stop") })
        // asking again changes nothing
        val again = TempDaemon.launcherFor(newer).connectCompatible(d.home)
        assertFalse(again.restarted)
        assertEquals(c.hello.pid, again.hello.pid)
        assertEquals(1, d.log().count { it.contains("daemon.stop") })
    }

    @Test fun aNewerDaemonIsAnErrorAndNotKilled() {
        val newer = TempDaemon.jarWithVersion("9.9.9", d.home)
        val running = TempDaemon.launcherFor(newer).connectCompatible(d.home)
        try {
            d.launcher.connectCompatible(d.home)
            fail("expected daemon/incompatible")
        } catch (e: DaemonException) {
            assertEquals("daemon/incompatible", e.type)
            assertTrue(e.message!!, e.message!!.contains("newer"))
        }
        assertTrue(ProcessHandle.of(running.hello.pid).get().isAlive)
        assertEquals(0, d.log().count { it.contains("daemon.stop") })
    }

    @Test fun runsInFlightBlockTheReplacement() {
        val client = d.client
        client.call("session.start", args("key" to "J-4"))
        val started = java.util.concurrent.CompletableFuture<Boolean>()
        val call = client.start("scenario.run", args("path" to waitScenario("30s"))) { if (it.name == "run.started") started.complete(true) }
        started.get(10, TimeUnit.SECONDS)
        val pid = client.hello().pid
        val newer = TempDaemon.jarWithVersion("9.9.9", d.home)
        try {
            TempDaemon.launcherFor(newer).connectCompatible(d.home)
            fail("expected daemon/incompatible")
        } catch (e: DaemonException) {
            assertEquals("daemon/incompatible", e.type)
            assertTrue(e.message!!, e.message!!.contains("in flight"))
        }
        assertTrue(ProcessHandle.of(pid).get().isAlive)
        call.cancel()
    }

    @Test fun missingJarIsATypedError() {
        val l = DaemonLauncher(System.getProperty("java.home"), d.home.resolve("nope.jar"))
        try {
            l.connectCompatible(d.home)
            fail("expected daemon/no-jar")
        } catch (e: DaemonException) {
            assertEquals("daemon/no-jar", e.type)
            assertNotNull(e.hint)
        }
    }

    @Test fun anUnreachableEndpointFailsTheCallWithATypedError() {
        try {
            DaemonClient("unix://" + d.home.resolve("nobody.sock")).hello()
            fail("expected daemon/unreachable")
        } catch (e: DaemonException) {
            assertEquals("daemon/unreachable", e.type)
        }
    }
}
