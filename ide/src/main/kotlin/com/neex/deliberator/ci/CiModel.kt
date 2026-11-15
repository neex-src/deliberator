/**
 * The CI view's data (M5.2-M5.4, piece 01): what `ci.runs`, `ci.jobs`, `ci.log`, `ci.diff` and `ci.local` answered, parsed defensively (a missing key is an empty value), and the two
 * pure decisions of the view: what the default action on a run is, and where a workflow job is in a workflow file. Nothing here talks to the daemon.
 */
package com.neex.deliberator.ci

import com.google.gson.JsonObject

/**
 * One run of `ci.runs` (a row of its text file) or of `ci.jobs` (its `run-row`). [status] is the CI system's (`completed`, `in_progress`, `queued`), [conclusion] the
 * outcome of a completed run. [origin] says what ran it (`github` today): nothing in the view assumes GitHub, [url] is where the CI system shows it (null: nowhere).
 */
data class CiRun(
    val id: String, val number: String?, val workflow: String, val title: String?, val branch: String?, val sha: String?,
    val status: String, val conclusion: String?, val createdAt: String?, val url: String?,
    val event: String? = null, val attempt: Int = 1, val origin: String = "github", val startedAt: String? = null, val updatedAt: String? = null,
) {
    val running: Boolean get() = status != "completed"
    val red: Boolean get() = conclusion in RED
    val result: String get() = conclusion ?: status

    /** Seconds the run took (completed) or has taken so far, null when its start is not known. */
    fun durationS(now: java.time.Instant = java.time.Instant.now()): Long? {
        val a = Ci.instant(startedAt ?: createdAt) ?: return null
        val b = if (running) now else Ci.instant(updatedAt) ?: return null
        return (b.epochSecond - a.epochSecond).coerceAtLeast(0)
    }

    /** The row's text: `#20  failure  push feat 9f3a2c1  wip  2026-10-10 10:00  3m 20s`. */
    fun label(now: java.time.Instant = java.time.Instant.now()): String =
        "#${number ?: id}  $result  ${listOfNotNull(event, branch, sha?.take(7)).joinToString(" ")}  ${title ?: ""}  ${createdAt?.take(16)?.replace('T', ' ') ?: ""}  ${durationS(now)?.let { Ci.fmt(it) } ?: ""}"
            .replace(Regex("\\s{3,}"), "  ").trim()

    companion object {
        val RED = setOf("failure", "timed_out", "startup_failure")
    }
}

/** What a step, job or run is, for its icon and colour. */
enum class CiState { OK, RED, RUNNING, QUEUED, SKIPPED }

/** One step of a job of `ci.jobs`. [log] is the kept file of its lines (null: not fetched yet), [exact] false when its lines were cut by time. */
data class CiStep(
    val number: Int, val name: String, val status: String, val conclusion: String?, val startedAt: String?, val completedAt: String?,
    val durationS: Long?, val log: String?, val lines: Int?, val exact: Boolean?,
) {
    val state: CiState get() = Ci.state(status, conclusion)
    val result: String get() = conclusion ?: status
    fun duration(now: java.time.Instant = java.time.Instant.now()): Long? = durationS ?: if (state == CiState.RUNNING) Ci.instant(startedAt)?.let { (now.epochSecond - it.epochSecond).coerceAtLeast(0) } else null
    fun label(now: java.time.Instant = java.time.Instant.now()): String =
        "$number  $name  $result  ${duration(now)?.let { Ci.fmt(it) } ?: ""}${if (exact == false) "  (cut by time)" else ""}".trim()
}

