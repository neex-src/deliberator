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
        assertEquals("#20  failure  feat aaaaaaa  wip  2026-10-10 10:00", r.rows[0].label())
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

    @Test fun the_jobs_of_a_run_are_parsed_with_steps_durations_and_where_their_logs_are() {
        val d = Ci.parseJobs(JsonParser.parseString("""{"repo":"o/r","run":"7","attempt":2,"final":true,"source":":kept","kept-at":"2026-11-10T10:00:00Z","age-s":3600,
            "run-row":{"id":7,"number":9,"workflow":"build","status":"completed","conclusion":"failure","event":"push","branch":"main","sha":"abcdef0123","title":"t",
              "created-at":"2026-11-05T12:15:48Z","started-at":"2026-11-05T12:15:49Z","updated-at":"2026-11-05T12:16:19Z","url":"https://github.com/o/r/actions/runs/7"},
            "jobs":[{"id":70,"name":"verify","status":"completed","conclusion":"failure","duration-s":27,"url":"https://github.com/o/r/actions/runs/7/job/70","log":"/ws/ci/70.log","steps":[
              {"number":1,"name":"Set up job","status":"completed","conclusion":"success","duration-s":1,"log":null},
              {"number":4,"name":"Run mvn -B verify","status":"completed","conclusion":"failure","duration-s":20,"log":"/ws/ci/70/04-x.log","lines":60,"exact":true},
              {"number":5,"name":"Post","status":"completed","conclusion":"success","duration-s":0,"log":"/ws/ci/70/05-p.log","lines":3,"exact":false},
              {"number":6,"name":"Build","status":"in_progress","started-at":"2026-11-05T12:00:00Z"}]}]}""").asJsonObject)
        assertEquals(2, d.attempt)
        assertTrue(d.final)
        assertEquals("kept", d.source)
        assertEquals(3600L, d.ageS)
        assertEquals("push", d.run.event)
        assertEquals(30L, d.run.durationS())
        assertEquals("#9  failure  push main abcdef0  t  2026-11-05 12:15  30s", d.run.label())
        val job = d.jobs[0]
        assertEquals(CiState.RED, job.state)
        assertEquals(4, job.firstRedStep!!.number)
        assertEquals("verify  failure  27s", job.label())
        assertEquals("4  Run mvn -B verify  failure  20s", job.steps[1].label())
        assertEquals("a step cut by time says so in its row", "5  Post  success  0s  (cut by time)", job.steps[2].label())
        assertEquals(CiState.RUNNING, job.steps[3].state)
        assertEquals("https://github.com/o/r/actions/runs/7/job/70", d.jobs[0].url)
        assertTrue(d.text().lines().first().startsWith("#9  failure"))
        assertEquals("https://github.com/o/r/actions/runs/7/job/70#step:4:1", CiSelection("o/r", d.run, job, job.steps[1], d).url())
        assertEquals(CiState.SKIPPED, Ci.state("completed", "skipped"))
        assertEquals(CiState.QUEUED, Ci.state("queued", null))
        assertEquals("3m 05s", Ci.fmt(185))
        assertEquals("2 days ago", Ci.age(2 * 86400L))
    }
}
