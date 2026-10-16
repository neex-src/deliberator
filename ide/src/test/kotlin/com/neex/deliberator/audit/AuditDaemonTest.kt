package com.neex.deliberator.audit

import com.google.gson.JsonObject
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.daemon.DaemonLauncher
import com.neex.deliberator.testing.TempDaemon
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * The independent audit of M2, odd inputs at the daemon level (no platform): a home on a path with a space, a home too long for
 * a unix socket, `TempDaemon.close()` racing a run, the daemon stopped by hand in the middle of a run, a newer daemon.
 * Plain JUnit, real jar, throwaway homes below `ide/build/th`.
 */
class AuditDaemonTest {
    private val homes = ArrayList<TempDaemon>()

    @After fun tearDown() { homes.forEach { it.close() } }

    private fun home(name: String? = null): TempDaemon = (if (name == null) TempDaemon.create() else TempDaemon.createNamed(name)).also { homes += it }

    private fun args(vararg kv: Pair<String, String>) = JsonObject().apply { kv.forEach { (k, v) -> addProperty(k, v) } }

    private fun runStarted(d: TempDaemon, scn: String, key: String): Pair<com.neex.deliberator.daemon.DaemonClient.Call, String> {
        val client = d.client
        assertTrue(client.call("session.start", args("key" to key)).ok)
        val id = CompletableFuture<String>()
        val call = client.start("scenario.run", args("path" to scn)) { if (it.name == "run.started") id.complete(it.json.get("run-id").asString) }
        return call to id.get(20, TimeUnit.SECONDS)
    }

    @Test fun aHomeWithASpaceInItsPathWorksEndToEnd() {
        val d = home("h s")
        assertTrue(d.home.toString().contains(" "))
        val c = d.connect()
        assertTrue(c.started)
        assertEquals(d.home.toString(), c.hello.home)
        assertTrue(c.hello.endpoint, c.hello.endpoint.startsWith("unix://" + d.home))
        assertEquals(d.lockPid(), c.hello.pid)
        // a scenario that lives below the spaced home runs, and its run directory (also below it) holds result.txt
        val scn = d.scenario("my scn.scn", "### wait pause\n# @for 100ms\n")
        d.client.call("session.start", args("key" to "SP-1"))
        val t = d.client.call("scenario.run", args("path" to scn))
        assertTrue(t.raw, t.ok)
        val result = Path.of(t.resultObject().getAsJsonObject("paths").get("result").asString)
        assertTrue(result.toString(), result.toString().contains("h s") && Files.isRegularFile(result))
        // a second connect reuses it, and the exact-home predicate still recognises the process
        assertFalse(d.connect().started)
        assertEquals(1, TempDaemon.survivors(d.home).size)
        d.close()
        assertTrue(TempDaemon.survivors(d.home).isEmpty())
        assertFalse(Files.exists(d.home))
    }

    @Test fun aHomeTooLongForAUnixSocketFallsBackToTcpAndStillWorks() {
        val d = home("l".repeat(90))
        val c = d.connect()
        assertTrue(c.hello.endpoint, c.hello.endpoint.startsWith("tcp://"))
        d.client.call("session.start", args("key" to "LONG-1"))
        val t = d.client.call("scenario.run", args("path" to d.scenario("w.scn", "### wait pause\n# @for 50ms\n")))
        assertTrue(t.raw, t.ok)
    }

    @Test fun closeWhileARunIsInFlightEndsTheRunTheStreamAndTheProcess() {
        val d = home()
        val (call, _) = runStarted(d, d.scenario("w.scn", "### wait pause\n# @for 60s\n"), "RACE-1")
        val t0 = System.nanoTime()
        d.close()
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("AUDIT close() with a 60 s run in flight: $ms ms")
        assertTrue("close() took $ms ms", ms < 30_000)
        assertTrue("a daemon survived close()", TempDaemon.survivors(d.home).isEmpty())
        assertFalse("the home is still there", Files.exists(d.home))
        // the stream ended one way or the other (a terminal or daemon/closed), never hangs
        try { call.terminal.get(15, TimeUnit.SECONDS) } catch (e: ExecutionException) { assertEquals("daemon/closed", (e.cause as DaemonException).type) }
        d.close() // idempotent
    }

