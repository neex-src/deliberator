package com.neex.deliberator.explorer

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.neex.deliberator.scn.LiveResource

/** What the explorer shows for a kind of resource and the read step that "opens" one of them. */
object ExplorerSteps {
    class KindInfo(val kind: String, val title: String, val verb: String, val request: (LiveResource) -> JsonObject)

    private fun obj(vararg kv: Pair<String, Any>) = JsonObject().also { o ->
        kv.forEach { (k, v) ->
            o.add(k, when (v) {
                is Number -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v)
                is List<*> -> JsonArray().also { a -> v.forEach { a.add(it.toString()) } }
                else -> JsonPrimitive(v.toString())
            })
        }
    }

    /** Read calls only: what is in a queue is peeked at (visibility timeout 0, nothing deleted), secrets show metadata, never a value. */
    val KINDS: List<KindInfo> = listOf(
        KindInfo("sqs", "Queues", "sqs.ReceiveMessage") { obj("QueueUrl" to (it.ref ?: it.name), "MaxNumberOfMessages" to 10, "VisibilityTimeout" to 0, "WaitTimeSeconds" to 0, "AttributeNames" to listOf("All"), "MessageAttributeNames" to listOf("All")) },
        KindInfo("dynamodb", "Tables", "dynamodb.Scan") { obj("TableName" to it.name, "Limit" to 25) },
        KindInfo("s3", "Buckets", "s3.ListObjectsV2") { obj("Bucket" to it.name) },
        KindInfo("sns", "Topics", "sns.ListSubscriptionsByTopic") { obj("TopicArn" to (it.ref ?: it.name)) },
        KindInfo("eventbridge", "Event buses", "eventbridge.ListRules") { obj("EventBusName" to it.name) },
        KindInfo("lambda", "Functions", "lambda.GetFunction") { obj("FunctionName" to it.name) },
        KindInfo("ssm", "Parameters", "ssm.GetParameter") { obj("Name" to it.name, "WithDecryption" to false) },
        KindInfo("secretsmanager", "Secrets (metadata)", "secretsmanager.DescribeSecret") { obj("SecretId" to it.name) },
        KindInfo("kinesis", "Streams", "kinesis.DescribeStream") { obj("StreamName" to it.name) },
        KindInfo("logs", "Log groups", "logs.DescribeLogStreams") { obj("logGroupName" to it.name, "orderBy" to "LastEventTime", "descending" to true, "limit" to 10) },
        KindInfo("ecs", "ECS clusters", "ecs.DescribeClusters") { obj("clusters" to listOf(it.ref ?: it.name)) },
        KindInfo("iam", "Roles", "iam.GetRole") { obj("RoleName" to it.name) },
        KindInfo("states", "State machines", "states.ListExecutions") { obj("stateMachineArn" to (it.ref ?: it.name)) },
    )

    private val PRETTY = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun info(kind: String) = KINDS.firstOrNull { it.kind == kind }

    /** The step that opens [r] of [kind], as `.scn` text (with the resource's name as the step name). */
    fun step(kind: String, r: LiveResource): String? {
        val k = info(kind) ?: return null
        val name = r.name.substringAfterLast('/').ifBlank { r.name }.replace(Regex("[^A-Za-z0-9._-]"), "-")
        return "### aws ${k.verb} $name\n${PRETTY.toJson(k.request(r))}\n"
    }

    /** The scenario file the explorer writes into: named per environment, so it is findable and survives (principle 4). */
    fun fileName(env: String) = "explore-$env.scn"

    fun header(env: String) = "# Written by the AWS explorer: one read step per resource you opened. Edit, rerun, keep.\n# @env $env\n"
}
