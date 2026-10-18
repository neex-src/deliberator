package com.neex.deliberator.scn

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AwsStepToolsTest {
    private fun node(json: String) = AwsNode.parse(JsonParser.parseString(json))!!
    private val sendMessage = node("""{"type":"structure","required":["QueueUrl","MessageBody"],"order":["QueueUrl","MessageBody","DelaySeconds","MessageAttributes"],
        "members":{"QueueUrl":{"type":"string"},"MessageBody":{"type":"string"},"DelaySeconds":{"type":"integer"},"MessageAttributes":{"type":"map","value":{"type":"structure"}}}}""")
    private val verbs = setOf("sqs.SendMessage", "sqs.ReceiveMessage", "ssm.GetParameter")

    @Test fun skeleton_holds_the_required_members() {
        assertEquals("{\n  \"QueueUrl\": \"\",\n  \"MessageBody\": \"\"\n}", AwsStepTools.skeleton(sendMessage))
        assertTrue(AwsStepTools.skeleton(sendMessage, all = true).contains("DelaySeconds"))
    }

    @Test fun cli_command_becomes_a_step() {
        val r = AwsStepTools.fromCli("aws sqs send-message --queue-url http://x/q --message-body 'hello world' --delay-seconds 5 --endpoint-url http://localhost:4566", verbs) { sendMessage }
        assertEquals("sqs.SendMessage", r.verb)
        assertEquals("http://x/q", r.request!!.get("QueueUrl").asString)
        assertEquals("hello world", r.request!!.get("MessageBody").asString)
        assertEquals(5, r.request!!.get("DelaySeconds").asInt)
        assertEquals(3, r.request!!.size())
        assertTrue(r.text()!!.startsWith("### aws sqs.SendMessage\n{"))
    }

    @Test fun json_values_pass_through_and_unknown_flags_are_reported() {
        val r = AwsStepTools.fromCli("""aws sqs send-message --queue-url q --message-body b --message-attributes '{"a":{"DataType":"String"}}' --bogus-flag 1""", verbs) { sendMessage }
        assertEquals("String", r.request!!.getAsJsonObject("MessageAttributes").getAsJsonObject("a").get("DataType").asString)
        assertEquals(listOf("--bogus-flag"), r.unknown)
        assertEquals(1, r.request!!.get("BogusFlag").asInt)
    }

    @Test fun unknown_operations_say_so() {
        val r = AwsStepTools.fromCli("aws sqs frobnicate", verbs) { null }
        assertNull(r.verb); assertTrue(r.error!!.contains("no operation sqs.frobnicate"))
        assertTrue(AwsStepTools.fromCli("aws", verbs) { null }.error!!.startsWith("expected"))
    }

    @Test fun shell_style_splitting() {
        assertEquals(listOf("aws", "a b", "c\"d", "e"), AwsStepTools.split("aws 'a b' \"c\\\"d\" e"))
        assertEquals(listOf("aws", "x", "y"), AwsStepTools.split("aws x \\\n y"))
    }
}
