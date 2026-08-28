package com.neex.tellij.demo

import com.google.gson.JsonParser
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import kotlin.concurrent.thread

/**
 * The service under test, minimal on purpose: Kafka `orders` → insert a row; SQS `payments` →
 * update the row's status. Every step is one log line with the orderId in it, because that is
 * what the workbench filters on.
 */
object Config {
    val bootstrap: String = env("KAFKA_BOOTSTRAP", "localhost:9092")
    val topic: String = env("KAFKA_TOPIC", "orders")
    val sqsEndpoint: String = env("SQS_ENDPOINT", "http://localhost:9324")
    val sqsQueue: String = env("SQS_QUEUE", "payments")
    val pgUrl: String = env("PG_URL", "jdbc:postgresql://localhost:5432/demo")
    val pgUser: String = env("PG_USER", "demo")
    val pgPassword: String = env("PG_PASSWORD", "demo")
    val logFile: String = env("LOG_FILE", "consumer.log")
    private fun env(k: String, d: String) = System.getenv(k)?.takeIf { it.isNotBlank() } ?: d
}

object Log {
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    private val file = File(Config.logFile)

    @Synchronized
    fun line(msg: String) {
        val l = "${LocalDateTime.now().format(fmt)} $msg"
        println(l)
        runCatching { file.appendText(l + "\n") }
    }
}

object Store {
    private var conn: Connection? = null

    @Synchronized
    private fun jdbc(): Connection {
        conn?.takeIf { !it.isClosed }?.let { return it }
        val p = Properties().apply { put("user", Config.pgUser); put("password", Config.pgPassword); put("ApplicationName", "demo-consumer") }
        return DriverManager.getConnection(Config.pgUrl, p).also { conn = it }
    }

    fun insertOrder(id: String, customer: String?, amount: Double?, source: String): Int =
        jdbc().prepareStatement("insert into orders (id, customer, amount, source) values (?, ?, ?, ?) on conflict (id) do nothing").use {
            it.setString(1, id); it.setString(2, customer); if (amount != null) it.setDouble(3, amount) else it.setNull(3, java.sql.Types.NUMERIC); it.setString(4, source)
            it.executeUpdate()
        }

    fun updateStatus(id: String, status: String): Int =
        jdbc().prepareStatement("update orders set status = ? where id = ?").use {
            it.setString(1, status); it.setString(2, id)
            it.executeUpdate()
        }
}

/** One event from either bus. */
fun handle(source: String, body: String) {
    val json = try { JsonParser.parseString(body).asJsonObject } catch (e: Exception) {
        Log.line("$source received unparseable body=${body.take(80)}"); return
    }
    val id = json.get("orderId")?.asString ?: run { Log.line("$source received message without orderId"); return }
    val status = json.get("status")?.asString
    if (status != null) {
        Log.line("$source received orderId=$id status=$status")
        retry("update") { val n = Store.updateStatus(id, status); Log.line("updated orderId=$id status=$status pg=$n") }
    } else {
        val customer = json.get("customer")?.asString
        val amount = json.get("amount")?.asDouble
        Log.line("$source received orderId=$id customer=$customer amount=$amount")
        retry("insert") { val n = Store.insertOrder(id, customer, amount, source); Log.line("stored orderId=$id pg=$n source=$source") }
    }
}

fun retry(what: String, body: () -> Unit) {
    var attempt = 0
    while (true) {
        try { body(); return } catch (e: Exception) {
            if (++attempt >= 20) { Log.line("$what failed for good: ${e.message}"); return }
            Log.line("$what failed (${e.javaClass.simpleName}: ${e.message?.lineSequence()?.first()}), retrying")
            Thread.sleep(1000)
        }
    }
}

fun kafkaLoop() {
    val props = Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Config.bootstrap)
        put(ConsumerConfig.GROUP_ID_CONFIG, "demo-consumer")
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")
    }
    while (true) {
        try {
            KafkaConsumer<String, String>(props).use { c ->
                c.subscribe(listOf(Config.topic), object : ConsumerRebalanceListener {
                    override fun onPartitionsAssigned(parts: Collection<TopicPartition>) = Log.line("kafka assigned partitions=${parts.joinToString { "${it.topic()}-${it.partition()}" }}")
                    override fun onPartitionsRevoked(parts: Collection<TopicPartition>) {}
                })
                while (true) {
                    for (r in c.poll(Duration.ofMillis(500))) handle("kafka", r.value())
                }
            }
        } catch (e: Exception) {
            Log.line("kafka loop error: ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.first()} — reconnecting")
            Thread.sleep(2000)
        }
    }
}

fun sqsLoop() {
    val client = SqsClient.builder()
        .region(Region.US_EAST_1)
        .endpointOverride(URI.create(Config.sqsEndpoint))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("x", "x")))
        .httpClientBuilder(UrlConnectionHttpClient.builder())
        .build()
    var url: String? = null
    while (true) {
        try {
            if (url == null) {
                url = client.createQueue { it.queueName(Config.sqsQueue) }.queueUrl()
                Log.line("sqs polling queue=${Config.sqsQueue} url=$url")
            }
            val msgs = client.receiveMessage { it.queueUrl(url).waitTimeSeconds(5).maxNumberOfMessages(10) }.messages()
            for (m in msgs) {
                handle("sqs", m.body())
                client.deleteMessage { it.queueUrl(url).receiptHandle(m.receiptHandle()) }
            }
        } catch (e: Exception) {
            Log.line("sqs loop error: ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.first()} — retrying")
            url = null
            Thread.sleep(2000)
        }
    }
}

fun main() {
    Log.line("demo-consumer up kafka=${Config.bootstrap}/${Config.topic} sqs=${Config.sqsEndpoint}/${Config.sqsQueue} pg=${Config.pgUrl}")
    val k = thread(name = "kafka") { kafkaLoop() }
    thread(name = "sqs") { sqsLoop() }
    k.join()
}
