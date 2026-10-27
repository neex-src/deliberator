package com.neex.deliberator.ci

import com.google.gson.JsonObject
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.run.ScnRunner
import com.neex.deliberator.workspace.WorkspaceService

/** What opening a run (or a job) comes to; the view acts on it. */
sealed interface CiOpen {
    /** A text file the daemon wrote: the diff, or the job's log. */
    data class Text(val path: String, val what: String, val note: String? = null) : CiOpen
    /** The job has no log yet (it runs): follow it with `ci.tail`. */
    data class Follow(val repo: String, val run: String, val job: String?) : CiOpen
    /** Which job? The names are the daemon's own (`known` of `ci/no-job`). [then] is what to do with the choice. */
    data class Choose(val repo: String, val run: String, val jobs: List<String>, val then: CiDefault) : CiOpen
}

/**
 * M5.2-M5.4: the daemon's `ci.*` ops, one blocking function each (call from a background task); nothing about GitHub is known here, the repository is whatever
 * `ci.runs` accepts (`owner/name`, a github url, a clone's name). The files are the daemon's (`sessions/<k>/ci/`); the view opens the paths it is given.
 */
@Service(Service.Level.PROJECT)
class CiOps(private val project: Project) {
    private val svc get() = WorkspaceService.getInstance(project)

    private fun args(vararg kv: Pair<String, Any?>) = JsonObject().apply {
        kv.forEach { (k, v) -> when (v) { null -> {}; is Boolean -> addProperty(k, v); is Number -> addProperty(k, v); else -> addProperty(k, v.toString()) } }
    }

    /** The ops below write into the current session; one is offered when there is none. */
    private fun session() = ScnRunner.getInstance(project).ensureSession(svc)

    /** `ci.runs`: the runs of [repo] (all workflows, or the [workflow] given), newest first; the text file of them is [CiRuns.path]. */
    fun runs(repo: String, workflow: String? = null, limit: Int = 40, branch: String? = null): CiRuns {
        session()
        return Ci.parseRuns(svc.call("ci.runs", args("repo" to repo, "workflow" to workflow?.takeIf { it.isNotBlank() }, "limit" to limit,
            "branch" to branch?.takeIf { it.isNotBlank() })).resultObject())
    }

    /**
     * The job names of a run. There is no op that lists them; `ci.log` of a job nothing is called answers `ci/no-job` with every name in `known`, and
     * that is read here (a run with no jobs is an empty list). If the daemon grows `ci.jobs` this is the one place to change.
     */
    fun jobNames(repo: String, run: String): List<String> {
        session()
        return try {
            svc.call("ci.log", args("repo" to repo, "run" to run, "job" to NO_SUCH_JOB))
            emptyList()
        } catch (e: DaemonException) {
            when (e.type) { "ci/no-job" -> e.known; "ci/no-jobs" -> emptyList(); else -> throw e }
        }
    }

    /** `ci.log` of a job (default: the run's first failed job): the path of the log as text. A job that runs has none yet (`ci/log-not-available`). */
    fun log(repo: String, run: String, job: String? = null): CiOpen.Text {
        session()
        val r = svc.call("ci.log", args("repo" to repo, "run" to run, "job" to job)).resultObject()
        val j = r.getAsJsonObject("job")
        val name = j?.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: job ?: "job"
        val others = r.getAsJsonArray("other-failed")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()
        return CiOpen.Text(path(r), "log of $name (run $run)", others.takeIf { it.isNotEmpty() }?.let { "also failed: " + it.joinToString(", ") })
    }

    /** `ci.diff`: the failed job's log against the last green run of the same job, as a unified diff text file. */
    fun diff(repo: String, run: String, job: String? = null): CiOpen.Text {
        session()
        val r = svc.call("ci.diff", args("repo" to repo, "run" to run, "job" to job)).resultObject()
        val green = r.getAsJsonObject("green")?.get("run")?.takeIf { it.isJsonPrimitive }?.asString
        val added = r.get("added")?.takeIf { it.isJsonPrimitive }?.asInt
        return CiOpen.Text(path(r), "diff of run $run against the green run ${green ?: "?"}",
            if (r.get("equal")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) "the logs are equal after normalisation" else added?.let { "$it lines only in the red run" })
    }

