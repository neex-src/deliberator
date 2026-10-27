package com.neex.deliberator.repo

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.workspace.WorkspaceService
import java.nio.file.Path

/**
 * Attach and detach repositories of the project's workspace, without reopening. The daemon's record (`repo.*`) is the truth;
 * the IDE half is done by the [RepositoryKind] of the repository's kind. Every method blocks and talks to the daemon: call
 * from a background task, never the EDT.
 *
 *  - [attach]: `repo.attach`, then the IDE half. A kind whose plugin is missing ("Gradle plugin not available") is refused
 *    and the record it just made is removed again.
 *  - [detach]: the IDE half, then `repo.detach`.
 *  - [reattachAll]: the record both ways, used when a project opens: every `repo.list` entry the project lacks is attached,
 *    and every linkage or plain module this plugin made whose repository is no longer listed (a detach done through `dlb`
 *    while the IDE was closed) is detached. Gradle/Maven linkage made by this plugin is remembered in `.idea/deliberator.xml`
 *    (the platform persists the linkage, not who made it); plain modules are recognised by their entity source.
 */
@Service(Service.Level.PROJECT)
class RepositoryAttacher(private val project: Project) {
    /** [bare]: a bare clone of `repo.clone` (no work tree: it cannot be a module or a project; `worktree.start` makes one). [url] is where a clone came from. */
    data class Repo(val name: String, val path: String, val kind: String, val attachedAt: String?, val bare: Boolean = false, val url: String? = null)

    /** [note] is set when there was nothing to do in the IDE ("already part of module X"). */
    data class Outcome(val repo: Repo, val note: String?)

    data class Report(val attached: List<String> = emptyList(), val detached: List<String> = emptyList(), val problems: List<String> = emptyList()) {
        val empty get() = attached.isEmpty() && detached.isEmpty() && problems.isEmpty()
    }

    private val ws get() = WorkspaceService.getInstance(project)

    /**
     * The repositories the project may open: the checkouts with a work tree (`repo.list {bare false}`, B27). The bare clones of `repo.clone`
     * are only listed with [includeBare] (the Start Working chooser): attaching one as a module or project is what B27 was.
     */
    fun list(includeBare: Boolean = false): List<Repo> =
        parse(ws.call("repo.list", JsonObject().apply { if (!includeBare) addProperty("bare", false) }).result)

    fun attach(path: String, kind: String? = null): Outcome {
        val before = list().map { it.name }.toSet()
        val args = JsonObject().apply { addProperty("path", path); if (kind != null) addProperty("kind", kind) }
        val repo = parseOne(ws.call("repo.attach", args).resultObject())
        val impl = RepositoryKind.forKind(repo.kind)
        if (impl == null) {
            if (repo.name !in before) ws.call("repo.detach", JsonObject().apply { addProperty("name", repo.name) })
            throw pluginMissing(repo.kind)
        }
        val note = impl.attach(project, Path.of(repo.path))
        if (note == null) remember(repo.kind, repo.path)
        ws.refreshNow()
        return Outcome(repo, note)
    }

    fun detach(name: String): Repo {
        val repo = list().firstOrNull { it.name == name }
        if (repo == null) { ws.call("repo.detach", JsonObject().apply { addProperty("name", name) }); error("unreachable") }
        RepositoryKind.forKind(repo.kind)?.detach(project, Path.of(repo.path))
        forget(repo.kind, repo.path)
        ws.call("repo.detach", JsonObject().apply { addProperty("name", name) })
        ws.refreshNow()
        return repo
    }

    /** The IDE half of [detach] only, the record untouched (what closing the IDE does to non-persistent state; tests). */
    fun detachIdeOnly(dir: Path) {
        for (impl in RepositoryKind.EP.extensionList) impl.detach(project, dir)
    }

    fun reattachAll(): Report {
        val repos = try { list() } catch (e: DaemonException) { return Report(problems = listOf(e.message ?: "repo.list failed")) }
        val attached = mutableListOf<String>(); val detached = mutableListOf<String>(); val problems = mutableListOf<String>()
        for (r in repos) {
            val impl = RepositoryKind.forKind(r.kind)
            if (impl == null) { problems += "${r.name}: ${pluginMissing(r.kind).message}"; continue }
            val dir = Path.of(r.path)
            try {
                if (!java.nio.file.Files.isDirectory(dir)) { problems += "${r.name}: ${r.path} is not a directory any more"; continue }
                if (impl.isAttached(project, dir)) continue
                if (impl.attach(project, dir) == null) { remember(r.kind, r.path); attached += r.name }
            } catch (e: Exception) {
                problems += "${r.name}: ${e.message}"
            }
        }
        val listed = repos.map { it.kind to norm(it.path) }.toSet()
        for (impl in RepositoryKind.EP.extensionList) {
            val mine = (impl.owned(project).map { it.toString() } + remembered(impl.kind)).map { norm(it) }.toSet()
            for (p in mine) {
                if (impl.kind to p in listed) continue
                try { impl.detach(project, Path.of(p)); forget(impl.kind, p); detached += Path.of(p).fileName.toString() }
                catch (e: Exception) { problems += "${Path.of(p).fileName}: ${e.message}" }
            }
        }
        if (attached.isNotEmpty() || detached.isNotEmpty()) ws.refreshNow()
        return Report(attached, detached, problems)
    }

    // the plugin's own memory of linkages it made ----------------------------------------------------

    private fun norm(p: String) = Path.of(p).normalize().toString()
    private fun key(kind: String, path: String) = kind + "\t" + norm(path)

    @Synchronized private fun remember(kind: String, path: String) {
        if (kind == "plain") return // recognised by its entity source
        val l = ws.state.repos
        if (key(kind, path) !in l) l += key(kind, path)
    }

    @Synchronized private fun forget(kind: String, path: String) { ws.state.repos.remove(key(kind, path)) }

    @Synchronized private fun remembered(kind: String): List<String> =
        ws.state.repos.filter { it.startsWith(kind + "\t") }.map { it.substringAfter('\t') }

    companion object {
        fun getInstance(project: Project): RepositoryAttacher = project.getService(RepositoryAttacher::class.java)

        fun pluginMissing(kind: String) = DaemonException("${kind.replaceFirstChar { it.uppercase() }} plugin not available", "repo/plugin-not-available",
            "enable the bundled ${kind.replaceFirstChar { it.uppercase() }} plugin in Settings | Plugins; the repository was not attached")

        private fun s(o: JsonObject, k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.removePrefix(":")

        fun parseOne(o: JsonObject) = Repo(s(o, "name") ?: "?", s(o, "path") ?: "", s(o, "kind") ?: "plain", s(o, "attached-at"),
            o.get("bare")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false, s(o, "url"))

        fun parse(e: JsonElement?): List<Repo> =
            if (e != null && e.isJsonArray) e.asJsonArray.filter { it.isJsonObject }.map { parseOne(it.asJsonObject) } else emptyList()
    }
}
