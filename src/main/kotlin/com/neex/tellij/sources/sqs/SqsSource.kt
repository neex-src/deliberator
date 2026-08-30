package com.neex.tellij.sources.sqs

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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException
import software.amazon.awssdk.services.sqs.model.SqsException
import java.net.URI
import java.time.Duration

/**
 * `### sqs <queue>` sends the block body. The second events impl, next to Kafka on purpose. With
 * `endpoint=` (ElasticMQ, LocalStack) and static `accessKey`/`secretKey` it needs no AWS account;
 * without them the SDK's default credential chain applies. The HTTP client is passed explicitly
 * so nothing is looked up through ServiceLoader inside the plugin classloader.
 */
class SqsSource : SourceKind {
    override val id: String = "sqs"

    override val directives = listOf(
        DirectiveSpec("queue", "queue name or URL, when the block name is a connection name"),
        DirectiveSpec("header", "name=value message attribute (repeatable)"),
        DirectiveSpec("group", "MessageGroupId for FIFO queues"),
        DirectiveSpec("dedup", "MessageDeduplicationId for FIFO queues"),
        DirectiveSpec("assert", "facts: queue, messageId, md5, elapsed", listOf("messageId matches /^[0-9a-f-]+$/")),
    )
    override val connectOptions = listOf(
        DirectiveSpec("endpoint", "override for ElasticMQ / LocalStack", listOf("http://localhost:9324")),
        DirectiveSpec("region", "AWS region", listOf("us-east-1", "eu-central-1", "eu-west-1")),
        DirectiveSpec("accessKey", "static credentials (any value for ElasticMQ)", listOf("x")),
        DirectiveSpec("secretKey", "static credentials (any value for ElasticMQ)", listOf("x")),
        DirectiveSpec("timeout", "connect timeout", listOf("5s")),
    )

    override fun connect(decl: ConnectDecl, ctx: ConnectContext): Connection = SqsConnection(
        decl,
        endpoint = decl.option("endpoint"),
        region = decl.option("region") ?: "us-east-1",
        accessKey = decl.option("accessKey"),
        secretKey = decl.option("secretKey"),
        timeoutMs = Durations.parse(decl.option("timeout"), 5_000),
    )

    override fun step(req: StepRequest, conn: Connection): Step {
        val queue = req.target ?: req.directive("queue") ?: throw StepError("no queue", "### sqs <queue>, or # @queue <queue>")
        if (req.body.isBlank()) throw StepError("empty message", "put the message body under the ### sqs line")
        val attrs = req.directives("header").associate { h ->
            val i = h.indexOf('=')
            if (i <= 0) throw StepError("bad header '$h'", "# @header name=value")
            h.substring(0, i).trim() to h.substring(i + 1).trim()
        }
        return SqsSend(conn as SqsConnection, queue, req.body, attrs, req.directive("group"), req.directive("dedup"))
    }
}

class SqsConnection(
    override val decl: ConnectDecl,
    val endpoint: String?,
    private val region: String,
    private val accessKey: String?,
    private val secretKey: String?,
    private val timeoutMs: Long,
) : Connection {
    private var client: SqsClient? = null
    private val queueUrls = HashMap<String, String>()

    @Synchronized
    fun client(): SqsClient {
        client?.let { return it }
        val builder = SqsClient.builder()
            .region(Region.of(region))
            .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofMillis(timeoutMs)).socketTimeout(Duration.ofMillis(timeoutMs * 4)))
        if (accessKey != null && secretKey != null) {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        } else if (endpoint != null) {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("x", "x")))
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build())
        }
        endpoint?.let { builder.endpointOverride(URI.create(it)) }
        return builder.build().also { client = it }
    }

    @Synchronized
    fun queueUrl(name: String): String = queueUrls.getOrPut(name) {
        if (name.startsWith("http://") || name.startsWith("https://")) name
        else client().getQueueUrl { it.queueName(name) }.queueUrl()
    }

    override fun close() {
        runCatching { client?.close() }
        client = null
    }
}

class SqsSend(
    private val conn: SqsConnection,
    private val queue: String,
    private val body: String,
    private val attrs: Map<String, String>,
    private val group: String?,
    private val dedup: String?,
) : Step {
    override fun run(ctx: RunContext, out: ResultSink) {
        val where = conn.endpoint ?: "AWS"
        try {
            val url = conn.queueUrl(queue)
            val resp = conn.client().sendMessage { b ->
                b.queueUrl(url).messageBody(body)
                if (attrs.isNotEmpty()) b.messageAttributes(attrs.mapValues { MessageAttributeValue.builder().dataType("String").stringValue(it.value).build() })
                group?.let { b.messageGroupId(it) }
                dedup?.let { b.messageDeduplicationId(it) }
            }
            out.appendLine("sent queue=$queue messageId=${resp.messageId()} md5=${resp.md5OfMessageBody()} bytes=${body.toByteArray().size}" +
                (if (attrs.isNotEmpty()) " attributes=${attrs.keys.joinToString(",")}" else ""))
            out.fact("queue", queue); out.fact("messageId", resp.messageId()); out.fact("md5", resp.md5OfMessageBody())
            out.done("sent")
        } catch (e: QueueDoesNotExistException) {
            throw StepError("queue '$queue' does not exist at $where", "the demo consumer creates its queues; or create it: aws sqs create-queue --queue-name $queue")
        } catch (e: SqsException) {
            throw StepError("SQS refused: ${e.awsErrorDetails()?.errorMessage() ?: e.message?.lineSequence()?.first()}", "check region/credentials on the @connect sqs line")
        } catch (e: SdkClientException) {
            throw StepError("cannot reach $where: ${e.message?.lineSequence()?.first()}", "is ElasticMQ up? demo/up.sh sqs")
        }
    }
}
