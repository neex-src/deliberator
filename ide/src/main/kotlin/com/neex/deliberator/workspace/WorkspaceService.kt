package com.neex.deliberator.workspace

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.neex.deliberator.daemon.DaemonClient
import com.neex.deliberator.daemon.DaemonEvent
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.daemon.DaemonService
import com.neex.deliberator.daemon.DaemonTerminal
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A project's view of its Deliberator workspace (project level; the binding and the selected
 * environment live in `.idea/deliberator.xml`).
 *
 * How later pieces use it:
 *  - [ws] is the workspace name or null for an unbound project; [call] sends any op with that `ws`
 *    added (always), off the EDT; [client] is the shared daemon client.
 *  - [selectedEnv] is the environment the user picked (default `local`); [selectEnv] changes it.
 *  - [model] is the last snapshot ([WorkspaceModel]); [refreshNow] (blocking) / [refresh] (async)
 *    re-read it from the daemon; [addListener] is called on the EDT after every change.
 *  - the mutating methods ([startSession], [endSession], [runtimeUp], [runtimeDown], [runtimeLogs])
 *    block, call one op each and refresh; call them from a background task.
 *
 * Binding order ([resolve]): the persisted `ws`; else the convention that the project directory is
 * `<workspace directory>/project` (the daemon confirms: `workspace.show` of the parent's name must
 * answer the parent as its `path`, so Kotlin never learns the store layout); else unbound.
 */
@Service(Service.Level.PROJECT)
@State(name = "DeliberatorProject", storages = [Storage("deliberator.xml")])
class WorkspaceService(private val project: Project) : PersistentStateComponent<WorkspaceService.State>, Disposable {
    class State {
        var ws: String = ""
        var env: String = ""
        /** `kind<TAB>path` of every Gradle/Maven linkage this plugin made (the platform persists the linkage itself, not who made it). */
        var repos: MutableList<String> = ArrayList()
    }

    private var state = State()
    override fun getState(): State = state
    override fun loadState(s: State) { state = s }

    @Volatile var model: WorkspaceModel = WorkspaceModel.unbound(); private set
    private val listeners = CopyOnWriteArrayList<(WorkspaceModel) -> Unit>()
    @Volatile private var conventionTried = false

    override fun dispose() { listeners.clear() }

    val ws: String? get() = state.ws.ifBlank { null }

    val selectedEnv: String get() = state.env.ifBlank { WorkspaceModel.DEFAULT_ENV }

    fun client(): DaemonClient = DaemonService.getInstance().client()

    /** Binds this project to workspace [name] (persisted) and publishes an empty model for it. */
    fun bind(name: String) {
        state.ws = name
        conventionTried = true
        publish(WorkspaceModel(name, selectedEnv = selectedEnv))
    }

    fun selectEnv(name: String) {
        state.env = name
        publish(model.copy(selectedEnv = name))
    }

    /** The bound workspace, applying the directory convention once when nothing is persisted. Blocking. */
    fun resolve(): String? {
        ws?.let { return it }
        if (conventionTried) return null
        conventionTried = true
        val found = project.basePath?.let { conventionWs(it) { client() } }
        if (found != null) state.ws = found
        return found
    }

    /** One op with the project's `ws` added to [args] (an unbound project is an error). Blocking, off the EDT. */
    fun call(op: String, args: JsonObject = JsonObject(), onEvent: (DaemonEvent) -> Unit = {}): DaemonTerminal {
        val w = ws ?: throw DaemonException("this project is not bound to a workspace", "workspace/unbound",
            "Deliberator | Open workspace... or Use workspace for this project...")
        val a = JsonObject()
        args.entrySet().forEach { a.add(it.key, it.value) }
        a.addProperty("ws", w)
        val t = client().call(op, a, onEvent)
        if (!t.ok) throw DaemonException(t.errorMessage ?: "$op failed", t.errorType ?: "daemon/error", t.error?.get("hint")?.takeIf { it.isJsonPrimitive }?.asString)
        return t
    }

    /** As [call] but returns the request in flight (events to [onEvent], [DaemonClient.Call.cancel] closes it); `ws` added. Off the EDT. */
    fun start(op: String, args: JsonObject = JsonObject(), cancelOnClose: Boolean = true, onEvent: (DaemonEvent) -> Unit = {}): DaemonClient.Call {
        val w = ws ?: throw DaemonException("this project is not bound to a workspace", "workspace/unbound",
            "Deliberator | Open workspace... or Use workspace for this project...")
        val a = JsonObject()
        args.entrySet().forEach { a.add(it.key, it.value) }
        a.addProperty("ws", w)
        return client().start(op, a, cancelOnClose, onEvent)
    }

    /** True when the user picked an environment (else the scenario's own `@env`, then `local`, decides). */
    val envChosen: Boolean get() = state.env.isNotBlank()

    private fun obj(op: String, args: JsonObject = JsonObject()) = call(op, args).resultObject()

    // reading ------------------------------------------------------------------------------------

