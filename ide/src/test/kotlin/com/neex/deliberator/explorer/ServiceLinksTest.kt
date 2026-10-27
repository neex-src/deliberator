package com.neex.deliberator.explorer

import com.google.gson.JsonParser
import com.neex.deliberator.services.FakeOps
import com.neex.deliberator.services.FakesPanel
import com.neex.deliberator.services.ServicesPanel
import com.neex.deliberator.workspace.WorkspaceModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M6.4 and the fakes' rows from the shapes `service.list` and `fake.list` answer: which service an ECS or log-group row belongs to, and how the Services tab shows them. */
class ServiceLinksTest {
    private val list = JsonParser.parseString("""[
        {"name":"a","kind":"spring-boot","port":18081,"container-port":8081,"image":"i","mode":"local","status":"running","container":"deliberator-w-service-a",
         "log-target":"log/service-a","log":{"container":"deliberator-w-service-a"}},
        {"name":"b","port":8082,"mode":"fake","fake":"b-fake","url":"http://x","status":"stopped","container":"deliberator-w-service-b",
         "log-target":"log/service-b","log":{"cloudwatch":"/estate/other-name"}},
        {"name":"c","mode":"external","url":"http://c","status":"stopped","container":"deliberator-w-service-c"}]""")
    private val rows = WorkspaceModel.parseServices(list, null, emptyList())

    @Test fun service_list_rows_carry_the_container_the_log_target_and_what_it_stands_for() {
        val a = rows[0]
        assertEquals("deliberator-w-service-a", a.container)
        assertEquals("log/service-a", a.logTarget)
        assertEquals("deliberator-w-service-a", a.logContainer)
        assertNull(a.logGroup)
        assertEquals("/estate/other-name", rows[1].logGroup)
        assertEquals("b-fake", rows[1].fake)
        assertNull(rows[2].logTarget)   // an environment without a log/ target for it
    }

    @Test fun a_log_group_belongs_to_the_service_whose_target_it_is_or_whose_name_it_carries() {
        assertEquals("b", ServiceLinks.forResource("logs", "/estate/other-name", rows)!!.service.name)       // the target's CloudWatch group
        assertEquals("a", ServiceLinks.forResource("logs", "/estate/service-a", rows)!!.service.name)        // named after the service
        assertNull(ServiceLinks.forResource("logs", "/aws/lambda/estate-logger", rows))
        assertNull(ServiceLinks.forResource("sqs", "service-a", rows))                                       // only ECS and log groups link
    }

    @Test fun an_ecs_row_belongs_to_the_service_it_is_named_after() {
        assertEquals("c", ServiceLinks.forResource("ecs", "service-c", rows)!!.service.name)
        assertEquals("a", ServiceLinks.forResource("ecs", "a", rows)!!.service.name)
        assertNull(ServiceLinks.forResource("ecs", "estate-cluster", rows))
        val t = ServiceLinks.forResource("logs", "/estate/service-a", rows)!!.text()
        assertEquals("service a · container deliberator-w-service-a · log/service-a", t)
    }

    @Test fun an_ecs_service_belongs_to_the_service_whose_container_it_runs() {
        // the estate's ECS service is named <prefix>-service-a and its task definition runs the container service-a (B31)
        assertEquals("a", ServiceLinks.forResource("ecs-services", "m6a/m6a-service-a", rows, listOf("service-a"))!!.service.name)
        assertEquals("b", ServiceLinks.forResource("ecs-services", "m6a/orders-api", rows, listOf("sidecar", "service-b"))!!.service.name)   // the container decides, not the name
        assertEquals("a", ServiceLinks.forResource("ecs-services", "m6a/m6a-service-a", rows)!!.service.name)                           // no containers known: the suffix
        assertEquals("a", ServiceLinks.forResource("ecs-taskdefs", "m6a-service-a:3", rows)!!.service.name)
        assertNull(ServiceLinks.forResource("ecs-services", "m6a/m6a-chain", rows, listOf("nginx")))
        assertNull(ServiceLinks.forResource("ecs-taskdefs", "m6a-service-aa:1", rows))
        val t = ServiceLinks.forResource("ecs-services", "m6a/m6a-service-a", rows, listOf("service-a"))!!.text()
        assertEquals("service a · container deliberator-w-service-a · log/service-a", t)
    }

    @Test fun aws_discover_services_come_through_with_their_containers_and_open_as_describe_steps() {
        val parsed = com.neex.deliberator.scn.AwsResources.parse(JsonParser.parseString("""{"resources":{
            "ecs":[{"name":"m6a","ref":"arn:aws:ecs:r:1:cluster/m6a"}],
            "ecs-services":[{"name":"m6a/m6a-service-a","ref":"arn:aws:ecs:r:1:service/m6a/m6a-service-a","cluster":"m6a","containers":["service-a"]}],
            "ecs-taskdefs":[{"name":"m6a-service-a:1","ref":"arn:aws:ecs:r:1:task-definition/m6a-service-a:1"}]}}""").asJsonObject)
        val svc = parsed["ecs-services"]!!.single()
        assertEquals(listOf("service-a"), svc.containers)
        assertEquals(emptyList<String>(), parsed["ecs"]!!.single().containers)
        val step = ExplorerSteps.step("ecs-services", svc)!!
        assertTrue(step, step.startsWith("### aws ecs.DescribeServices m6a-service-a") && step.contains("\"cluster\": \"m6a\"") && step.contains("\"m6a-service-a\""))
        assertTrue(ExplorerSteps.step("ecs-taskdefs", parsed["ecs-taskdefs"]!!.single())!!.contains("task-definition/m6a-service-a:1"))
    }

    @Test fun the_services_table_shows_the_fake_a_service_is_served_by() {
        assertEquals("fake (b-fake)", ServicesPanel.cells(rows[1])[1])
        assertEquals("external (http://c)", ServicesPanel.cells(rows[2])[1])
        assertEquals("local", ServicesPanel.cells(rows[0])[1])
    }

    @Test fun fakes_are_parsed_with_their_recordings() {
        val f = FakeOps.parse(JsonParser.parseString("""[{"name":"a","mode":"replay","port":18080,"url":"http://localhost:18080","container-url":"http://x:80","container":"c",
            "attached":[{"env":"local","service":"a"}],"upstream":"http://deliberator-w-service-a:8081","recording":"/s/S1/fakes/a/20261027-1","exchanges":3,"serving":"replay",
            "recordings":["20261027-1","20261027-2"]}]"""))
        assertEquals(1, f.size)
        assertEquals("replay", f[0].mode)
        assertEquals("20261027-1", f[0].recording)
        assertEquals(listOf("a"), f[0].attached)
        assertEquals(listOf("20261027-1", "20261027-2"), f[0].recordings)
        assertEquals(listOf("a", "replay", "replay", "http://deliberator-w-service-a:8081", "18080", "a"), FakesPanel.cells(f[0]).map { it.toString() })
        assertNotNull(FakeOps.parse(null))
    }
}
