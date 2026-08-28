package com.neex.tellij.engine

import com.neex.tellij.model.ConnectDecl
import com.neex.tellij.model.WorkbenchParser
import com.neex.tellij.spi.Connection
import com.neex.tellij.spi.ResultSink
import com.neex.tellij.spi.RunContext
import com.neex.tellij.spi.SourceKind
import com.neex.tellij.spi.Step
import com.neex.tellij.spi.StepError
import com.neex.tellij.spi.StepRequest
import com.neex.tellij.vars.Vars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Fake source: body "lines N" streams N lines, "fail X" throws, "tail" loops until cancelled. */
class FakeSource(override val id: String = "fake", val events: MutableList<String> = CopyOnWriteArrayList()) : SourceKind {
    override fun connect(decl: ConnectDecl, vars: Vars): Connection = object : Connection {
        override val decl = decl
        override fun close() { events += "close ${decl.name}" }
    }

    override fun step(req: StepRequest, conn: Connection): Step {
        val words = req.body.trim().split(" ")
        val name = req.block.name ?: "?"
        return when (words[0]) {
            "lines" -> object : Step {
                override fun arm(ctx: RunContext) { events += "arm $name" }
                override fun run(ctx: RunContext, out: ResultSink) {
                    events += "run $name"
                    repeat(words[1].toInt()) { ctx.checkCancelled(); out.appendLine("$name line $it"); ctx.sleep(words.getOrNull(2)?.toLong() ?: 0) }
                }
            }
            "fail" -> object : Step {
                override fun run(ctx: RunContext, out: ResultSink) { events += "run $name"; throw StepError(words.drop(1).joinToString(" "), "try harder") }
            }
            "tail" -> object : Step {
                override val concurrent = true
                override fun arm(ctx: RunContext) { events += "arm $name" }
                override fun run(ctx: RunContext, out: ResultSink) {
                    events += "run $name"
                    val until = System.currentTimeMillis() + (words.getOrNull(1)?.toLong() ?: 60_000)
                    while (!ctx.isCancelled && System.currentTimeMillis() < until) { out.appendLine("tick"); ctx.sleep(20) }
                    events += "end $name"
                }
            }
            "badstep" -> throw StepError("bad step", "fix it")
            else -> throw StepError("unknown fake body '${req.body}'")
        }
    }
}

class MemorySink(val name: String) : ResultSink {
    val lines = CopyOnWriteArrayList<String>()
    @Volatile var terminal: String? = null
    override fun appendLine(line: CharSequence) { lines += line.toString() }
    override fun done(summary: String?) { terminal = terminal ?: "done" }
    override fun fail(message: String, hint: String?) { terminal = terminal ?: "fail:$message:$hint" }
    override fun skipped(reason: String) { terminal = terminal ?: "skipped:$reason" }
    override fun stopped() { terminal = terminal ?: "stopped" }
}

class TestContext : RunContext {
    val flag = AtomicBoolean(false)
    override val vars = Vars(emptyMap())
    override val isCancelled get() = flag.get()
    override fun sleep(ms: Long) { val end = System.currentTimeMillis() + ms; while (!isCancelled && System.currentTimeMillis() < end) Thread.sleep(5) }
}

class RunnerTest {

    private fun run(text: String, source: FakeSource = FakeSource(), ctx: TestContext = TestContext(), before: (Map<String, MemorySink>) -> Unit = {}): Map<String, MemorySink> {
        val model = WorkbenchParser.parse(text)
        val plan = Runner.plan(text, model, model.blocks, Vars(emptyMap())) { if (it == source.id) source else null }
        val sinks = model.blocks.associate { (it.name ?: "?") to MemorySink(it.name ?: "?") }
        before(sinks)
        Runner.execute(plan, ctx, { sinks.getValue(it.name ?: "?") }) { r -> Thread(r).start() }
        return sinks
    }

    @Test
    fun sequentialOrderAndArmFirst() {
        val src = FakeSource()
        val sinks = run("# @connect fake f\n### fake a\nlines 2\n### fake b\nlines 1\n", src)
        assertEquals(listOf("arm a", "arm b", "run a", "run b", "close f"), src.events)
        assertEquals(listOf("a line 0", "a line 1"), sinks.getValue("a").lines)
        assertEquals("done", sinks.getValue("a").terminal)
        assertEquals("done", sinks.getValue("b").terminal)
    }

    @Test
    fun firstFailureSkipsLaterSequentialSteps() {
        val sinks = run("# @connect fake f\n### fake a\nfail boom\n### fake b\nlines 1\n")
        assertEquals("fail:boom:try harder", sinks.getValue("a").terminal)
        assertEquals("skipped:earlier step failed", sinks.getValue("b").terminal)
    }

    @Test
    fun planErrorsAreReportedPerBlock() {
        val sinks = run("# @connect fake f\n### fake a\nbadstep\n### nope b\nx\n")
        assertEquals("fail:bad step:fix it", sinks.getValue("a").terminal)
        assertTrue(sinks.getValue("b").terminal!!, sinks.getValue("b").terminal!!.startsWith("fail:unknown block kind 'nope'"))
        val noConn = run("### fake a\nlines 1\n")
        assertTrue(noConn.getValue("a").terminal!!, noConn.getValue("a").terminal!!.startsWith("fail:no @connect fake declared"))
    }

    @Test
    fun concurrentTailRunsAlongsideAndFinishesWithWindow() {
        val src = FakeSource()
        val sinks = run("# @connect fake f\n### fake t\ntail 150\n### fake a\nlines 1 50\n", src)
        assertEquals("done", sinks.getValue("t").terminal)
        assertEquals("done", sinks.getValue("a").terminal)
        assertTrue(sinks.getValue("t").lines.size > 2)
        // The tail was spawned before "a" ran (thread start order is not guaranteed) and outlived it.
        assertTrue(src.events.toString(), src.events.indexOf("end t") > src.events.indexOf("run a"))
        assertTrue(src.events.toString(), src.events.indexOf("close f") > src.events.indexOf("end t"))
    }

    @Test
    fun cancelStopsEverything() {
        val ctx = TestContext()
        val sinks = run("# @connect fake f\n### fake t\ntail\n### fake a\nlines 100 20\n", ctx = ctx) {
            Thread { Thread.sleep(150); ctx.flag.set(true) }.start()
        }
        assertEquals("stopped", sinks.getValue("t").terminal)
        assertTrue(sinks.getValue("a").lines.size < 100)
    }

    @Test
    fun connectionResolutionRules() {
        val m = WorkbenchParser.parse("# @connect k a\n# @connect k b\n### k a\n### k topic\n# @conn b\n### k topic\n")
        assertEquals("a" to null, Runner.connectionFor(m, m.blocks[0]).let { it.first.name to it.second })
        assertEquals("b" to "topic", Runner.connectionFor(m, m.blocks[1]).let { it.first.name to it.second })
        try { Runner.connectionFor(m, m.blocks[2]); org.junit.Assert.fail() } catch (e: StepError) { assertTrue(e.message!!.startsWith("several k connections")) }
    }
}
