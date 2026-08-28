package com.neex.tellij.sources.kafka

import com.neex.tellij.model.ConnectDecl
import com.neex.tellij.spi.Connection
import com.neex.tellij.spi.Durations
import com.neex.tellij.spi.ResultSink
import com.neex.tellij.spi.RunContext
import com.neex.tellij.spi.SourceKind
import com.neex.tellij.spi.Step
import com.neex.tellij.spi.StepError
import com.neex.tellij.spi.StepRequest
import com.neex.tellij.vars.Vars
import java.util.concurrent.atomic.AtomicLong

/**
 * `### kafka <topic>` publishes the block body. `simulate=true` on the `@connect` line makes the
 * connection pretend: no broker, a per-session offset counter, optional `latency=300ms` so the
 * streaming/cancel path can be watched. The real producer lands in feature/events on this same
 * class — the workbench file never changes.
 */
class KafkaSource : SourceKind {
    override val id: String = "kafka"

    override fun connect(decl: ConnectDecl, vars: Vars): Connection {
        if (decl.option("simulate") == "true") return SimulatedKafkaConnection(decl)
        throw StepError("real Kafka producer is not built yet", "add simulate=true to the @connect kafka line")
    }

    override fun step(req: StepRequest, conn: Connection): Step {
        val topic = req.target ?: req.directive("topic")
            ?: throw StepError("no topic", "### kafka <topic>, or # @topic <topic>")
        if (req.body.isBlank()) throw StepError("empty payload", "put the message body under the ### kafka line")
        val key = req.directive("key")
        return when (conn) {
            is SimulatedKafkaConnection -> SimulatedSend(conn, topic, key, req.body)
            else -> throw StepError("unsupported connection ${conn.javaClass.simpleName}")
        }
    }
}

class SimulatedKafkaConnection(override val decl: ConnectDecl) : Connection {
    private val offsets = HashMap<String, AtomicLong>()
    val latencyMs: Long = Durations.parse(decl.option("latency"), 0)
    fun nextOffset(topic: String): Long = offsets.getOrPut(topic) { AtomicLong(0) }.getAndIncrement()
}

class SimulatedSend(private val conn: SimulatedKafkaConnection, private val topic: String, private val key: String?, private val body: String) : Step {
    override fun run(ctx: RunContext, out: ResultSink) {
        if (conn.latencyMs > 0) {
            ctx.sleep(conn.latencyMs)
            ctx.checkCancelled()
        }
        val bytes = body.toByteArray(Charsets.UTF_8).size
        out.appendLine("simulated send topic=$topic partition=0 offset=${conn.nextOffset(topic)}" +
            (key?.let { " key=$it" } ?: "") + " bytes=$bytes")
        out.done("simulated")
    }
}
