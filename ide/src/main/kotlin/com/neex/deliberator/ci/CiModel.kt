/**
 * The CI view's data (M5.2-M5.4): what `ci.runs`, `ci.log`, `ci.diff` and `ci.local` answered, parsed defensively (a missing key is an empty value), and the two
 * pure decisions of the view: what the default action on a run is, and where a workflow job is in a workflow file. Nothing here talks to the daemon.
 */
package com.neex.deliberator.ci

import com.google.gson.JsonObject

/** One run of `ci.runs` (a row of its text file). [status] is GitHub's (`completed`, `in_progress`, `queued`), [conclusion] the outcome of a completed run. */
data class CiRun(
    val id: String, val number: String?, val workflow: String, val title: String?, val branch: String?, val sha: String?,
    val status: String, val conclusion: String?, val createdAt: String?, val url: String?,
) {
    val running: Boolean get() = status != "completed"
    val red: Boolean get() = conclusion in RED
    val result: String get() = conclusion ?: status

    /** The row's text: `#20 failure feat 9f3a2c1 2026-10-10 wip`. */
    fun label(): String = "#${number ?: id}  $result  ${branch ?: ""}  ${sha?.take(7) ?: ""}  ${createdAt?.take(16)?.replace('T', ' ') ?: ""}  ${title ?: ""}".replace(Regex("\\s{3,}"), "  ").trim()

    companion object {
        val RED = setOf("failure", "timed_out", "startup_failure")
    }
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

    fun parseRuns(r: JsonObject): CiRuns {
        val rows = r.getAsJsonArray("rows")?.filter { it.isJsonObject }?.map { it.asJsonObject }?.map { o ->
            CiRun(o.str("id") ?: "?", o.str("number"), o.str("workflow") ?: "(workflow)", o.str("title"), o.str("branch"), o.str("sha"),
                o.str("status") ?: "?", o.str("conclusion"), o.str("created-at"), o.str("url"))
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