    /** What the client sees when `dlb daemon stop` (here: `stopDaemon`) comes while a run is in flight; the IDE-level consequence is in AuditRunTest. */
    @Test fun aDaemonStoppedByHandMidRunClosesTheStreamAndLeavesACancelledResultTxt() {
        val d = home()
        val (call, runId) = runStarted(d, d.scenario("w.scn", "### wait pause\n# @for 60s\n"), "STOP-1")
        val st = d.launcher.stopDaemon(d.home)
        assertTrue(st.running && st.gone)
        assertEquals("the stop answer names the cancelled run", 1, st.runs)
        val e = try { call.terminal.get(15, TimeUnit.SECONDS); null } catch (x: ExecutionException) { x.cause as DaemonException }
        assertNotNull("the run's stream has no terminal answer, only a closed connection", e)
        assertEquals("daemon/closed", e!!.type)
        val results = Files.walk(d.home).use { s -> s.filter { it.fileName.toString() == "result.txt" && it.toString().contains(runId) }.toList() }
        assertEquals("the daemon finalised the run on disk: $results", 1, results.size)
        assertTrue(Files.readString(results[0]).contains("RUN cancelled"))
    }

    @Test fun aNewerDaemonIsNotKilledByAnOlderPluginAndTheErrorSaysWhy() {
        val d = home()
        val newer = TempDaemon.jarWithVersion("9.9.9", d.home)
        val t0 = System.nanoTime()
        val running = TempDaemon.launcherFor(newer).connectCompatible(d.home)
        println("AUDIT start of a re-zipped jar: ${(System.nanoTime() - t0) / 1_000_000} ms")
        val e = try { d.launcher.connectCompatible(d.home); null } catch (x: DaemonException) { x }
        assertEquals("daemon/incompatible", e!!.type)
        assertNotNull("the error carries a hint a user can act on", e.hint)
        assertTrue(e.hint!!, e.hint!!.contains("dlb daemon stop"))
        assertTrue(ProcessHandle.of(running.hello.pid).get().isAlive)
    }

    @Test fun aKilledDaemonLeavesAStaleEndpointAndTheNextConnectStartsAFreshOne() {
        val d = home()
        val first = d.connect()
        ProcessHandle.of(first.hello.pid).get().also { it.destroyForcibly() }.onExit().get(10, TimeUnit.SECONDS)
        assertTrue("the endpoint file of the dead daemon is still there", Files.exists(d.launcher.endpointFile(d.home)))
        val second = d.connect()
        assertTrue(second.started)
        assertTrue(second.hello.pid != first.hello.pid)
        assertEquals(1, TempDaemon.survivors(d.home).size)
    }

    @Test fun sixConnectsRacingOnAColdHomeEndWithOneDaemon() {
        val d = home()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        try {
            val results = (1..6).map { pool.submit<Long> { d.connect().hello.pid } }.map { it.get(90, TimeUnit.SECONDS) }
            assertEquals("all callers talk to the same daemon: $results", 1, results.toSet().size)
            // every caller started its own JVM (nothing was up yet); the losers of the lock must leave on their own
            val t0 = System.nanoTime()
            val seen = ArrayList<Int>()
            while (TempDaemon.survivors(d.home).size > 1 && System.nanoTime() - t0 < 60_000_000_000L) { seen += TempDaemon.survivors(d.home).size; Thread.sleep(500) }
            println("AUDIT-RACE: losers gone after ${(System.nanoTime() - t0) / 1_000_000} ms (survivor counts seen: $seen)")
            assertEquals(1, TempDaemon.survivors(d.home).size)
            assertEquals(results.first(), d.lockPid())
        } finally { pool.shutdownNow() }
    }

    @Test fun aJavaHomeWithoutJavaIsATypedErrorWithTheDaemonOutput() {
        val d = home()
        val l = DaemonLauncher("/nonexistent/java-home", TempDaemon.shippedJar(), emptyList(), startTimeoutMs = 3_000)
        val e = try { l.connectCompatible(d.home); null } catch (x: DaemonException) { x }
        assertEquals("daemon/unreachable", e!!.type)
    }

    @Test fun aJarThatIsNotAJarIsATypedError() {
        val d = home()
        val bad = d.home.resolve("not-a.jar").also { Files.writeString(it, "this is not a zip") }
        val l = DaemonLauncher(System.getProperty("java.home"), bad, emptyList(), startTimeoutMs = 4_000)
        val e = try { l.connectCompatible(d.home); null } catch (x: DaemonException) { x }
        assertEquals("daemon/unreachable", e!!.type)
        assertTrue(e.message!!, e.message!!.contains("corrupt") || e.message!!.contains("jarfile") || e.message!!.contains("did not come up"))
    }
}
