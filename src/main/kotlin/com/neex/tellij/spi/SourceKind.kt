package com.neex.tellij.spi

import com.neex.tellij.model.Block
import com.neex.tellij.model.ConnectDecl
import com.neex.tellij.vars.Vars

/**
 * The seam. A source kind (kafka, logs, sql, …) turns a `@connect` declaration into a [Connection]
 * and a block into a [Step]. Nothing in this package imports the platform: a source can be
 * developed and tested as plain Kotlin, and third-party plugins register one through the
 * `com.neex.tellij.sourceKind` extension point exactly like the built-ins do.
 */
interface SourceKind {
    /** The word after `###` and after `# @connect`. */
    val id: String

    /** Called at most once per session per `@connect` name; expensive clients may connect lazily. */
    fun connect(decl: ConnectDecl, vars: Vars): Connection

    /** Builds the runnable step; validate directives eagerly and throw [StepError] with a hint. */
    fun step(req: StepRequest, conn: Connection): Step
}

interface Connection : AutoCloseable {
    val decl: ConnectDecl
    override fun close() {}
}

/**
 * What a block asks for, with variables already applied to the body. [target] is the block's name
 * when it is not a connection name (`### kafka orders` → topic "orders").
 */
class StepRequest(val block: Block, val body: String, val target: String?, val vars: Vars) {
    fun directive(name: String): String? = block.directive(name)?.let { vars.substitute(it) }
    fun directives(name: String): List<String> = block.directives(name).map { vars.substitute(it) }
    fun require(name: String, hint: String): String = directive(name) ?: throw StepError("missing # @$name", hint)
}

interface Step {
    /** Runs before ANY step of the session starts — log tails capture their offset here. */
    fun arm(ctx: RunContext) {}

    /** Blocking. Must poll [RunContext.isCancelled] (or call [RunContext.checkCancelled]) in loops. */
    fun run(ctx: RunContext, out: ResultSink)

    /** Concurrent steps run on their own thread while the following sequential steps proceed. */
    val concurrent: Boolean get() = false
}

interface RunContext {
    val vars: Vars
    val isCancelled: Boolean
    fun checkCancelled() { if (isCancelled) throw RunCancelled() }
    /** Cooperative sleep that returns early on cancel. */
    fun sleep(ms: Long)
    /** Publish a value for later blocks (`@capture`); a no-op until captures land. */
    fun capture(name: String, value: String) {}
}

/** Where a step writes. Terminal calls are idempotent: the first of done/fail/skipped/stopped wins. */
interface ResultSink {
    /** The step is about to run — elapsed time counts from here, not from session start. */
    fun started() {}
    fun appendLine(line: CharSequence)
    fun appendText(text: CharSequence) = text.lineSequence().forEach { appendLine(it) }
    fun done(summary: String? = null)
    fun fail(message: String, hint: String? = null)
    fun skipped(reason: String)
    fun stopped()
}

class StepError(message: String, val hint: String? = null) : RuntimeException(message)

class RunCancelled : RuntimeException("cancelled") {
    override fun fillInStackTrace(): Throwable = this
}

/** `500ms`, `5s`, `2m`, or a bare number of milliseconds. */
object Durations {
    private val PATTERN = Regex("""^\s*(\d+(?:\.\d+)?)\s*(ms|s|m|h)?\s*$""")
    fun parse(value: String?, defaultMs: Long): Long {
        if (value == null) return defaultMs
        val m = PATTERN.matchEntire(value) ?: throw StepError("bad duration '$value'", "use 500ms, 5s or 2m")
        val n = m.groupValues[1].toDouble()
        return when (m.groupValues[2]) {
            "", "ms" -> n
            "s" -> n * 1000
            "m" -> n * 60_000
            "h" -> n * 3_600_000
            else -> n
        }.toLong()
    }
}
