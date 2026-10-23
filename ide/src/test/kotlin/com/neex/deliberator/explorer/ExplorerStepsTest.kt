package com.neex.deliberator.explorer

import com.google.gson.JsonParser
import com.neex.deliberator.scn.AwsResources
import com.neex.deliberator.scn.LiveResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorerStepsTest {
    @Test fun a_queue_is_peeked_not_consumed() {
        val s = ExplorerSteps.step("sqs", LiveResource("estate-inbox", "http://h/000/estate-inbox"))!!
        assertTrue(s.startsWith("### aws sqs.ReceiveMessage estate-inbox\n"))
        val body = JsonParser.parseString(s.substringAfter('\n')).asJsonObject
        assertEquals("http://h/000/estate-inbox", body.get("QueueUrl").asString)
        assertEquals(0, body.get("VisibilityTimeout").asInt)
    }

    @Test fun secrets_show_metadata_and_parameters_stay_encrypted() {
        assertTrue(ExplorerSteps.step("secretsmanager", LiveResource("estate/api-key", null))!!.contains("secretsmanager.DescribeSecret"))
        val p = ExplorerSteps.step("ssm", LiveResource("/estate/x", "String"))!!
        assertTrue(p.contains("\"WithDecryption\": false"))
        assertTrue(p.startsWith("### aws ssm.GetParameter -estate-x\n") || p.startsWith("### aws ssm.GetParameter estate-x\n") || p.startsWith("### aws ssm.GetParameter x\n"))
    }

    @Test fun every_kind_has_a_step_and_unknown_kinds_none() {
        ExplorerSteps.KINDS.forEach { assertTrue(it.kind, ExplorerSteps.step(it.kind, LiveResource("n", "arn:x:y:z"))!!.startsWith("### aws ${it.verb} ")) }
        assertEquals(null, ExplorerSteps.step("nope", LiveResource("n", null)))
    }

    @Test fun discover_result_parses() {
        val r = JsonParser.parseString("""{"resources":{"sqs":[{"name":"q","ref":"u"}],"s3":[{"name":"b"}]},"errors":{}}""").asJsonObject
        val m = AwsResources.parse(r)
        assertEquals(LiveResource("q", "u"), m["sqs"]!![0])
        assertEquals(LiveResource("b", null), m["s3"]!![0])
        assertEquals("sqs", AwsResources.forMember("sqs", "QueueUrl")!!.first)
        assertEquals("ssm", AwsResources.forMember("ssm", "Name")!!.first)
        assertEquals(null, AwsResources.forMember("sns", "Name"))
    }
}