    @Synchronized
    fun refreshNow(): WorkspaceModel {
        val name = try { resolve() } catch (e: DaemonException) {
            return publish(model.copy(error = describe(e), fetchedAt = Instant.now()))
        }
        if (name == null) return publish(WorkspaceModel.unbound().copy(fetchedAt = Instant.now()))
        var m = model.takeIf { it.ws == name } ?: WorkspaceModel(name)
        var error: String? = null
        fun <T> part(f: () -> T): T? = try { f() } catch (e: DaemonException) { if (error == null) error = describe(e); null }
        val shown = try { obj("workspace.show") } catch (e: DaemonException) {
            // a workspace the daemon does not know is a state of its own (never created by a refresh): nothing else is asked
            if (e.type == "workspace/not-found") return publish(WorkspaceModel(name, selectedEnv = selectedEnv, missing = true, fetchedAt = Instant.now()))
            error = describe(e); null
        }
        shown?.let { m = m.copy(path = it.get("path")?.asString, missing = false) }
        part { WorkspaceModel.parseSessions(obj("session.list")) }?.let { (s, c) -> m = m.copy(sessions = s, currentSession = c) }
        part { WorkspaceModel.parseRuntime(obj("runtime.status")) }?.let { (r, h, c) -> m = m.copy(engineReachable = r, engineHint = h, containers = c) }
        part { WorkspaceModel.parseEnvs(obj("env.list")) }?.let { m = m.copy(envs = it) }
        m = if (m.currentSession != null) {
            part { WorkspaceModel.parseRuns(obj("runs.list", JsonObject().apply { addProperty("limit", 20) })) }?.let { m.copy(runs = it) } ?: m
        } else m.copy(runs = emptyList())
        return publish(m.copy(selectedEnv = selectedEnv, error = error, fetchedAt = Instant.now()))
    }

    /** Re-reads the model on a pooled thread; the listeners hear about it on the EDT. */
    fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread { refreshNow() }
    }

    // acting -------------------------------------------------------------------------------------

    /** `workspace.open` for the bound workspace (the one way a missing workspace comes to exist); refreshes. Blocking. */
    fun openWorkspace(): JsonObject {
        val r = call("workspace.open").resultObject()
        refreshNow()
        return r
    }

    fun startSession(key: String, title: String?): WorkspaceModel {
        call("session.start", JsonObject().apply { addProperty("key", key); if (!title.isNullOrBlank()) addProperty("title", title) })
        return refreshNow()
    }

    /** Ends [key], default the current session. */
    fun endSession(key: String? = null): WorkspaceModel {
        call("session.end", JsonObject().apply { if (key != null) addProperty("key", key) })
        return refreshNow()
    }

    fun runtimeUp(onEvent: (DaemonEvent) -> Unit = {}): WorkspaceModel {
        try { call("runtime.ensure", JsonObject(), onEvent) } finally { refreshNow() }
        return model
    }

    fun runtimeDown(): WorkspaceModel {
        try { call("runtime.down") } finally { refreshNow() }
        return model
    }

    /** The last [tail] lines of [role]'s log; `paths` are the files the daemon mirrored them to (open those). */
    fun runtimeLogs(role: String, tail: Int = 500): JsonObject =
        call("runtime.logs", JsonObject().apply { addProperty("role", role); addProperty("tail", tail) }).resultObject()

    // listeners ----------------------------------------------------------------------------------

    /** [l] is called on the EDT with every new model; removed when [parent] is disposed. */
    fun addListener(parent: Disposable, l: (WorkspaceModel) -> Unit) {
        listeners += l
        Disposer.register(parent) { listeners -= l }
    }

    private fun publish(m: WorkspaceModel): WorkspaceModel {
        model = m
        val app = ApplicationManager.getApplication()
        for (l in listeners) app.invokeLater({ l(m) }, ModalityState.any())
        return m
    }

    private fun describe(e: DaemonException) = e.message + (e.hint?.let { " ($it)" } ?: "")

    companion object {
        /**
         * The workspace [basePath] is the project directory of, or null: the directory is named `project`
         * and `workspace.show` of its parent's name answers that parent as its path.
         */
        fun conventionWs(basePath: String, client: DaemonClient): String? = conventionWs(basePath) { client }

        /** As above, the client is only asked for once the directory is named `project` (an ordinary project never starts the daemon). */
        fun conventionWs(basePath: String, client: () -> DaemonClient): String? {
            val p = Path.of(basePath).toAbsolutePath().normalize()
            if (p.fileName?.toString() != "project") return null
            val parent = p.parent ?: return null
            val name = parent.fileName?.toString() ?: return null
            val t = try { client().call("workspace.show", JsonObject().apply { addProperty("ws", name) }) } catch (_: DaemonException) { return null }
            if (!t.ok) return null
            val shown = t.resultObject().get("path")?.asString ?: return null
            return if (sameDir(Path.of(shown), parent)) name else null
        }

        private fun sameDir(a: Path, b: Path) = try { a.toRealPath() == b.toRealPath() } catch (_: Exception) { a.toAbsolutePath().normalize() == b.toAbsolutePath().normalize() }

        fun getInstance(project: Project): WorkspaceService = project.getService(WorkspaceService::class.java)
    }
}