    /**
     * M5.3: what a double-click on [run] does. Red: the diff against the last green run (the failed run's own log when there is no green run to compare with).
     * Running: follow. Anything else: its log, after choosing the job when there are several.
     */
    fun openDefault(repo: String, run: CiRun): CiOpen = when (Ci.defaultFor(run)) {
        CiDefault.DIFF -> try {
            diff(repo, run.id)
        } catch (e: DaemonException) {
            if (e.type in DIFF_FALLBACK) log(repo, run.id).let { it.copy(note = "no diff: ${e.message}") } else throw e
        }
        CiDefault.FOLLOW -> choose(repo, run.id, CiDefault.FOLLOW)
        CiDefault.LOG -> choose(repo, run.id, CiDefault.LOG)
    }

    /** The job to act on: the only one, else the choice is the view's. */
    private fun choose(repo: String, run: String, then: CiDefault): CiOpen {
        val names = jobNames(repo, run)
        return if (names.size == 1) openJob(repo, run, names[0], then) else CiOpen.Choose(repo, run, names, then)
    }

    /** A chosen [job]: its log, or, for [CiDefault.FOLLOW] / a job still running, a follow; [CiDefault.DIFF] is the diff of that job. */
    fun openJob(repo: String, run: String, job: String, how: CiDefault = CiDefault.LOG): CiOpen = when (how) {
        CiDefault.FOLLOW -> CiOpen.Follow(repo, run, job)
        CiDefault.DIFF -> diff(repo, run, job)
        CiDefault.LOG -> try { log(repo, run, job) } catch (e: DaemonException) { if (e.type == "ci/log-not-available") CiOpen.Follow(repo, run, job) else throw e }
    }

    /** `ci.tail` as a process whose console is the job's live log. Start it with [DaemonOpProcessHandler.begin]. */
    fun tail(repo: String, run: String, job: String?): DaemonOpProcessHandler {
        session()
        return DaemonOpProcessHandler(project, "ci.tail", args("repo" to repo, "run" to run, "job" to job), "ci tail $repo run $run" + (job?.let { " job $it" } ?: ""),
            CiSummary::tail)
    }

    /**
     * M5.4: `ci.local` of one job of a workflow of the checkout [dir] (an absolute directory: act runs in it) as a process; the console is act's output
     * (`logs.line` events), the end line is the conclusion and the text file the daemon kept. [image] is the runner image of `ubuntu-latest` (default: the daemon's).
     */
    fun local(dir: String, workflow: String, job: String, image: String? = null): DaemonOpProcessHandler {
        session()
        return DaemonOpProcessHandler(project, "ci.local", args("repo" to dir, "workflow" to workflow, "job" to job, "image" to image), "ci local $workflow:$job", CiSummary::local)
    }

    companion object {
        fun getInstance(project: Project): CiOps = project.getService(CiOps::class.java)

        /** Cannot be a part of a job's name or id. */
        const val NO_SUCH_JOB = "\u0001"

        /** Errors of `ci.diff` that only mean "nothing to compare with": the failed run's log is shown instead. */
        val DIFF_FALLBACK = setOf("ci/no-green-run", "ci/not-failed", "ci/no-failed-job")

        private fun path(r: JsonObject) = r.get("path")?.takeIf { it.isJsonPrimitive }?.asString ?: throw DaemonException("the daemon returned no file", "ci/no-path")
    }
}

/** The end lines of a console: what the daemon answered, in its words. */
object CiSummary {
    private fun JsonObject.str(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString?.removePrefix(":")

    fun tail(r: JsonObject): Pair<List<String>, Int> {
        val job = r.getAsJsonObject("job")?.str("name")
        return listOf("-- followed ${job ?: "the job"} of run ${r.str("run") ?: "?"}: ${r.get("lines")?.takeIf { it.isJsonPrimitive }?.asInt ?: "?"} lines") +
            listOfNotNull(r.str("path")?.let { "-- text: $it" }) to 0
    }

    fun local(r: JsonObject): Pair<List<String>, Int> {
        val exit = r.get("exit")?.takeIf { it.isJsonPrimitive }?.asInt
        val conclusion = r.str("conclusion") ?: "?"
        val lines = listOf("-- ci.local: $conclusion" + (exit?.let { " (exit $it)" } ?: "")) + listOfNotNull(r.str("path")?.let { "-- text: $it" })
        return lines to (exit ?: 130)
    }
}
