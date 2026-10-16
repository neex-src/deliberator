package com.neex.deliberator.run

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic
import com.neex.deliberator.daemon.DaemonClient
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.daemon.DaemonTerminal
import com.neex.deliberator.ui.Ui
import com.neex.deliberator.workspace.WorkspaceModel
import com.neex.deliberator.workspace.WorkspaceService
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

/** What happens to scenario runs of a project, on the EDT. Piece 07 (logs) hooks [runFinished]. */
interface ScnRunListener {
    /** The state of [path] changed (a run started, a step started or finished, the view's snapshot changed). */
    fun stateChanged(path: String) {}

    /** A run of [path] ended (any status, including cancelled) and its `result.txt` was read: [snapshot]. */
    fun runFinished(path: String, snapshot: RunSnapshot) {}

    companion object {
        @JvmField val TOPIC: Topic<ScnRunListener> = Topic.create("Deliberator scenario runs", ScnRunListener::class.java)
    }
}

/** What an event said about a step while the run is in flight (replaced by the file's section at the end). */
data class LiveStep(val n: Int, val status: String, val elapsedMs: Long?)

/** Per scenario file: is a run in flight, which, which steps, what the events said so far, the run the view shows. */
class RunState(val path: String) {
    @Volatile var running = false
    @Volatile var runId: String? = null
    @Volatile var call: DaemonClient.Call? = null
    /** Step numbers asked for; empty = all. */
    @Volatile var asked: List<Int> = emptyList()
    @Volatile var started: Set<Int> = emptySet()
    @Volatile var live: Map<Int, LiveStep> = emptyMap()
    @Volatile var snapshot: RunSnapshot? = null
    @Volatile var cancelRequested = false
    /** The run directory the daemon named in `run.started` (where the progressive `result.txt` is). */
    @Volatile var runDir: String? = null
    /** The progressive `result.txt` read during the run: the sections of the steps finished so far (null before the first read). */
    @Volatile var progress: RunSnapshot? = null
    @Volatile var finishedEvents = 0
    internal val reading = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile internal var dirty = false
}

/**
 * Runs scenario files through the daemon: one run per file at a time, runs of different files are independent.
 *
 * `run` saves the buffer, makes sure a session exists, sends `scenario.run {path env? steps confirm}` with
 * `cancel-on-close` (closing the project cancels), follows the events for the live state and, when the run ends,
 * reads `result.txt` (path from the daemon's answer, step numbers from `runs.show`) into a [RunSnapshot] that the
 * [RunView]s of that file render. The file is the truth; the events only say that something happened.
 * [ScnRunListener.TOPIC] announces state changes and finished runs on the EDT.
 */
@Service(Service.Level.PROJECT)
class ScnRunner(private val project: Project) : Disposable {
    private val states = ConcurrentHashMap<String, RunState>()

    /** Asked for each confirmation of a `confirm-required` run; the default is a dialog. Replaced by tests. */
    @Volatile var confirmer: (Confirmation) -> Boolean = { c ->
        var yes = false
        ApplicationManager.getApplication().invokeAndWait({
            yes = Messages.showYesNoDialog(project,
                "Step ${c.step} needs confirmation:\n\n${c.summary}\n\nRun it again with this confirmation?",
                "Confirm Remote Change", "Confirm and Run", "Cancel", Messages.getWarningIcon()) == Messages.YES
        }, ModalityState.nonModal())
        yes
    }

    /** Asked for a session key when none is current; null = do not run. Replaced by tests. */
    @Volatile var sessionKeyAsker: () -> String? = {
        var key: String? = null
        ApplicationManager.getApplication().invokeAndWait({
            key = Messages.showInputDialog(project, "Running a scenario needs a session. Session key:", "Start Session", null,
                "S-" + java.time.LocalDate.now().toString().replace("-", ""), null)?.trim()?.takeIf { it.isNotEmpty() }
        }, ModalityState.nonModal())
        key
    }

    override fun dispose() {
        states.values.forEach { it.call?.cancel() }
        states.clear()
    }

    fun state(path: String): RunState = states.computeIfAbsent(path) { RunState(it) }

    fun isRunning(path: String): Boolean = states[path]?.running == true

    // starting --------------------------------------------------------------------------------------

