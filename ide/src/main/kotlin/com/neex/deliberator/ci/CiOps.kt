package com.neex.deliberator.ci

import com.google.gson.JsonObject
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.run.ScnRunner
import com.neex.deliberator.workspace.WorkspaceService

/** What `ci.archive` did for a repository, for the status line. */
data class CiArchive(val repo: String, val checked: Int, val newRuns: Int, val newLogs: Int, val failed: Int) {
    fun text(): String = if (newRuns == 0 && newLogs == 0 && failed == 0) "$repo: the newest $checked finished runs are kept"
    else "$repo: kept $newRuns new runs and $newLogs logs of the newest $checked finished runs" + (if (failed > 0) ", $failed failed" else "")
}

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
 * `ci.runs` accepts (`owner/name`, a github url, a clone's name). The files are the daemon's (in the session's ci directory); the view opens the paths it is given.
 */
@Service(Service.Level.PROJECT)
class CiOps(private val project: Project) {
    private val svc get() = WorkspaceService.getInstance(project)

    private fun args(vararg kv: Pair<String, Any?>) = JsonObject().apply {
        kv.forEach { (k, v) -> when (v) { null -> {}; is Boolean -> addProperty(k, v); is Number -> addProperty(k, v); is List<*> -> add(k, com.google.gson.JsonArray().also { a -> v.forEach { a.add(it.toString()) } }); else -> addProperty(k, v.toString()) } }
    }

    /** The ops below write into the current session; one is offered when there is none. */
    private fun session() = ScnRunner.getInstance(project).ensureSession(svc)

    /**
     * `ci.runs`: the runs of [repo] (all workflows, or the [workflow] given) and its LOCAL runs, newest first; the text file of them is [CiRuns.path].
     * [cached] answers the last known listing at once (no request, [CiRuns.ageS]); [poll] is the 30 s poll (one overwritten text view).
     */
    fun runs(repo: String, workflow: String? = null, limit: Int = 40, branch: String? = null, cached: Boolean = false, poll: Boolean = false): CiRuns {
        session()
        return Ci.parseRuns(svc.call("ci.runs", args("repo" to repo, "workflow" to workflow?.takeIf { it.isNotBlank() }, "limit" to limit,
            "branch" to branch?.takeIf { it.isNotBlank() }, "cached" to (if (cached) true else null), "poll" to (if (poll) true else null))).resultObject())
    }

    /** `ci.workflows`: the workflow files of the checkout [repo] names (empty for a repository with no checkout here). */
    fun workflows(repo: String): List<CiWorkflow> = try {
        Ci.parseWorkflows(svc.call("ci.workflows", args("repo" to repo)).resultObject())
    } catch (e: DaemonException) { emptyList() }

    /** `ci.jobs`: the jobs of [run] with their steps. A finished run is answered from the kept copy (no network); [attempt] keeps a rerun apart from its first attempt. */
    fun jobs(repo: String, run: String, attempt: Int? = null, refresh: Boolean = false): CiRunDetail {
        session()
        return Ci.parseJobs(svc.call("ci.jobs", args("repo" to repo, "run" to run, "attempt" to attempt?.toString(), "refresh" to (if (refresh) true else null))).resultObject())
    }

    /** The job names of a run (from [jobs]; a run with no jobs is an empty list). */
    fun jobNames(repo: String, run: String): List<String> = jobs(repo, run).jobs.map { it.name }

    /** `ci.archive`: keeps the jobs and logs of the newest [limit] finished runs of [repo] that are not kept yet. */
    fun archive(repo: String, limit: Int): CiArchive {
        session()
        val r = svc.call("ci.archive", args("repo" to repo, "limit" to limit)).resultObject()
        fun n(k: String) = r.get(k)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        return CiArchive(repo, n("checked"), n("new-runs"), n("new-logs"), r.getAsJsonArray("failed")?.size() ?: 0)
    }

    /** `ci.log` of a job (default: the run's first failed job), or of one [step] of it (number or name): the path of the log as text. A job that runs has none yet (`ci/log-not-available`). */
    fun log(repo: String, run: String, job: String? = null, step: String? = null): CiOpen.Text {
        session()
        val r = svc.call("ci.log", args("repo" to repo, "run" to run, "job" to job, "step" to step)).resultObject()
        val j = r.get("job") as? JsonObject
        val name = j?.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: job ?: "job"
        val others = r.getAsJsonArray("other-failed")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()
        val st = r.get("step") as? JsonObject
        val stepName = st?.let { "${it.get("number")?.asString} ${it.get("name")?.asString}" }
        val kept = r.get("source")?.takeIf { it.isJsonPrimitive }?.asString?.removePrefix(":") == "kept"
        val age = r.get("age-s")?.takeIf { it.isJsonPrimitive }?.asLong
        val inexact = st?.get("exact")?.takeIf { it.isJsonPrimitive }?.asBoolean == false
        val note = listOfNotNull(
            if (kept && age != null) "kept copy, fetched ${Ci.age(age)}" else null,
            if (inexact) "cut by time, not exact: the first line says so" else null,
            others.takeIf { it.isNotEmpty() }?.let { "also failed: " + it.joinToString(", ") },
        ).joinToString("; ").ifEmpty { null }
        return CiOpen.Text(path(r), (if (stepName != null) "log of step $stepName of $name" else "log of $name") + " (run $run)", note)
    }

    /** `ci.diff`: the failed job's log (or one failed [step] of it) against the last green run of the same job, as a unified diff text file. */
    fun diff(repo: String, run: String, job: String? = null, step: String? = null): CiOpen.Text {
        session()
        val r = svc.call("ci.diff", args("repo" to repo, "run" to run, "job" to job, "step" to step)).resultObject()
        val green = (r.get("green") as? JsonObject)?.get("run")?.takeIf { it.isJsonPrimitive }?.asString
        val added = r.get("added")?.takeIf { it.isJsonPrimitive }?.asInt
        val st = (r.get("step") as? JsonObject)?.let { "step ${it.get("number")?.asString} ${it.get("name")?.asString} of " } ?: ""
        return CiOpen.Text(path(r), "diff of ${st}run $run against the green run ${green ?: "?"}",
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
     * `ci.local` of [workflow] of the checkout [repo] (a repository name or an absolute directory) as a process: the console is act's output (`logs.line`
     * events), the end line is the conclusion. [job] null runs every job; [event] and [inputs] as the Run Locally dialog decided. `onEvent` hears `ci.run`
     * and `ci.step`.
     */
    fun local(repo: String, workflow: String, job: String?, event: String? = null, inputs: Map<String, String> = emptyMap(), image: String? = null): DaemonOpProcessHandler {
        session()
        return DaemonOpProcessHandler(project, "ci.local",
            args("repo" to repo, "workflow" to workflow, "job" to job, "event" to event, "input" to inputs.map { "${it.key}=${it.value}" }.takeIf { it.isNotEmpty() }, "image" to image),
            "ci local ${workflow.substringAfterLast('/')}" + (job?.let { ":$it" } ?: ""), CiSummary::local)
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
        val lines = listOf("-- ci.local: ${r.str("run")?.let { "$it " } ?: ""}$conclusion" + (exit?.let { " (exit $it)" } ?: "")) + listOfNotNull(r.str("path")?.let { "-- text: $it" })
        return lines to (exit ?: 130)
    }
}