/** One job of `ci.jobs`. */
data class CiJob(
    val id: String, val name: String, val status: String, val conclusion: String?, val startedAt: String?, val completedAt: String?,
    val durationS: Long?, val url: String?, val runner: String?, val log: String?, val steps: List<CiStep>,
) {
    val state: CiState get() = Ci.state(status, conclusion)
    val result: String get() = conclusion ?: status
    fun duration(now: java.time.Instant = java.time.Instant.now()): Long? = durationS ?: if (state == CiState.RUNNING) Ci.instant(startedAt)?.let { (now.epochSecond - it.epochSecond).coerceAtLeast(0) } else null
    fun label(now: java.time.Instant = java.time.Instant.now()): String = "$name  $result  ${duration(now)?.let { Ci.fmt(it) } ?: ""}".trim()
    /** The first failed step, the one a red run opens down to. */
    val firstRedStep: CiStep? get() = steps.firstOrNull { it.state == CiState.RED }
}

/** The answer of `ci.jobs`: the [run] row, its [jobs] with steps, where it came from ([source] `kept` or `fetched`, [ageS] of a kept copy) and the text view [path]. */
data class CiRunDetail(val repo: String, val run: CiRun, val attempt: Int, val final: Boolean, val source: String, val keptAt: String?, val ageS: Long?, val jobs: List<CiJob>, val path: String?) {
    val firstRedJob: CiJob? get() = jobs.firstOrNull { it.state == CiState.RED }

    /** The run as indented text, one line per job and per step (Copy as Text). */
    fun text(now: java.time.Instant = java.time.Instant.now()): String =
        (listOf(run.label(now)) + jobs.flatMap { j -> listOf("  " + j.label(now)) + j.steps.map { "    " + it.label(now) } }).joinToString("\n")
}

/** The answer of `ci.runs`: [path] is the text file the daemon wrote (one line per run), [total] what GitHub knows. */
data class CiRuns(val repo: String, val path: String?, val total: Int, val rows: List<CiRun>) {
    /** The rows grouped by workflow name, in the order GitHub listed them (newest first). */
    fun byWorkflow(): Map<String, List<CiRun>> = rows.groupBy { it.workflow }
}

/** What the view does when a run is opened with Enter or a double-click (M5.3: a red run is a diff against the last green one). */
enum class CiDefault { DIFF, FOLLOW, LOG }

object Ci {
    fun defaultFor(run: CiRun): CiDefault = when {
        run.running -> CiDefault.FOLLOW
        run.red -> CiDefault.DIFF
        else -> CiDefault.LOG
    }

