package com.neex.deliberator.work

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.repo.RepositoryAttacher
import com.neex.deliberator.run.ScnRunner
import com.neex.deliberator.workspace.WorkspaceService
import java.util.concurrent.CopyOnWriteArrayList

/** One row of `worktree.list`: [path] is where the daemon made it, [dirty] the number of changed paths. */
data class Worktree(val repo: String, val name: String, val branch: String?, val head: String?, val path: String, val missing: Boolean, val dirty: Int)

/** What the Work tab shows: every repository of `repo.list` (clones and checkouts) and every worktree. */
data class WorkSnapshot(val repos: List<RepositoryAttacher.Repo> = emptyList(), val worktrees: List<Worktree> = emptyList(), val error: String? = null)

/** A worktree made (or found) by `worktree.start` or `pr.open`, and what the IDE did with it: [attached] is the attach outcome, [problem] why it is not one. */
data class Working(val worktree: Worktree, val attached: RepositoryAttacher.Outcome?, val problem: String?, val created: Boolean)

/** What `pr.open` answered, reduced to what the IDE opens: the worktree, the review-comments text, the checks text, why GitHub was skipped. */
data class PrOpened(val repo: String, val n: Int, val working: Working, val reviewPath: String?, val checksPath: String?, val title: String?, val state: String?,
                    val checks: String?, val note: String?, val raw: JsonObject)

/**
 * M5.1 / M5.5: the repository store as the daemon keeps it. Everything asks the daemon (`repo.list`, `repo.clone`, `worktree.*`, `pr.open`) and attaches the
 * worktree path it answers through [RepositoryAttacher], the same code as Attach Repository; the IDE never knows where `repos/` or `worktrees/` are.
 * Every function blocks: call from a background task. A mutation publishes the new [WorkSnapshot] to the listeners.
 */
@Service(Service.Level.PROJECT)
class WorkOps(private val project: Project) {
    private val svc get() = WorkspaceService.getInstance(project)
    private val attacher get() = RepositoryAttacher.getInstance(project)
    private val listeners = CopyOnWriteArrayList<(WorkSnapshot) -> Unit>()
    @Volatile var snapshot = WorkSnapshot()
        private set

    fun addListener(parent: Disposable, l: (WorkSnapshot) -> Unit) {
        listeners += l
        com.intellij.openapi.util.Disposer.register(parent) { listeners -= l }
    }

    private fun args(vararg kv: Pair<String, Any?>) = JsonObject().apply {
        kv.forEach { (k, v) -> when (v) { null -> {}; is Boolean -> addProperty(k, v); is Number -> addProperty(k, v); else -> addProperty(k, v.toString()) } }
    }

    /**
     * Every repository the workspace records, bare clones included, except the worktrees: an attached worktree is recorded by `repo.attach` like any checkout
     * (that is how it stays attached across reopening) but it is a row of [worktrees], not a repository to start working in.
     */
    fun repos(): List<RepositoryAttacher.Repo> {
        val all = attacher.list(includeBare = true)
        val trees = runCatching { worktrees() }.getOrDefault(emptyList()).map { norm(it.path) }.toSet()
        return all.filter { norm(it.path) !in trees }
    }

    private fun norm(p: String) = try { java.nio.file.Path.of(p).toRealPath().toString() } catch (_: Exception) { java.nio.file.Path.of(p).normalize().toString() }

    fun worktrees(repo: String? = null): List<Worktree> =
        parseWorktrees(svc.call("worktree.list", args("repo" to repo)).result)

    /** Reads both lists and publishes them; a failed part keeps the previous value and says so in [WorkSnapshot.error]. */
    fun refreshNow(): WorkSnapshot {
        var error: String? = null
        val repos = try { repos() } catch (e: DaemonException) { error = e.message; snapshot.repos }
        val wts = try { worktrees() } catch (e: DaemonException) { if (error == null) error = e.message; snapshot.worktrees }
        return publish(WorkSnapshot(repos, wts, error))
    }

    private fun publish(s: WorkSnapshot): WorkSnapshot {
        snapshot = s
        listeners.forEach { it(s) }
        return s
    }

    /** `repo.clone` of a git url or path; the bare clone is recorded with the repositories. */
    fun clone(url: String, name: String? = null): RepositoryAttacher.Repo {
        val r = svc.call("repo.clone", args("url" to url.trim(), "name" to name?.trim()?.takeIf { it.isNotEmpty() })).resultObject()
        refreshNow()
        return RepositoryAttacher.parseOne(r).copy(bare = true)
    }

