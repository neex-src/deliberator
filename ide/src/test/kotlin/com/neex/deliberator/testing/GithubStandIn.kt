package com.neex.deliberator.testing

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A local stand-in for the GitHub REST API (the one `ci_test` of the core uses, in Kotlin): `acme/widgets` with the workflow `ci`, a red run 200 (jobs "test (ubuntu)"
 * red, "lint" green), two green runs 195 (same branch, earlier) and 190 (main), and a run 210 that is in progress until [runningPolls] polls of its job have been
 * answered. The workspace's `github.edn` names it as `:api` ([point]); no token is involved. Every request path is recorded in [requests].
 */
class GithubStandIn : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port: Int get() = server.address.port
    val api: String get() = "http://127.0.0.1:$port"
    val requests = CopyOnWriteArrayList<String>()
    /** Polls of job 2101 (the running one) answered "in progress" before it completes. */
    @Volatile var runningPolls = 2
    private val polled = ConcurrentHashMap<Int, Int>()

    private class Run(val id: Int, val number: Int, val branch: String, val status: String, val conclusion: String?, val created: String, val title: String)
    private val runs = listOf(
        Run(210, 21, "feat", "in_progress", null, "2026-10-10T11:00:00Z", "wip 3"),
        Run(200, 20, "feat", "completed", "failure", "2026-10-10T10:00:00Z", "wip"),
        Run(195, 19, "feat", "completed", "success", "2026-10-10T09:00:00Z", "wip 2"),
        Run(190, 18, "main", "completed", "success", "2026-10-09T09:00:00Z", "release"),
    )
    private val jobs = mapOf(
        210 to listOf(2101 to "test (ubuntu)"),
        200 to listOf(2001 to "test (ubuntu)", 2002 to "lint"),
        195 to listOf(1951 to "test (ubuntu)", 1952 to "lint"),
        190 to listOf(1901 to "test (ubuntu)"),
    )
    private val failing = setOf(2001)

    init {
        server.createContext("/") { ex -> try { handle(ex) } catch (t: Throwable) { reply(ex, 500, t.toString()) } }
        server.start()
    }

    /** Writes the workspace's `github.edn` (no token source, this stand-in as the API). */
    fun point(workspaceDir: Path) = Files.writeString(workspaceDir.resolve("github.edn"), "{:token-source :none :api \"$api\"}\n")

    private fun ts(h: Int, m: Int, s: Int, frac: Int) = "2026-10-10T%02d:%02d:%02d.%07dZ".format(h, m, s, frac)

    private fun logOf(job: Int): String {
        val h = job % 24
        val lines = mutableListOf(ts(h, 0, 1, 4793500) + " Current runner version: '2.337.0'", ts(h, 0, 3, 2) + " ##[group]Run make test", ts(h, 0, 6, 5) + " running 12 tests")
        if (job in failing) { lines += ts(h, 0, 7, 6) + " FAIL in (match-test) (fs_test.clj:98)"; lines += ts(h, 0, 7, 7) + " ##[error]Process completed with exit code 1." }
        else lines += ts(h, 0, 7, 6) + " 12 tests, 0 failures"
        lines += ts(h, 0, 9, 8) + " Cleaning up orphan processes"
        return lines.joinToString("\n") + "\n"
    }

    private fun runJson(r: Run) = JsonObject().apply {
        addProperty("id", r.id); addProperty("run_number", r.number); addProperty("run_attempt", 1); addProperty("name", "ci"); addProperty("workflow_id", 7)
        addProperty("display_title", r.title); addProperty("head_branch", r.branch); addProperty("head_sha", "a".repeat(40)); addProperty("event", "push")
        addProperty("status", r.status); addProperty("conclusion", r.conclusion); addProperty("created_at", r.created)
        addProperty("html_url", "https://github.com/acme/widgets/actions/runs/${r.id}")
    }

    private fun jobJson(id: Int, name: String, status: String, conclusion: String?) = JsonObject().apply {
        addProperty("id", id); addProperty("name", name); addProperty("status", status); addProperty("conclusion", if (status == "completed") conclusion else null)
        addProperty("started_at", "2026-10-10T10:00:00Z"); addProperty("completed_at", if (status == "completed") "2026-10-10T10:09:00Z" else null)
        addProperty("run_id", 1); addProperty("run_attempt", 1)
        add("steps", JsonArray().apply {
            add(JsonObject().apply { addProperty("number", 1); addProperty("name", "Set up job"); addProperty("status", "completed"); addProperty("conclusion", "success") })
            add(JsonObject().apply { addProperty("number", 2); addProperty("name", "make test"); addProperty("status", status); addProperty("conclusion", if (status == "completed") conclusion else null) })
        })
    }

    private fun conclusionOf(job: Int) = if (job in failing) "failure" else "success"

    private fun user(login: String) = JsonObject().apply { addProperty("login", login) }

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        requests += path
        val base = "/repos/acme/widgets"
        fun json(x: Any) = reply(ex, 200, x.toString(), "application/json")
        when {
            path.startsWith("/blob/") -> reply(ex, 200, logOf(path.substringAfterLast('/').toInt()), "text/plain")
            path == "$base/actions/workflows" -> json(JsonObject().apply { add("workflows", JsonArray().apply { add(JsonObject().apply { addProperty("id", 7); addProperty("name", "ci"); addProperty("path", ".github/workflows/ci.yml") }) }) })
            Regex("$base/actions/workflows/[^/]+/runs").matches(path) || path == "$base/actions/runs" ->
                json(JsonObject().apply { addProperty("total_count", runs.size); add("workflow_runs", JsonArray().apply { runs.forEach { add(runJson(it)) } }) })
            Regex("$base/actions/runs/\\d+").matches(path) -> runs.firstOrNull { it.id == path.substringAfterLast('/').toInt() }?.let { json(runJson(it)) } ?: notFound(ex)
            Regex("$base/actions/runs/\\d+/jobs").matches(path) -> {
                val run = path.removePrefix("$base/actions/runs/").substringBefore('/').toInt()
                val r = runs.firstOrNull { it.id == run } ?: return notFound(ex)
                json(JsonObject().apply { add("jobs", JsonArray().apply { jobs[run]!!.forEach { (id, name) -> add(jobJson(id, name, r.status, conclusionOf(id))) } }) })
            }
            Regex("$base/actions/jobs/\\d+").matches(path) -> {
                val id = path.substringAfterLast('/').toInt()
                val name = jobs.values.flatten().first { it.first == id }.second
                val left = if (id == 2101) runningPolls - polled.merge(id, 1, Int::plus)!! + 1 else 0
                json(jobJson(id, name, if (id == 2101 && left > 0) "in_progress" else "completed", conclusionOf(id)))
            }
            Regex("$base/actions/jobs/\\d+/logs").matches(path) -> {
                ex.responseHeaders.add("Location", "http://localhost:$port/blob/${path.removeSuffix("/logs").substringAfterLast('/')}?sig=SECRET")
                ex.sendResponseHeaders(302, -1); ex.close()
            }
            path == "$base/pulls/1" -> json(JsonObject().apply {
                addProperty("title", "Add feature"); addProperty("state", "open"); addProperty("merged", false); addProperty("draft", false); add("user", user("octo"))
                add("base", JsonObject().apply { addProperty("ref", "main") })
                add("head", JsonObject().apply { addProperty("ref", "feat"); addProperty("label", "acme:feat"); addProperty("sha", "a".repeat(40)) })
                addProperty("commits", 1); addProperty("changed_files", 1); addProperty("additions", 1); addProperty("deletions", 0)
                addProperty("html_url", "https://github.com/acme/widgets/pull/1"); addProperty("body", "Please review"); addProperty("mergeable_state", "clean")
            })
            path == "$base/pulls/1/reviews" -> json(JsonArray().apply { add(JsonObject().apply { add("user", user("rev")); addProperty("state", "CHANGES_REQUESTED"); addProperty("body", "needs tests"); addProperty("submitted_at", "2026-10-10T12:00:00Z") }) })
            path == "$base/pulls/1/comments" -> json(JsonArray().apply { add(JsonObject().apply {
                addProperty("id", 1); addProperty("path", "README"); addProperty("line", 1); addProperty("body", "nit: say more"); add("user", user("rev"))
                addProperty("created_at", "2026-10-10T12:01:00Z"); addProperty("diff_hunk", "@@ -0,0 +1 @@\n+x") }) })
            path == "$base/issues/1/comments" -> json(JsonArray().apply { add(JsonObject().apply { addProperty("id", 9); add("user", user("octo")); addProperty("body", "will fix"); addProperty("created_at", "2026-10-10T12:05:00Z") }) })
            else -> notFound(ex)
        }
    }

    private fun notFound(ex: HttpExchange) = reply(ex, 404, "{\"message\":\"Not Found\"}", "application/json")

    private fun reply(ex: HttpExchange, status: Int, body: String, type: String = "text/plain") {
        val bs = body.toByteArray()
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("X-RateLimit-Remaining", "4990")
        ex.sendResponseHeaders(status, bs.size.toLong())
        ex.responseBody.use { it.write(bs) }
    }

    override fun close() = server.stop(0)
}
