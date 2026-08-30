package com.neex.tellij.sources.kafka

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
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import java.time.Duration
import java.util.Properties
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * `### kafka <topic>` publishes the block body. A real producer by default; `simulate=true` on the
 * `@connect` line keeps the offline mode (no broker, per-session offsets, optional `latency=`).
 * The producer is created lazily on the worker thread, whose context classloader is the plugin's —
 * Kafka resolves serializers and reporters reflectively.
 */
class KafkaSource : SourceKind {
    override val id: String = "kafka"

    override val directives = listOf(
        DirectiveSpec("key", "message key ({{orderId}} keeps one order on one partition)"),
        DirectiveSpec("header", "name=value record header (repeatable)"),
        DirectiveSpec("topic", "topic, when the block name is a connection name"),
        DirectiveSpec("assert", "facts: topic, partition, offset, key, elapsed", listOf("offset >= 0", "partition == 0")),
    )
    override val connectOptions = listOf(
        DirectiveSpec("bootstrap", "host:port[,host:port]", listOf("localhost:9092")),
        DirectiveSpec("simulate", "true = no broker, pretend to send", listOf("true")),
        DirectiveSpec("latency", "simulated send delay", listOf("300ms", "1s")),
        DirectiveSpec("timeout", "max.block.ms — fail fast without a broker", listOf("5s")),
        DirectiveSpec("producer.", "any producer config, e.g. producer.acks=1"),
    )

    override fun connect(decl: ConnectDecl, ctx: ConnectContext): Connection {
        if (decl.option("simulate") == "true") return SimulatedKafkaConnection(decl)
        val bootstrap = decl.option("bootstrap") ?: throw StepError("kafka connection needs bootstrap=host:port", "# @connect kafka local bootstrap=localhost:9092")
        return RealKafkaConnection(decl, bootstrap, Durations.parse(decl.option("timeout"), 5_000))
    }

    override fun step(req: StepRequest, conn: Connection): Step {
        val topic = req.target ?: req.directive("topic")
            ?: throw StepError("no topic", "### kafka <topic>, or # @topic <topic>")
        if (req.body.isBlank()) throw StepError("empty payload", "put the message body under the ### kafka line")
        val key = req.directive("key")
        val headers = req.directives("header").map { h ->
            val i = h.indexOf('=')
            if (i <= 0) throw StepError("bad header '$h'", "# @header name=value")
            h.substring(0, i).trim() to h.substring(i + 1).trim()
        }
        val timeoutMs = Durations.parse(req.directive("timeout"), 10_000)
        return when (conn) {
            is SimulatedKafkaConnection -> SimulatedSend(conn, topic, key, req.body)
            is RealKafkaConnection -> KafkaSend(conn, topic, key, headers, req.body, timeoutMs)
            else -> throw StepError("unsupported connection ${conn.javaClass.simpleName}")
        }
    }
}

class RealKafkaConnection(override val decl: ConnectDecl, val bootstrap: String, private val blockMs: Long) : Connection {
    private var producer: KafkaProducer<String, ByteArray>? = null

    @Synchronized
    fun producer(): KafkaProducer<String, ByteArray> {
        producer?.let { return it }
        val props = Properties()
        props[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = bootstrap
        props[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        props[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = ByteArraySerializer::class.java
        props[ProducerConfig.CLIENT_ID_CONFIG] = "tellij-" + java.util.UUID.randomUUID().toString().substring(0, 8)
        props[ProducerConfig.ACKS_CONFIG] = "all"
        props[ProducerConfig.MAX_BLOCK_MS_CONFIG] = blockMs
        props[ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG] = blockMs.toInt()
        props[ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG] = (blockMs * 2).toInt()
        props[ProducerConfig.RETRIES_CONFIG] = 1
        decl.options.filterKeys { it.startsWith("producer.") }.forEach { (k, v) -> props[k.removePrefix("producer.")] = v }
        return try {
            KafkaProducer<String, ByteArray>(props).also { producer = it }
        } catch (e: Exception) {
            throw StepError("cannot create producer: ${e.message}", "check the @connect kafka options")
        }
    }

    override fun close() {
        runCatching { producer?.close(Duration.ofSeconds(2)) }
        producer = null
    }
}

class KafkaSend(
    private val conn: RealKafkaConnection,
    private val topic: String,
    private val key: String?,
    private val headers: List<Pair<String, String>>,
    private val body: String,
    private val timeoutMs: Long,
) : Step {
    override fun run(ctx: RunContext, out: ResultSink) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val record = ProducerRecord<String, ByteArray>(topic, null, key, bytes, headers.map { RecordHeader(it.first, it.second.toByteArray()) })
        val md = try {
            conn.producer().send(record).get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            throw StepError("cannot send: ${cause.javaClass.simpleName}: ${cause.message?.lineSequence()?.first()}", "is Kafka up at ${conn.bootstrap}? demo/up.sh kafka")
        } catch (e: TimeoutException) {
            throw StepError("no acknowledgement from ${conn.bootstrap} within ${timeoutMs} ms", "is Kafka up? demo/up.sh kafka")
        }
        out.appendLine("sent topic=${md.topic()} partition=${md.partition()} offset=${md.offset()}" +
            (key?.let { " key=$it" } ?: "") + " bytes=${bytes.size}" +
            (if (headers.isNotEmpty()) " headers=${headers.joinToString(",") { it.first }}" else ""))
        out.fact("topic", md.topic()); out.fact("partition", md.partition()); out.fact("offset", md.offset()); out.fact("key", key)
        out.done("sent")
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
        val offset = conn.nextOffset(topic)
        out.appendLine("simulated send topic=$topic partition=0 offset=$offset" +
            (key?.let { " key=$it" } ?: "") + " bytes=$bytes")
        out.fact("topic", topic); out.fact("partition", 0); out.fact("offset", offset); out.fact("key", key)
        out.done("simulated")
    }
}