    /**
     * M5.1: `worktree.start` (a fresh worktree from the latest default branch, [branch] blank = the daemon's `work-<timestamp>`), then the worktree is attached
     * to the project. Not attaching (no Gradle plugin, a name clash with another repository) is a [Working.problem], the worktree stays.
     */
    fun startWorking(repo: String, branch: String? = null): Working {
        val r = svc.call("worktree.start", args("repo" to repo, "branch" to branch?.trim()?.takeIf { it.isNotEmpty() })).resultObject()
        val wt = parseWorktree(r)
        return attach(wt, r.get("created-branch")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true)
    }

    /** Attaches the worktree's directory (what Attach Repository does); the outcome or the reason it did not happen. */
    fun attach(wt: Worktree, created: Boolean = false): Working {
        val working = try {
            Working(wt, attacher.attach(wt.path), null, created)
        } catch (e: DaemonException) {
            Working(wt, null, e.message + (e.hint?.let { " ($it)" } ?: ""), created)
        }
        refreshNow()
        return working
    }

    /**
     * `worktree.remove`; a worktree with changes is refused with the list (a [DaemonException]) unless [force]. The IDE half (module, content root) goes after the
     * daemon removed the directory, so a refusal leaves everything as it was.
     */
    fun removeWorktree(wt: Worktree, force: Boolean = false) {
        svc.call("worktree.remove", args("repo" to wt.repo, "name" to wt.name, "force" to (if (force) true else null)))
        val rec = runCatching { repos() }.getOrDefault(emptyList()).firstOrNull { sameDir(it.path, wt.path) }
        if (rec != null) runCatching { attacher.detach(rec.name) }
        refreshNow()
    }

    /**
     * M5.5: `pr.open` (needs a session: it writes the comments and the checks into one), then attaches the `pr-<n>` worktree. The comments file and the checks file are
     * for the caller to open; a GitHub half that failed is [PrOpened.note] (the worktree is there anyway).
     */
    fun openPr(repo: String, n: Int, github: String? = null): PrOpened {
        ScnRunner.getInstance(project).ensureSession(svc)
        val r = svc.call("pr.open", args("repo" to repo, "n" to n, "github" to github?.trim()?.takeIf { it.isNotEmpty() })).resultObject()
        val wtObj = r.getAsJsonObject("worktree") ?: throw DaemonException("pr.open returned no worktree", "pr/no-worktree")
        val wt = Worktree(repo, str(wtObj, "name") ?: "pr-$n", str(wtObj, "branch"), str(wtObj, "head")?.take(10), str(wtObj, "path") ?: "", false, 0)
        val working = attach(wt, wtObj.get("created")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false)
        val pr = r.getAsJsonObject("pr")
        val checks = r.getAsJsonObject("checks")
        val note = str(r, "github-error")?.let { e -> e + (str(r, "hint")?.let { " ($it)" } ?: "") } ?: str(r, "note") ?: str(wtObj, "hint")
        return PrOpened(repo, n, working, r.getAsJsonObject("comments")?.let { str(it, "path") }, checks?.let { str(it, "path") },
            pr?.let { str(it, "title") }, pr?.let { str(it, "state") }, checks?.let { checksLine(it) }, note, r)
    }

    companion object {
        fun getInstance(project: Project): WorkOps = project.getService(WorkOps::class.java)

        private fun str(o: JsonObject, k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.removePrefix(":")?.takeIf { it.isNotEmpty() }

        private fun sameDir(a: String, b: String) = try { java.nio.file.Path.of(a).toRealPath() == java.nio.file.Path.of(b).toRealPath() } catch (_: Exception) { a == b }

        fun parseWorktree(o: JsonObject) = Worktree(str(o, "repo") ?: "?", str(o, "name") ?: "?", str(o, "branch"), str(o, "head")?.take(10), str(o, "path") ?: "",
            o.get("missing")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false, o.get("dirty")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0)

        fun parseWorktrees(e: com.google.gson.JsonElement?): List<Worktree> =
            if (e != null && e.isJsonArray) e.asJsonArray.filter { it.isJsonObject }.map { parseWorktree(it.asJsonObject) } else emptyList()

        /** `3 runs: 2 success, 1 failure` from the checks block of `pr.open`. */
        fun checksLine(c: JsonObject): String {
            val n = c.get("count")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
            val by = c.getAsJsonObject("by-result")?.entrySet()?.joinToString(", ") { "${it.value.asString} ${it.key.removePrefix(":")}" }
            return "$n run" + (if (n == 1) "" else "s") + (if (by.isNullOrEmpty()) "" else ": $by")
        }
    }
}
