/**
 * Source markers: a marker is a source line whose log lines the user wants highlighted. The truth lives in the
 * daemon (the daemon's marker store, ops `marker.*`); [MarkerService] only caches the last `marker.list` for drawing, and
 * every change goes to the daemon first. The logger of a marker (what a log line names as `logger:line`) is the
 * FQN of the outermost class at that line, derived by a [LoggerResolver] (Java PSI when the Java plugin is there);
 * without one no logger is sent and the daemon compares the file's base name.
 */
package com.neex.deliberator.logs

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.workspace.WorkspaceService
import java.util.concurrent.atomic.AtomicBoolean

/** One entry of `marker.list`. */
data class Marker(val id: String, val file: String, val line: Int, val logger: String?) {
    companion object {
        fun parse(e: JsonElement?): List<Marker> = (e as? JsonArray)?.mapNotNull { x ->
            val o = x as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString
            Marker(s("id") ?: return@mapNotNull null, s("file") ?: return@mapNotNull null,
                o.get("line")?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull() ?: return@mapNotNull null, s("logger"))
        } ?: emptyList()
    }
}

/** Derives the logger name of the code at a line; one per language support, registered by the optional plugin configs. */
interface LoggerResolver {
    /** The logger (class FQN) of the code at 0-based [line0] of [file], or null. Runs in a read action. */
    fun loggerAt(project: Project, file: VirtualFile, line0: Int): String?

    companion object {
        val EP: ExtensionPointName<LoggerResolver> = ExtensionPointName.create("com.neex.deliberator.loggerResolver")
    }
}

/** Marker list and last-match changes, on the EDT. */
interface MarkerListener {
    /** The cached marker list changed (add, remove, refresh from the daemon). */
    fun markersChanged() {}

    /** The matches of the log at [path] were recomputed (its highlights are up to date). */
    fun matchesChanged(path: String) {}

    companion object {
        @JvmField val TOPIC: Topic<MarkerListener> = Topic.create("Deliberator markers", MarkerListener::class.java)
    }
}

@Service(Service.Level.PROJECT)
class MarkerService(private val project: Project) : Disposable {
    @Volatile var markers: List<Marker> = emptyList(); private set
    @Volatile var loaded = false; private set
    private val loading = AtomicBoolean(false)

    override fun dispose() {}

    fun markerAt(file: String, line: Int): Marker? = markers.firstOrNull { it.file == file && it.line == line }

    fun markersOf(file: String): List<Marker> = markers.filter { it.file == file }

    fun byId(id: String): Marker? = markers.firstOrNull { it.id == id }

    /** The logger for [line0] of [file] from the first resolver that knows. Takes the read lock itself. */
    fun loggerFor(file: VirtualFile, line0: Int): String? =
        com.intellij.openapi.application.ReadAction.compute<String?, RuntimeException> {
            LoggerResolver.EP.extensionList.firstNotNullOfOrNull { r -> runCatching { r.loggerAt(project, file, line0) }.getOrNull() }
        }

    // calls, blocking and off the EDT ----------------------------------------------------------------

    fun refreshNow(): List<Marker> {
        val r = WorkspaceService.getInstance(project).call("marker.list")
        return accept(r.result)
    }

    fun addNow(file: String, line: Int, logger: String?): List<Marker> {
        val a = JsonObject().apply { addProperty("file", file); addProperty("line", line); logger?.let { addProperty("logger", it) } }
        return accept(WorkspaceService.getInstance(project).call("marker.add", a).result)
    }

    fun removeNow(file: String, line: Int): List<Marker> {
        val a = JsonObject().apply { addProperty("file", file); addProperty("line", line) }
        return accept(WorkspaceService.getInstance(project).call("marker.remove", a).result)
    }

    /** A marker that moved with edits: the new line first (so a failure drops nothing), then the old one. */
    fun moveNow(m: Marker, newLine: Int): List<Marker> {
        addNow(m.file, newLine, m.logger)
        return if (newLine == m.line) markers else removeNow(m.file, m.line)
    }

    /** Add a marker at [line] (1-based) or remove the one there. Blocking. Returns true when one was added. */
    fun toggleNow(file: String, line: Int, logger: String?): Boolean {
        refreshNow()
        return if (markerAt(file, line) != null) { removeNow(file, line); false } else { addNow(file, line, logger); true }
    }

    /** Loads the list once per project session, in the background; a failure leaves the list empty (the next editor tries again). */
    fun ensureLoaded() {
        if (loaded || WorkspaceService.getInstance(project).ws == null || !loading.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try { refreshNow() } catch (_: Exception) {} finally { loading.set(false) }
        }
    }

    private fun accept(r: JsonElement?): List<Marker> {
        val list = Marker.parse(r)
        markers = list
        loaded = true
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) project.messageBus.syncPublisher(MarkerListener.TOPIC).markersChanged()
        }, ModalityState.nonModal())
        return list
    }

    companion object {
        fun getInstance(project: Project): MarkerService = project.getService(MarkerService::class.java)
    }
}

/** Which files are logs: the ones the plugin fetched, and any `*.log` (the daemon names fetched logs `<target>-<ts>.log`). */
object LogFiles {
    private val known = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun register(path: String) { known += path }

    fun isLog(file: VirtualFile): Boolean = file.path in known || file.extension.equals("log", ignoreCase = true)
}
