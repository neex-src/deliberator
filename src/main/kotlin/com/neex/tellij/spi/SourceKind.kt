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

    /**
     * Called at most once per session per `@connect` name; expensive clients may connect lazily.
     * Option values already have `{{vars}}` applied.
     */
    fun connect(decl: ConnectDecl, ctx: ConnectContext): Connection

    /** Builds the runnable step; validate directives eagerly and throw [StepError] with a hint. */
    fun step(req: StepRequest, conn: Connection): Step

    /** A kind that works without any `@connect` line (http) returns an implicit declaration here. */
    fun defaultConnection(): ConnectDecl? = null

    /** The `# @directive`s a block of this kind understands — completion reads this. */
    val directives: List<DirectiveSpec> get() = emptyList()

    /** The `key=value` options of its `@connect` line. */
    val connectOptions: List<DirectiveSpec> get() = emptyList()
}

/** A directive or option: its name, a one-line hint, and the values worth offering. */
data class DirectiveSpec(val name: String, val hint: String, val values: List<String> = emptyList()) {
    companion object {
        /** Understood on every block, by the runner or every source. */
        val COMMON: List<DirectiveSpec> = listOf(
            DirectiveSpec("conn", "which @connect of this kind to use"),
            DirectiveSpec("delay", "wait before running this block", listOf("500ms", "1s", "2s", "5s")),
            DirectiveSpec("timeout", "give up after", listOf("1s", "5s", "10s", "30s")),
            DirectiveSpec("capture", "name = \$.json.path (http, mongo) or = column (sql) → {{name}} for later blocks"),
            DirectiveSpec("assert", "a check on this block's facts — repeatable; ✓/✗ under the result, FAILED n/m in the header, the run continues", listOf("elapsed < 500")),
        )
        val HEADER: List<DirectiveSpec> = listOf(
            DirectiveSpec("var", "name = value — {{name}} everywhere below; may use {{uuid}}, {{now}}, {{env.X}}"),
            DirectiveSpec("connect", "kind name key=value … — a connection for ### kind blocks"),
        )
    }
}

/** What a connection may need besides its options: the variables and the workbench file's directory. */
class ConnectContext(val vars: Vars, val baseDir: java.nio.file.Path?) {
    /** Resolves a possibly relative path against the workbench file's directory. */
    fun resolve(path: String): java.nio.file.Path {
        val p = java.nio.file.Path.of(path)
        return if (p.isAbsolute || baseDir == null) p else baseDir.resolve(p).normalize()
    }
}

interface Connection : AutoCloseable {
    val decl: ConnectDecl
    override fun close() {}
}

/**
 * What a block asks for, with variables already applied to the body. [target] is the block's name
 * when it is not a connection name (`### kafka orders` → topic "orders").
 */
class StepRequest(
    val block: Block,
    val body: String,
    val target: String?,
    val vars: Vars,
    /** Host-provided handles a source may need (the IDE project under "project"); empty in pure tests. */
    val context: Map<String, Any> = emptyMap(),
    /** The whole workbench, for sources that look at other declarations (`# @connect debug`). */
    val model: com.neex.tellij.model.WorkbenchModel? = null,
) {
    fun directive(name: String): String? = block.directive(name)?.let { vars.substitute(it) }
    fun directives(name: String): List<String> = block.directives(name).map { vars.substitute(it) }
    fun require(name: String, hint: String): String = directive(name) ?: throw StepError("missing # @$name", hint)
}

interface Step {
    /** Runs before ANY step of the session starts — log tails capture their offset here. */
    fun arm(ctx: RunContext) {}

    /**
     * Blocking. Must poll [RunContext.isCancelled] (or call [RunContext.checkCancelled]) in loops,
     * and must NOT call [ResultSink.done] after a cancel — the runner marks the section "stopped".
     */
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
    /** Publish a value for later blocks (`# @capture name = …`); they see it as `{{name}}`. */
    fun capture(name: String, value: String) {}
    /** Bumps on every capture; the runner re-resolves a block's body when it changed since planning. */
    val captureVersion: Int get() = 0
}

/** `# @capture name = expression` parsed once, evaluated by the source that knows the result shape. */
data class CaptureSpec(val name: String, val expr: String) {
    companion object {
        fun parse(directives: List<String>): List<CaptureSpec> = directives.map { d ->
            val i = d.indexOf('=')
            if (i <= 0) throw StepError("bad @capture '$d'", "# @capture name = \$.path.to.value")
            val name = d.substring(0, i).trim()
            if (!Regex("[A-Za-z_][\\w.\\-]*").matches(name)) throw StepError("bad capture name '$name'")
            CaptureSpec(name, d.substring(i + 1).trim())
        }
    }
}

/** Where a step writes. Terminal calls are idempotent: the first of done/fail/skipped/stopped wins. */
interface ResultSink {
    /** The step is about to run — elapsed time counts from here, not from session start. */
    fun started() {}
    fun appendLine(line: CharSequence)
    fun appendText(text: CharSequence) = text.lineSequence().forEach { appendLine(it) }
    fun done(summary: String? = null)
    /** `done`, but an assertion failed: the header says so; the run continues. */
    fun failed(summary: String) = done(summary)
    /** A value later `# @assert` lines can check (`status`, `rows`, `$` as a JsonElement, …). */
    fun fact(name: String, value: Any?) {}
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
