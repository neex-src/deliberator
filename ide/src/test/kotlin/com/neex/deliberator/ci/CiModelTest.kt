package com.neex.deliberator.ci

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the CI view: the rows of `ci.runs`, the default action of a run, the jobs of a workflow file and the checkout directory of one. */
class CiModelTest {
    private fun run(status: String, conclusion: String?) = CiRun("1", "7", "ci", "t", "main", "abcdef0123", status, conclusion, "2026-10-10T10:00:00Z", null)

    @Test fun the_default_action_follows_the_state_of_the_run() {
        assertEquals(CiDefault.DIFF, Ci.defaultFor(run("completed", "failure")))
        assertEquals(CiDefault.DIFF, Ci.defaultFor(run("completed", "timed_out")))
        assertEquals(CiDefault.FOLLOW, Ci.defaultFor(run("in_progress", null)))
        assertEquals(CiDefault.FOLLOW, Ci.defaultFor(run("queued", null)))
        assertEquals(CiDefault.LOG, Ci.defaultFor(run("completed", "success")))
        assertEquals(CiDefault.LOG, Ci.defaultFor(run("completed", "cancelled")))
    }

    @Test fun runs_are_parsed_defensively_and_grouped_by_workflow() {
        val r = Ci.parseRuns(JsonParser.parseString("""{"repo":"o/r","path":"/x/runs.txt","total":3,"rows":[
            {"id":200,"number":20,"workflow":"ci","branch":"feat","sha":"aaaaaaaaaaaa","status":"completed","conclusion":"failure","created-at":"2026-10-10T10:00:00Z","title":"wip"},
            {"id":201,"workflow":"lint","status":"completed","conclusion":"success"},
            {"id":199,"number":19,"workflow":"ci","status":"completed","conclusion":"success"}]}""").asJsonObject)
        assertEquals("o/r", r.repo)
        assertEquals(3, r.total)
        assertEquals(mapOf("ci" to listOf("200", "199"), "lint" to listOf("201")), r.byWorkflow().mapValues { e -> e.value.map { it.id } })
        assertEquals("#20  failure  feat  aaaaaaa  2026-10-10 10:00  wip", r.rows[0].label())
        assertTrue(r.rows[0].red)
        assertEquals("#201  success", r.rows[1].label())
        assertEquals(CiRuns("", null, 0, emptyList()), Ci.parseRuns(JsonParser.parseString("{}").asJsonObject))
    }

    private val workflow = """
        name: ci   # the workflow
        on: [push]
        env:
          X: 1
        jobs:
          # first
          test:
            runs-on: ubuntu-latest
            steps:
              - run: make test
          "deploy-prod":
            needs: test
            steps:
              - run: ./deploy

          lint:   # last
            runs-on: ubuntu-latest
        permissions:
          contents: read
    """.trimIndent()

    @Test fun the_jobs_of_a_workflow_are_the_keys_under_jobs() {
        val jobs = WorkflowFiles.jobs(workflow)
        assertEquals(listOf("test", "deploy-prod", "lint"), jobs.map { it.id })
        assertEquals(listOf(6, 10, 15), jobs.map { it.line })
        assertEquals("test", WorkflowFiles.jobAt(jobs, 8)?.id)
        assertEquals("deploy-prod", WorkflowFiles.jobAt(jobs, 12)?.id)
        assertEquals("lint", WorkflowFiles.jobAt(jobs, 16)?.id)
        assertNull(WorkflowFiles.jobAt(jobs, 3))
        assertNull(WorkflowFiles.jobAt(jobs, 18))   // permissions: is not a job
        assertEquals(emptyList<WorkflowJob>(), WorkflowFiles.jobs("name: x\non: push\n"))
    }

    @Test fun a_workflow_file_names_its_checkout() {
        assertEquals("/work/proj" to "ci.yml", WorkflowFiles.locate("/work/proj/.github/workflows/ci.yml"))
        assertEquals("/work/proj" to "deploy.yaml", WorkflowFiles.locate("/work/proj/.github/workflows/deploy.yaml"))
        assertNull(WorkflowFiles.locate("/work/proj/ci.yml"))
        assertNull(WorkflowFiles.locate("/work/proj/.github/workflows/README.md"))
        assertNull(WorkflowFiles.locate("/work/proj/.github/workflows/sub/x.yml"))
    }

    @Test fun summaries_use_the_daemons_words() {
        val (l, code) = CiSummary.local(JsonParser.parseString("""{"conclusion":"failure","exit":3,"path":"/s/ci/x.log"}""").asJsonObject)
        assertEquals(listOf("-- ci.local: failure (exit 3)", "-- text: /s/ci/x.log"), l)
        assertEquals(3, code)
        assertEquals(130, CiSummary.local(JsonParser.parseString("""{"conclusion":"cancelled"}""").asJsonObject).second)
        assertEquals(0, CiSummary.tail(JsonParser.parseString("""{"run":"210","lines":9,"job":{"name":"test"}}""").asJsonObject).second)
    }
}