    private fun JsonObject.str(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString?.removePrefix(":")?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull()
    private fun JsonObject.long(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.runCatching { asLong }?.getOrNull()
    private fun JsonObject.bool(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.runCatching { asBoolean }?.getOrNull()

    fun runOf(o: JsonObject) = CiRun(o.str("id") ?: "?", o.str("number"), o.str("workflow") ?: "(workflow)", o.str("title"), o.str("branch"), o.str("sha"),
        o.str("status") ?: "?", o.str("conclusion"), o.str("created-at"), o.str("url"), o.str("event"), o.int("attempt") ?: 1, o.str("origin") ?: "github",
        o.str("started-at"), o.str("updated-at"))

    fun instant(s: String?): java.time.Instant? = s?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }

    fun state(status: String, conclusion: String?): CiState = when {
        status == "completed" -> when (conclusion) { "success" -> CiState.OK; "failure", "timed_out", "startup_failure" -> CiState.RED; else -> CiState.SKIPPED }
        status == "in_progress" -> CiState.RUNNING
        else -> CiState.QUEUED
    }

    /** `12s`, `3m 05s`, `1h 02m`. */
    fun fmt(s: Long): String = when {
        s < 60 -> "${s}s"
        s < 3600 -> "%dm %02ds".format(s / 60, s % 60)
        else -> "%dh %02dm".format(s / 3600, s / 60 % 60)
    }

    /** `3 min ago`, `2 days ago`: the age of a kept copy. */
    fun age(s: Long): String = when {
        s < 90 -> "just now"
        s < 5400 -> "${(s + 30) / 60} min ago"
        s < 129600 -> "${(s + 1800) / 3600} h ago"
        else -> "${(s + 43200) / 86400} days ago"
    }

    fun parseJobs(r: JsonObject): CiRunDetail {
        val run = runOf(r.getAsJsonObject("run-row") ?: JsonObject())
        val jobs = r.getAsJsonArray("jobs")?.filter { it.isJsonObject }?.map { it.asJsonObject }?.map { j ->
            val steps = j.getAsJsonArray("steps")?.filter { it.isJsonObject }?.map { it.asJsonObject }?.map { s ->
                CiStep(s.int("number") ?: 0, s.str("name") ?: "?", s.str("status") ?: "?", s.str("conclusion"), s.str("started-at"), s.str("completed-at"),
                    s.long("duration-s"), s.str("log"), s.int("lines"), s.bool("exact"))
            }.orEmpty()
            CiJob(j.str("id") ?: "?", j.str("name") ?: "?", j.str("status") ?: "?", j.str("conclusion"), j.str("started-at"), j.str("completed-at"),
                j.long("duration-s"), j.str("url"), j.str("runner"), j.str("log"), steps)
        }.orEmpty()
        return CiRunDetail(r.str("repo") ?: "", run, r.int("attempt") ?: run.attempt, r.bool("final") ?: false, r.str("source") ?: "fetched", r.str("kept-at"),
            r.long("age-s"), jobs, r.str("path"))
    }

    fun parseRuns(r: JsonObject): CiRuns {
        val rows = r.getAsJsonArray("rows")?.filter { it.isJsonObject }?.map { it.asJsonObject }?.map { o ->
            runOf(o)
        }.orEmpty()
        return CiRuns(r.str("repo") ?: "", r.str("path"), r.get("total")?.takeIf { it.isJsonPrimitive }?.asInt ?: rows.size, rows)
    }
}

/** A job of a workflow file: its id (the key under `jobs:`), its 0-based [line], and [end], the line after its block. */
data class WorkflowJob(val id: String, val line: Int, val end: Int = line + 1)

/** A workflow file `<root>/.github/workflows/<file>` and what is in it. */
object WorkflowFiles {
    private val DIR = Regex("^(.*)/\\.github/workflows/([^/]+\\.ya?ml)$")

    /** `(root, file)` of a path inside `.github/workflows`, null for any other file. The root is the checkout directory `ci.local` runs in. */
    fun locate(path: String): Pair<String, String>? = DIR.matchEntire(path.replace('\\', '/'))?.let { it.groupValues[1].ifEmpty { "/" } to it.groupValues[2] }

    private val JOBS = Regex("^jobs:\\s*(#.*)?$")
    private val KEY = Regex("^(\\s+)(?:\"([^\"]+)\"|'([^']+)'|([A-Za-z0-9_][A-Za-z0-9_-]*)):\\s*(#.*)?$")

    /** The job ids under the top-level `jobs:` key (text only: no YAML plugin needed), with their lines. */
    fun jobs(text: String): List<WorkflowJob> {
        val out = mutableListOf<WorkflowJob>()
        var inJobs = false
        var indent = -1
        var jobsEnd = -1
        val lines = text.lines()
        for ((i, raw) in lines.withIndex()) {
            val line = raw.trimEnd()
            if (!inJobs) { if (jobsEnd < 0 && JOBS.matches(line)) inJobs = true; continue }
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            if (!line[0].isWhitespace()) { inJobs = false; jobsEnd = i; continue }   // the next top-level key ends the jobs
            val m = KEY.matchEntire(line) ?: continue
            val ind = m.groupValues[1].length
            if (indent < 0) indent = ind
            if (ind == indent) out += WorkflowJob(m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { m.groupValues[4] }, i)
        }
        if (jobsEnd < 0) jobsEnd = lines.size
        return out.mapIndexed { n, j -> j.copy(end = out.getOrNull(n + 1)?.line ?: jobsEnd) }
    }

    /** The job whose block holds [line] (null above the first job and below the `jobs:` section). */
    fun jobAt(jobs: List<WorkflowJob>, line: Int): WorkflowJob? = jobs.lastOrNull { it.line <= line && line < it.end }
}
