package com.neex.tellij.engine

import com.neex.tellij.sources.logs.EntryGrouper
import com.neex.tellij.sources.logs.FileTail
import com.neex.tellij.sources.logs.FileTailStep
import com.neex.tellij.sources.logs.LogFilter
import com.neex.tellij.sources.logs.PodmanTailStep
import com.neex.tellij.spi.StepError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE

class LogTailTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun append(name: String, text: String) = Files.writeString(tmp.root.toPath().resolve(name), text, CREATE, APPEND)

    @Test
    fun onlyLinesAfterArmAndPartialLines() {
        append("a.log", "old line\n")
        val tail = FileTail(tmp.root.toPath().resolve("a.log"))
        tail.arm()
        assertEquals(emptyList<String>(), tail.poll())
        append("a.log", "new 1\nnew 2\npart")
        // "new 2" may still get continuation lines; it completes on the idle poll.
        assertEquals(listOf("new 1"), tail.poll())
        assertEquals(listOf("new 2"), tail.poll())
        assertEquals(emptyList<String>(), tail.poll())
        append("a.log", "ial\r\n")
        assertEquals(emptyList<String>(), tail.poll())
        assertEquals(listOf("partial"), tail.poll())
    }

    @Test
    fun missingFileThenCreatedAndTruncation() {
        val p = tmp.root.toPath().resolve("later.log")
        val tail = FileTail(p)
        tail.arm()
        assertEquals(emptyList<String>(), tail.poll())
        append("later.log", "first line, long enough\n")
        tail.poll()
        assertEquals(listOf("first line, long enough"), tail.poll())
        // Rotation/truncation is detected by the file shrinking (size-only heuristic, v1).
        Files.writeString(p, "rotated\n")
        tail.poll()
        assertEquals(listOf("rotated"), tail.poll())
    }

    @Test
    fun indentedContinuationLinesTravelWithTheirHeader() {
        append("g.log", "")
        val tail = FileTail(tmp.root.toPath().resolve("g.log"), LogFilter.parse(listOf("ord-1")))
        tail.arm()
        append("g.log", "LOG: execute: insert into orders\n\tvalues ('ord-1')\n\ton conflict do nothing\nException: boom ord-1\n\tat a.b(C.kt:1)\nunrelated\n\tat still.unrelated\n")
        assertEquals(listOf("LOG: execute: insert into orders", "\tvalues ('ord-1')", "\ton conflict do nothing", "Exception: boom ord-1", "\tat a.b(C.kt:1)"), tail.poll())
        assertEquals(emptyList<String>(), tail.poll())
        val g = EntryGrouper(LogFilter.ALL)
        assertEquals(emptyList<String>(), g.feed("a"))
        assertEquals(listOf("a"), g.feed("b"))
        assertEquals(listOf("b"), g.flush())
        assertEquals(emptyList<String>(), g.flush())
        // Postgres puts a prepared statement's parameters on a DETAIL line with its own timestamp.
        val pg = EntryGrouper(LogFilter.parse(listOf("ord-9")))
        assertEquals(emptyList<String>(), pg.feed("2026-08-28 14:43:41.905 UTC [82] LOG:  execute <unnamed>: insert into orders values (\$1)"))
        assertEquals(emptyList<String>(), pg.feed("2026-08-28 14:43:41.905 UTC [82] DETAIL:  parameters: \$1 = 'ord-9'"))
        assertEquals(2, pg.feed("2026-08-28 14:43:42.000 UTC [83] LOG:  statement: select 1").size)
        assertEquals(emptyList<String>(), pg.flush())
    }

    @Test
    fun filtersSubstringAndRegexAreAnded() {
        val f = LogFilter.parse(listOf("ord-1", "/received|stored/"))
        assertTrue(f.matches("x received ord-1"))
        assertTrue(!f.matches("x received ord-2"))
        assertTrue(!f.matches("x dropped ord-1"))
        try { LogFilter.parse(listOf("/(/")); org.junit.Assert.fail() } catch (e: StepError) { assertTrue(e.message!!.startsWith("bad regex")) }
    }

    @Test
    fun stepStreamsWithinWindowAndStopsOnCancel() {
        val p = tmp.root.toPath().resolve("s.log")
        val step = FileTailStep(p, LogFilter.parse(listOf("keep")), 600)
        val ctx = TestContext()
        val sink = MemorySink("s")
        step.arm(ctx)
        val writer = Thread { repeat(4) { append("s.log", "keep $it\ndrop $it\n"); Thread.sleep(100) } }.apply { start() }
        val t0 = System.currentTimeMillis()
        step.run(ctx, sink)
        writer.join()
        assertTrue(System.currentTimeMillis() - t0 >= 600)
        assertEquals(listOf("keep 0", "keep 1", "keep 2", "keep 3"), sink.lines)
        assertEquals("done", sink.terminal)

        val slow = FileTailStep(p, LogFilter.ALL, 60_000)
        val ctx2 = TestContext()
        Thread { Thread.sleep(200); ctx2.flag.set(true) }.start()
        val t1 = System.currentTimeMillis()
        val cancelledSink = MemorySink("x")
        slow.run(ctx2, cancelledSink)
        assertTrue(System.currentTimeMillis() - t1 < 5_000)
        assertEquals("a cancelled tail leaves the terminal state to the runner", null, cancelledSink.terminal)
    }

    @Test
    fun podmanTailFollowsAContainer() {
        val podman = runCatching { ProcessBuilder("podman", "info").redirectErrorStream(true).start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue("podman not available", podman)
        val name = "tellij-test-echo"
        ProcessBuilder("podman", "rm", "-f", name).start().waitFor()
        val started = ProcessBuilder("podman", "run", "-d", "--name", name, "docker.io/library/alpine:3.20", "sh", "-c",
            "sleep 1; i=0; while true; do echo \"tick \$i\"; i=\$((i+1)); sleep 0.2; done").redirectErrorStream(true).start().waitFor() == 0
        assumeTrue("could not start container", started)
        try {
            val step = PodmanTailStep(name, LogFilter.parse(listOf("tick")), 2500)
            val ctx = TestContext()
            val sink = MemorySink("p")
            step.arm(ctx)
            step.run(ctx, sink)
            assertTrue(sink.lines.toString(), sink.lines.size >= 3)
            assertTrue(sink.lines.first(), sink.lines.first().startsWith("tick "))

            val missing = PodmanTailStep("tellij-no-such-container", LogFilter.ALL, 3000)
            try { missing.run(TestContext(), MemorySink("m")); org.junit.Assert.fail() } catch (e: StepError) { assertTrue(e.message, e.message!!.startsWith("podman logs exited")) }
        } finally {
            ProcessBuilder("podman", "rm", "-f", name).start().waitFor()
        }
    }
}
