package com.neex.deliberator.daemon

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * The wire client: NDJSON over the daemon's endpoint (`unix:///...` or `tcp://127.0.0.1:p`, the one
 * line of `daemon/endpoint`). One request is one connection served by one virtual thread, so
 * `cancel-on-close` is exactly "close that connection" and no demultiplexing is needed (the daemon
 * serves any number of connections). Events reach `onEvent` on the request's own thread, in order;
 * the call completes with exactly one [DaemonTerminal], or fails with a [DaemonException]
 * (`daemon/closed` when the connection ends first, `daemon/unreachable` when it cannot be opened).
 * Callers update the UI themselves (`invokeLater`); nothing here touches the platform.
 */
class DaemonClient(val endpoint: String) {
    /** Epoch millis of the last request that could not reach the daemon (`daemon/unreachable`, `daemon/closed`); 0 when none. [DaemonService] re-checks after one. */
    @Volatile var unreachableAt: Long = 0; private set

    /** A request in flight. [terminal] completes once; [cancel] closes the connection, which cancels the request on the daemon. */
    class Call internal constructor(val id: String, val terminal: CompletableFuture<DaemonTerminal>, private val channel: () -> SocketChannel?) {
        fun cancel() {
            try { channel()?.close() } catch (_: IOException) { }
            terminal.cancel(false)
        }

        /** Blocks for the terminal; rethrows the [DaemonException] the call failed with. */
        fun await(): DaemonTerminal = try {
            terminal.get()
        } catch (e: ExecutionException) {
            throw (e.cause as? DaemonException ?: e)
        }

        /**
         * As [await], but gives up after [timeoutMs]: the request is cancelled (the connection closed) and a [DaemonException]
         * `daemon/timeout` is thrown. For the editor's paths (completion, annotator), which may wait a little but never hang.
         */
        fun await(timeoutMs: Long): DaemonTerminal = try {
            terminal.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            throw (e.cause as? DaemonException ?: e)
        } catch (e: java.util.concurrent.TimeoutException) {
            cancel()
            throw DaemonException("the daemon did not answer within $timeoutMs ms", "daemon/timeout")
        }
    }

    /** Sends one request and returns at once; [onEvent] is called for each event, [Call.terminal] completes at the end. */
    fun start(
        op: String,
        args: JsonObject = JsonObject(),
        cancelOnClose: Boolean = true,
        onEvent: (DaemonEvent) -> Unit = {},
    ): Call {
        val id = "ide-${ids.incrementAndGet()}"
        val future = CompletableFuture<DaemonTerminal>()
        val channel = java.util.concurrent.atomic.AtomicReference<SocketChannel?>()
        val call = Call(id, future) { channel.get() }
        threads.execute {
            try {
                val ch = open(endpoint)
                channel.set(ch)
                if (future.isCancelled) { ch.close(); return@execute }
                try {
                    val req = JsonObject().apply {
                        addProperty("id", id)
                        addProperty("op", op)
                        add("args", args)
                        if (cancelOnClose) addProperty("cancel-on-close", true)
                    }
                    writeLine(ch, req.toString())
                    val reader = BufferedReader(InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8))
                    while (true) {
                        val line = reader.readLine()
                            ?: throw DaemonException("the daemon closed the connection before answering $op", "daemon/closed")
                        if (line.isBlank()) continue
                        val m = JsonParser.parseString(line).asJsonObject
                        if (m.has("event")) {
                            onEvent(DaemonEvent(m.get("event").asString, m))
                        } else if (m.has("ok")) {
                            future.complete(DaemonTerminal(m.get("ok").asBoolean, m.get("result"), m.getAsJsonObject("error"), line))
                            return@execute
                        }
                    }
                } finally {
                    try { ch.close() } catch (_: IOException) { }
                }
            } catch (e: DaemonException) {
                if (e.type == "daemon/unreachable" || e.type == "daemon/closed") unreachableAt = System.currentTimeMillis()
                future.completeExceptionally(e)
            } catch (e: IOException) {
                if (!future.isCancelled) {
                    unreachableAt = System.currentTimeMillis()
                    future.completeExceptionally(
                        DaemonException("cannot talk to the daemon at $endpoint: ${e.message}", "daemon/unreachable", cause = e),
                    )
                }
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
        return call
    }

    /** Blocking convenience: one request, events to [onEvent], the terminal returned. Never call on the EDT. */
    fun call(op: String, args: JsonObject = JsonObject(), onEvent: (DaemonEvent) -> Unit = {}): DaemonTerminal =
        try {
            start(op, args, true, onEvent).await()
        } catch (e: CancellationException) {
            throw DaemonException("request $op cancelled", "daemon/cancelled")
        }

    /** [call] with a budget: `daemon/timeout` after [timeoutMs] (the request is cancelled). The editor's calls use it. */
    fun call(op: String, args: JsonObject, timeoutMs: Long): DaemonTerminal =
        try {
            start(op, args, true).await(timeoutMs)
        } catch (e: CancellationException) {
            throw DaemonException("request $op cancelled", "daemon/cancelled")
        }

    /** `hello {client "ide" version?}`; throws a [DaemonException] when the daemon answers an error. */
    fun hello(version: String? = null): Hello {
        val args = JsonObject().apply {
            addProperty("client", "ide")
            if (version != null) addProperty("version", version)
        }
        return Hello.of(call("hello", args).resultObject())
    }

    companion object {
        private val ids = AtomicLong()

        private val threads = Executors.newThreadPerTaskExecutor(
            ThreadFactory { r -> Thread.ofVirtual().name("deliberator-call").unstarted(r) },
        )

        /** Opens a connection to [endpoint]; throws a [DaemonException] (`daemon/unreachable`) when it is unusable or refused. */
        fun open(endpoint: String): SocketChannel = try {
            when {
                endpoint.startsWith("unix://") ->
                    SocketChannel.open(StandardProtocolFamily.UNIX).also { it.connect(UnixDomainSocketAddress.of(endpoint.removePrefix("unix://"))) }
                endpoint.startsWith("tcp://") -> {
                    val (h, p) = endpoint.removePrefix("tcp://").split(':')
                    SocketChannel.open().also { it.connect(InetSocketAddress(h, p.toInt())) }
                }
                else -> throw DaemonException("unknown endpoint $endpoint", "daemon/bad-endpoint")
            }
        } catch (e: IOException) {
            throw DaemonException("cannot connect to $endpoint: ${e.message}", "daemon/unreachable", cause = e)
        }

        private fun writeLine(ch: SocketChannel, line: String) {
            val buf = ByteBuffer.wrap((line + "\n").toByteArray(StandardCharsets.UTF_8))
            while (buf.hasRemaining()) ch.write(buf)
        }
    }
}