    /** Saves the buffer of [file], then runs the steps [steps] (1-based; null = all) in the background. EDT or any thread. */
    fun run(file: VirtualFile, steps: List<Int>?) {
        val doc = FileDocumentManager.getInstance().getCachedDocument(file)
        if (doc != null) {
            ApplicationManager.getApplication().invokeAndWait({ FileDocumentManager.getInstance().saveDocument(doc) }, ModalityState.nonModal())
        }
        val path = file.path
        val st = state(path)
        synchronized(st) {
            if (st.running) {
                Ui.notify(project, "Deliberator", "${file.name} is already running (Esc cancels it).", NotificationType.WARNING)
                return
            }
            st.running = true
            st.cancelRequested = false
            st.runId = null
            st.asked = steps.orEmpty()
            st.started = emptySet()
            st.live = emptyMap()
            st.progress = null
        }
        publish(path)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                execute(path, steps.orEmpty(), null)
            } catch (e: DaemonException) {
                Ui.notify(project, "Run ${file.name}", e.message + (e.hint?.let { "\n$it" } ?: ""), NotificationType.ERROR)
            } catch (e: Throwable) {
                Ui.notify(project, "Run ${file.name}", e.toString(), NotificationType.ERROR)
            } finally {
                st.running = false
                st.call = null
                st.progress = null
                publish(path)
            }
        }
    }

    /**
     * One `scenario.run` and what follows it: the reload, and the confirmations of `confirm-required` one by one
     * (each yes is another run of that one step). Blocking, off the EDT; the caller has set the running state.
     */
    fun execute(path: String, steps: List<Int>, confirm: String?): RunSnapshot? {
        val svc = WorkspaceService.getInstance(project)
        ensureSession(svc)
        val st = state(path)
        val args = JsonObject().apply {
            addProperty("path", path)
            if (svc.envChosen) addProperty("env", svc.selectedEnv)
            if (steps.isNotEmpty()) add("steps", JsonArray().also { a -> steps.forEach { a.add(it) } })
            if (confirm != null) addProperty("confirm", confirm)
        }
        st.asked = steps
        st.started = emptySet()
        st.live = emptyMap()
        st.progress = null
        st.runDir = null
        st.finishedEvents = 0
        val call = svc.start("scenario.run", args, true) { ev ->
            when (ev.name) {
                "run.started" -> {
                    st.runId = ev.json.get("run-id")?.asString
                    st.runDir = ev.json.get("run-dir")?.takeIf { it.isJsonPrimitive }?.asString
                    publish(path)
                    requestProgress(path, st)
                }
                "step.started" -> {
                    val n = ev.json.get("step")?.asInt ?: return@start
                    st.started = st.started + n
                    st.live = st.live + (n to LiveStep(n, "running", null))
                    publish(path)
                }
                "step.finished" -> {
                    val n = ev.json.get("step")?.asInt ?: return@start
                    val status = ev.json.get("status")?.asString?.removePrefix(":") ?: "?"
                    st.live = st.live + (n to LiveStep(n, status, ev.json.get("elapsed")?.takeIf { it.isJsonPrimitive }?.asLong))
                    st.finishedEvents++
                    publish(path)
                    requestProgress(path, st)
                }
            }
        }
        st.call = call
        if (st.cancelRequested) call.cancel()
        val terminal: DaemonTerminal = try { call.await() } catch (e: CancellationException) { return null }
        return afterTerminal(path, terminal, steps)
    }

    /** What a terminal means: a run to read, a confirmation to ask for, or an error. Public for the confirm test. */
    fun afterTerminal(path: String, terminal: DaemonTerminal, steps: List<Int>): RunSnapshot? {
        if (terminal.ok) {
            val runDir = terminal.resultObject().getAsJsonObject("paths")?.get("run")?.asString ?: return null
            return finished(path, loadSnapshot(runDir))
        }
        if (terminal.errorType == "confirm-required") {
            val err = terminal.error
            val runDir = err?.getAsJsonObject("result")?.getAsJsonObject("paths")?.get("run")?.asString
            val snap = runDir?.let { finished(path, loadSnapshot(it)) }
            var last = snap
            for (c in Confirmation.parse(err)) {
                if (!confirmer(c)) break
                last = execute(path, listOf(c.step), c.token) ?: last
            }
            return last
        }
        throw DaemonException(terminal.errorMessage ?: "scenario.run failed", terminal.errorType ?: "daemon/error",
            terminal.error?.get("hint")?.takeIf { it.isJsonPrimitive }?.asString)
    }

    private fun finished(path: String, snap: RunSnapshot): RunSnapshot {
        val st = state(path)
        st.snapshot = snap
        st.live = emptyMap()
        st.progress = null
        ApplicationManager.getApplication().invokeLater({
            project.messageBus.syncPublisher(ScnRunListener.TOPIC).runFinished(path, snap)
            project.messageBus.syncPublisher(ScnRunListener.TOPIC).stateChanged(path)
        }, ModalityState.nonModal())
        return snap
    }

    internal fun ensureSession(svc: WorkspaceService) {
        val cur = WorkspaceModel.parseSessions(svc.call("session.list").resultObject()).second
        if (cur != null) return
        val key = sessionKeyAsker() ?: throw DaemonException("no session: a scenario runs in a session", "run/no-session", "Deliberator | Start Session")
        svc.startSession(key, null)
    }

    // following the file ---------------------------------------------------------------------------

    /**
     * An event said something happened: re-read the run's progressive `result.txt` (through `runs.show`, the daemon names the file)
     * on a pooled thread, one read at a time, and publish. The file is the truth; the events only say when to look.
     */
    private fun requestProgress(path: String, st: RunState) {
        st.dirty = true
        if (!st.reading.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                while (st.dirty && st.running) {
                    st.dirty = false
                    val dir = st.runDir ?: break
                    var snap = readProgress(dir)
                    // core emits step.finished a moment BEFORE it rewrites result.txt (core finding): one short re-read, no more
                    if (st.running && (snap == null || snap.sections.size < st.finishedEvents)) {
                        Thread.sleep(150)
                        snap = readProgress(dir) ?: snap
                    }
                    if (st.running && snap != null) {
                        st.progress = snap
                        publish(path)
                    }
                }
            } finally {
                st.reading.set(false)
            }
            if (st.dirty && st.running) requestProgress(path, st)
        }
    }

    private fun readProgress(runDir: String): RunSnapshot? = try { loadSnapshot(runDir) } catch (_: Exception) { null }

    // cancelling ------------------------------------------------------------------------------------

    /** Esc: cancel the run of [path]. By run id once the daemon named it (the run closes properly), else by closing the request. */
    fun cancel(path: String) {
        val st = states[path] ?: return
        if (!st.running) return
        st.cancelRequested = true
        val id = st.runId
        if (id == null) { st.call?.cancel(); return }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                WorkspaceService.getInstance(project).call("scenario.cancel", JsonObject().apply { addProperty("run-id", id) })
            } catch (e: DaemonException) {
                Ui.notify(project, "Cancel", e.message ?: "cancel failed", NotificationType.WARNING)
            }
        }
    }

    // reading ---------------------------------------------------------------------------------------

    /** The run in [runRef] (a run directory the daemon named, or a run id): `runs.show`, then the file it names. Blocking. */
    fun loadSnapshot(runRef: String): RunSnapshot {
        val svc = WorkspaceService.getInstance(project)
        val shown = svc.call("runs.show", JsonObject().apply { addProperty("run", runRef) }).resultObject()
        val run = shown.getAsJsonObject("run")
        val paths = shown.getAsJsonObject("paths")
        val dir = paths?.get("run")?.asString ?: runRef
        val result = paths?.get("result")?.asString
        val ns = (run?.get("steps") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("n")?.takeIf { n -> n.isJsonPrimitive }?.asInt }.orEmpty()
        val status = run?.get("status")?.takeIf { it.isJsonPrimitive }?.asString?.removePrefix(":") ?: "?"
        val sha = run?.getAsJsonObject("scenario")?.get("sha256")?.takeIf { it.isJsonPrimitive }?.asString
        val id = run?.get("run-id")?.takeIf { it.isJsonPrimitive }?.asString ?: Path.of(dir).fileName.toString()
        val text = result?.let { runCatching { Files.readString(Path.of(it), StandardCharsets.UTF_8) }.getOrNull() }
        if (text == null) return RunSnapshot(id, dir, result, status, sha, emptyMap(), emptyList(), "", "no result.txt for this run")
        val (sections, trailer, problem) = ResultText.parse(text, ns)
        return RunSnapshot(id, dir, result, status, sha, sections, trailer, text, problem)
    }

    /** The newest run of the scenario file [path], or null. Blocking. */
    fun latest(path: String): RunSnapshot? {
        val row = history(path, 1).firstOrNull() ?: return null
        return loadSnapshot(row.path.ifBlank { row.runId })
    }

    /** `runs.list scenario=<absolute path>`, newest first (a path, not a name: two `order.scn` must not mix). Blocking. */
    fun history(path: String, limit: Int = 30): List<HistoryRow> =
        HistoryRow.parse(WorkspaceService.getInstance(project).call("runs.list", JsonObject().apply {
            addProperty("scenario", path)
            addProperty("limit", limit)
        }).resultObject())

    /** Shows [snap] (an older run) in the views of [path]. */
    fun show(path: String, snap: RunSnapshot) {
        state(path).snapshot = snap
        publish(path)
    }

    private fun publish(path: String) {
        ApplicationManager.getApplication().invokeLater({ project.messageBus.syncPublisher(ScnRunListener.TOPIC).stateChanged(path) }, ModalityState.nonModal())
    }

    companion object {
        fun getInstance(project: Project): ScnRunner = project.getService(ScnRunner::class.java)
    }
}
