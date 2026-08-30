package com.neex.tellij.engine

import com.neex.tellij.asserts.AssertExpr
import com.neex.tellij.asserts.Facts
import com.neex.tellij.spi.ResultSink

/**
 * Sits between a step and its section when the block has `# @assert` lines: collects the facts
 * the step publishes, and when the step reports `done` it evaluates every assertion, appends the
 * ✓/✗ lines and turns `done` into `failed` if any check failed. A failed assertion is a result,
 * not an error — the runner keeps going.
 */
class AssertingSink(private val delegate: ResultSink, private val asserts: List<AssertExpr>) : ResultSink {
    private val facts = LinkedHashMap<String, Any?>()
    @Volatile private var startedAt = System.currentTimeMillis()
    @Volatile private var finished = false

    override fun started() { startedAt = System.currentTimeMillis(); delegate.started() }
    override fun appendLine(line: CharSequence) = delegate.appendLine(line)
    override fun appendText(text: CharSequence) = delegate.appendText(text)

    @Synchronized
    override fun fact(name: String, value: Any?) { facts[name] = value }

    @Synchronized
    override fun done(summary: String?) {
        if (finished) return
        finished = true
        facts.putIfAbsent("elapsed", System.currentTimeMillis() - startedAt)
        val results = asserts.map { it.eval(Facts(facts)) }
        results.forEach { delegate.appendLine(it.line) }
        val failed = results.count { !it.ok }
        if (failed == 0) delegate.done(summary)
        else delegate.failed(listOfNotNull(summary, "FAILED $failed/${results.size}").joinToString(" · "))
    }

    override fun failed(summary: String) { if (!finished) { finished = true; delegate.failed(summary) } }
    override fun fail(message: String, hint: String?) { finished = true; delegate.fail(message, hint) }
    override fun skipped(reason: String) { finished = true; delegate.skipped(reason) }
    override fun stopped() { finished = true; delegate.stopped() }
}
