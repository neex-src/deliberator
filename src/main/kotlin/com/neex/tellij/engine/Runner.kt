package com.neex.tellij.engine

import com.neex.tellij.model.Block
import com.neex.tellij.model.ConnectDecl
import com.neex.tellij.model.WorkbenchModel
import com.neex.tellij.spi.ConnectContext
import com.neex.tellij.spi.Connection
import com.neex.tellij.spi.Durations
import com.neex.tellij.spi.ResultSink
import com.neex.tellij.spi.RunCancelled
import com.neex.tellij.spi.RunContext
import com.neex.tellij.spi.SourceKind
import com.neex.tellij.spi.Step
import com.neex.tellij.spi.StepError
import com.neex.tellij.spi.StepRequest
import com.neex.tellij.vars.Vars
import java.util.concurrent.atomic.AtomicInteger

/** A block resolved to a step, or to the error that resolution produced (reported in its section). */
class PlanEntry(val block: Block, val step: Step?, val error: StepError?)

class Plan(val entries: List<PlanEntry>, val connections: List<Connection>)

/**
 * The pure execution engine: no threads of its own, no platform. Resolution is eager, so a typo in
 * a directive fails before anything is sent; execution is sequential top-down with concurrent
 * steps (log tails) armed first and spawned in place, and stops at the first failure like a test.
 */
object Runner {

    fun plan(text: CharSequence, model: WorkbenchModel, blocks: List<Block>, vars: Vars, baseDir: java.nio.file.Path?, resolve: (String) -> SourceKind?): Plan {
        val connections = LinkedHashMap<String, Connection>()
        val connectCtx = ConnectContext(vars, baseDir)
        val entries = blocks.map { block ->
            try {
                val kind = resolve(block.kind)
                    ?: throw StepError("unknown block kind '${block.kind}'", "a kind is one SourceKind class registered on com.neex.tellij.sourceKind")
                val (decl, target) = connectionFor(model, block)
                val conn = connections.getOrPut("${decl.kind}/${decl.name}") {
                    kind.connect(decl.copy(options = decl.options.mapValues { vars.substitute(it.value) }), connectCtx)
                }
                val body = block.body?.let { vars.substitute(it.substring(text)) } ?: ""
                PlanEntry(block, kind.step(StepRequest(block, body, target, vars), conn), null)
            } catch (e: StepError) {
                PlanEntry(block, null, e)
            } catch (e: Exception) {
                PlanEntry(block, null, StepError(describe(e)))
            }
        }
        return Plan(entries, connections.values.toList())
    }

    /**
     * Which `@connect` a block uses: `# @conn NAME` wins; then a block name that matches a connection
     * of that kind; otherwise the block name is the kind's target and the sole connection of that
     * kind is used.
     */
    fun connectionFor(model: WorkbenchModel, block: Block): Pair<ConnectDecl, String?> {
        val explicit = block.directive("conn")
        if (explicit != null) {
            val decl = model.connection(block.kind, explicit)
                ?: throw StepError("no @connect ${block.kind} named '$explicit'", "add: # @connect ${block.kind} $explicit …")
            return decl to block.name
        }
        block.name?.let { model.connection(block.kind, it) }?.let { return it to null }
        val candidates = model.connectionsOf(block.kind)
        return when (candidates.size) {
            1 -> candidates[0] to block.name
            0 -> throw StepError("no @connect ${block.kind} declared", "add to the header: # @connect ${block.kind} local …")
            else -> throw StepError("several ${block.kind} connections (${candidates.joinToString { it.name }})", "pick one with # @conn NAME")
        }
    }

    /**
     * Runs the plan on the calling thread; [spawn] provides threads for concurrent steps. Returns
     * when every step — including spawned ones — has finished or noticed the cancellation.
     */
    fun execute(plan: Plan, ctx: RunContext, sinkFor: (Block) -> ResultSink, spawn: (Runnable) -> Unit) {
        val sinks = plan.entries.associate { it.block.index to sinkFor(it.block) }
        fun sink(e: PlanEntry) = sinks.getValue(e.block.index)

        // Arm everything first so a tail never misses a line emitted by an earlier step.
        val armed = HashSet<Int>()
        for (e in plan.entries) {
            val step = e.step ?: continue
            try {
                step.arm(ctx)
                armed += e.block.index
            } catch (t: Throwable) {
                sink(e).fail(describe(t), (t as? StepError)?.hint)
            }
        }

        val running = AtomicInteger(0)
        var halted = false
        for (e in plan.entries) {
            val out = sink(e)
            val step = e.step
            if (e.error != null) { out.fail(e.error.message ?: "error", e.error.hint); halted = true; continue }
            if (step == null || e.block.index !in armed) { halted = true; continue }
            if (ctx.isCancelled) { out.stopped(); continue }
            if (halted) { out.skipped("earlier step failed"); continue }
            val delayMs = try { Durations.parse(e.block.directive("delay"), 0) } catch (err: StepError) { out.fail(err.message ?: "bad @delay", err.hint); halted = true; continue }
            if (step.concurrent) {
                running.incrementAndGet()
                spawn {
                    try {
                        runOne(step, ctx, out, delayMs)
                    } finally {
                        running.decrementAndGet()
                    }
                }
            } else if (!runOne(step, ctx, out, delayMs)) {
                halted = true
            }
        }
        // Concurrent steps observe ctx.isCancelled themselves; we only wait for them to notice.
        // A cancel interrupts this thread as well — that is not a reason to stop waiting.
        while (running.get() > 0) {
            try { Thread.sleep(25) } catch (e: InterruptedException) { /* keep waiting */ }
        }
        for (c in plan.connections) runCatching { c.close() }
    }

    /** true on success. */
    private fun runOne(step: Step, ctx: RunContext, out: ResultSink, delayMs: Long = 0): Boolean {
        return try {
            // `# @delay 1s`: give an eventually-consistent system a beat before asking it.
            if (delayMs > 0) { ctx.sleep(delayMs); ctx.checkCancelled() }
            out.started()
            step.run(ctx, out)
            // A step that returned because it noticed the cancel flag did not "finish".
            if (ctx.isCancelled) { out.stopped(); false } else { out.done(); true }
        } catch (e: RunCancelled) {
            out.stopped(); false
        } catch (e: InterruptedException) {
            out.stopped(); false
        } catch (e: StepError) {
            out.fail(e.message ?: "error", e.hint); false
        } catch (t: Throwable) {
            if (ctx.isCancelled) { out.stopped(); return false }
            out.fail(describe(t)); false
        }
    }

    fun describe(t: Throwable): String =
        if (t is StepError) t.message ?: "error"
        else "${t.javaClass.simpleName}: ${t.message ?: ""}".trimEnd(':', ' ')
}
