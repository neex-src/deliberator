package com.neex.tellij.sources.logs

import com.neex.tellij.model.ConnectDecl
import com.neex.tellij.spi.ConnectContext
import com.neex.tellij.spi.DirectiveSpec
import com.neex.tellij.spi.Connection
import com.neex.tellij.spi.Durations
import com.neex.tellij.spi.ResultSink
import com.neex.tellij.spi.RunContext
import com.neex.tellij.spi.SourceKind
import com.neex.tellij.spi.Step
import com.neex.tellij.spi.StepError
import com.neex.tellij.spi.StepRequest
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * `### logs <connection>` streams matching lines into the buffer for `# @for` seconds. Two
 * connection shapes on purpose — `file=<path>` and `podman=<container>` — to prove the seam is
 * real: a Splunk/CloudWatch tail is one more [Connection] subclass here.
 */
class LogsSource : SourceKind {
    override val id: String = "logs"

    override val directives = listOf(
        DirectiveSpec("filter", "substring or /regex/ a line must contain; repeat to AND", listOf("{{orderId}}")),
        DirectiveSpec("for", "how long to tail", listOf("5s", "10s", "30s", "1m")),
        DirectiveSpec("limit", "max lines kept in the result", listOf("100", "500", "5000")),
    )
    override val connectOptions = listOf(
        DirectiveSpec("file", "path of the log file (relative to this workbench)", listOf("logs/app.log")),
        DirectiveSpec("podman", "container name to `podman logs -f`"),
    )

    override fun connect(decl: ConnectDecl, ctx: ConnectContext): Connection {
        decl.option("file")?.let { return FileLogConnection(decl, ctx.resolve(it)) }
        decl.option("podman")?.let { return PodmanLogConnection(decl, it) }
        throw StepError("logs connection needs file=<path> or podman=<container>", "# @connect logs app file=/var/log/app.log")
    }

    override fun step(req: StepRequest, conn: Connection): Step {
        val filter = LogFilter.parse(req.directives("filter"))
        val windowMs = Durations.parse(req.directive("for"), DEFAULT_WINDOW_MS)
        return when (conn) {
            is FileLogConnection -> FileTailStep(conn.path, filter, windowMs)
            is PodmanLogConnection -> PodmanTailStep(conn.container, filter, windowMs)
            else -> throw StepError("unsupported connection ${conn.javaClass.simpleName}")
        }
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 10_000L
        fun windowText(ms: Long): String = if (ms % 1000 == 0L) "${ms / 1000} s" else "$ms ms"
    }
}

class FileLogConnection(override val decl: ConnectDecl, val path: Path) : Connection
class PodmanLogConnection(override val decl: ConnectDecl, val container: String) : Connection

/** Polls the file every 200 ms for the window; armed offset means nothing before the run leaks in. */
class FileTailStep(path: Path, filter: LogFilter, private val windowMs: Long) : Step {
    private val tail = FileTail(path, filter)
    override val concurrent: Boolean get() = true

    override fun arm(ctx: RunContext) = tail.arm()

    override fun run(ctx: RunContext, out: ResultSink) {
        val deadline = System.currentTimeMillis() + windowMs
        var matched = 0
        while (true) {
            for (line in tail.poll()) { out.appendLine(line); matched++ }
            if (ctx.isCancelled || System.currentTimeMillis() >= deadline) break
            ctx.sleep(minOf(200L, maxOf(1L, deadline - System.currentTimeMillis())))
        }
        if (ctx.isCancelled) return // the runner marks the section "stopped"
        out.done("$matched lines · window ${LogsSource.windowText(windowMs)}")
    }
}

/**
 * `podman logs --since <arm time> -f <container>`; a reader thread feeds a queue so the step can
 * watch the clock and the cancel flag, and the process is killed on the way out.
 */
class PodmanTailStep(private val container: String, private val filter: LogFilter, private val windowMs: Long) : Step {
    private var since: Instant = Instant.now()
    override val concurrent: Boolean get() = true

    override fun arm(ctx: RunContext) {
        since = Instant.now()
    }

    override fun run(ctx: RunContext, out: ResultSink) {
        val process = try {
            ProcessBuilder("podman", "logs", "--since", DateTimeFormatter.ISO_INSTANT.format(since), "-f", container)
                .redirectErrorStream(true).start()
        } catch (e: Exception) {
            throw StepError("cannot start podman: ${e.message}", "is podman on the PATH of the IDE process?")
        }
        val queue = LinkedBlockingQueue<String>()
        val reader = Thread({
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { queue.put(it) } }
        }, "tellij-podman-logs").apply { isDaemon = true; start() }
        val deadline = System.currentTimeMillis() + windowMs
        var matched = 0
        var lastRaw: String? = null
        val grouper = EntryGrouper(filter)
        fun emit(lines: List<String>) { for (l in lines) { out.appendLine(l); matched++ } }
        try {
            while (!ctx.isCancelled) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                val line = queue.poll(minOf(100L, left), TimeUnit.MILLISECONDS)
                if (line != null) {
                    lastRaw = line
                    emit(grouper.feed(line))
                } else if (!process.isAlive && queue.isEmpty()) {
                    // The container stopping ends the follow with 0; anything else is podman complaining.
                    if (process.exitValue() != 0) {
                        throw StepError("podman logs exited with ${process.exitValue()}: ${lastRaw ?: ""}".trim(), "podman ps --filter name=$container")
                    }
                    break
                } else {
                    emit(grouper.flush())
                }
            }
            if (!ctx.isCancelled) emit(grouper.flush())
        } finally {
            process.destroyForcibly()
            reader.join(1000)
        }
        if (ctx.isCancelled) return // the runner marks the section "stopped"
        out.done("$matched lines · window ${LogsSource.windowText(windowMs)}")
    }
}
