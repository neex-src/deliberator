package com.neex.deliberator.logs

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.neex.deliberator.run.RunSnapshot
import com.neex.deliberator.run.ScnRunListener
import com.neex.deliberator.workspace.WorkspaceService
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A matched log line (1-based) of marker [markerId], and its highlighter in the editor that shows it. */
data class LogHit(val n: Int, val markerId: String)

private class EditorHighlights(val highlighters: MutableList<Pair<LogHit, RangeHighlighter>> = ArrayList(), val seq: AtomicInteger = AtomicInteger())

/**
 * Annotates open log files through `logs.match` (the daemon reads the file; nothing is parsed here): the lines that carry
 * a marked `logger:line` get a line highlight, an error-stripe mark and a tooltip. It runs after every finished scenario
 * run, after a marker is added or removed, when a log editor opens, and on demand; each run replaces the editor's old
 * highlights, and a late answer for an older request is dropped. It remembers the last matched line of each marker.
 */
@Service(Service.Level.PROJECT)
class LogMatcher(private val project: Project) : Disposable {
    private class Match(val seq: Long, val hits: List<LogHit>)

    private val matches = ConcurrentHashMap<String, Match>()
    private val clock = java.util.concurrent.atomic.AtomicLong()
    /** The id of the latest finished run, for the correlation filter's default. */
    @Volatile var latestRun: RunSnapshot? = null; private set

    init {
        val bus = project.messageBus.connect(this)
        bus.subscribe(ScnRunListener.TOPIC, object : ScnRunListener {
            override fun runFinished(path: String, snapshot: RunSnapshot) { latestRun = snapshot; annotateAll() }
        })
        bus.subscribe(MarkerListener.TOPIC, object : MarkerListener {
            override fun markersChanged() { annotateAll() }
        })
    }

    override fun dispose() { matches.clear() }

    fun logEditors(): List<Editor> = EditorFactory.getInstance().allEditors.filter { e ->
        e.project == project && !e.isDisposed && FileDocumentManager.getInstance().getFile(e.document)?.let { LogFiles.isLog(it) } == true
    }

    /** Annotates every open log; completes on the EDT when all are done. */
    fun annotateAll(): CompletableFuture<Unit> =
        CompletableFuture.allOf(*logEditors().map { annotate(it) }.toTypedArray()).thenApply { }

    /** `logs.match` of this editor's file, then the highlights. The future completes on the EDT after they are applied (or dropped). */
    fun annotate(editor: Editor): CompletableFuture<Unit> {
        val done = CompletableFuture<Unit>()
        val vf = FileDocumentManager.getInstance().getFile(editor.document)
        val svc = WorkspaceService.getInstance(project)
        if (vf == null || svc.ws == null) { done.complete(Unit); return done }
        val state = stateOf(editor)
        val seq = state.seq.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            val hits = try {
                val r = svc.call("logs.match", JsonObject().apply { addProperty("path", vf.path) }).resultObject()
                (r.get("lines") as? JsonArray)?.mapNotNull { x ->
                    val o = x as? JsonObject ?: return@mapNotNull null
                    LogHit(o.get("n")?.asInt ?: return@mapNotNull null, o.get("marker-id")?.asString ?: return@mapNotNull null)
                } ?: emptyList()
            } catch (e: Exception) {
                LOG.info("logs.match ${vf.path}: ${e.message}")
                null
            }
            ApplicationManager.getApplication().invokeLater({
                try {
                    if (hits != null && !editor.isDisposed && !project.isDisposed && state.seq.get() == seq) apply(editor, vf.path, hits)
                } finally { done.complete(Unit) }
            }, ModalityState.nonModal())
        }
        return done
    }

    private fun apply(editor: Editor, path: String, hits: List<LogHit>) {
        val state = stateOf(editor)
        state.highlighters.forEach { editor.markupModel.removeHighlighter(it.second) }
        state.highlighters.clear()
        val doc = editor.document
        val markers = MarkerService.getInstance(project)
        for (hit in hits) {
            if (hit.n < 1 || hit.n > doc.lineCount) continue
            val h = editor.markupModel.addLineHighlighter(MATCHED, hit.n - 1, HighlighterLayer.SELECTION - 1)
            val m = markers.byId(hit.markerId)
            h.setErrorStripeMarkColor(STRIPE)
            h.setErrorStripeTooltip("marker " + (m?.let { "${it.logger ?: Path.of(it.file).fileName}:${it.line}" } ?: hit.markerId))
            state.highlighters += hit to h
        }
        matches[path] = Match(clock.incrementAndGet(), hits)
        project.messageBus.syncPublisher(MarkerListener.TOPIC).matchesChanged(path)
    }

    /** The highlighted lines of [editor] now: 1-based line and marker id, in line order. */
    fun highlights(editor: Editor): List<LogHit> =
        editor.getUserData(KEY)?.highlighters?.filter { it.second.isValid }?.map { it.first }?.sortedBy { it.n } ?: emptyList()

    /** The latest match of a marker: the log file and its highest matched line, from the most recently annotated log that has one. */
    fun lastMatch(markerId: String): Pair<String, Int>? =
        matches.entries.filter { e -> e.value.hits.any { it.markerId == markerId } }
            .maxByOrNull { it.value.seq }?.let { e -> e.key to e.value.hits.filter { it.markerId == markerId }.maxOf { it.n } }

    /** Opens [path] in the editor with the caret on 1-based [line]. EDT. */
    fun open(path: String, line: Int): Editor? {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(path)) ?: return null
        LogFiles.register(path)
        return FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, vf, maxOf(0, line - 1), 0), true)
    }

    /** Forgets the latest run and every match (the light test project outlives a test). */
    fun reset() { latestRun = null; matches.clear() }

    fun forget(editor: Editor) { editor.getUserData(KEY)?.let { s -> s.seq.incrementAndGet(); s.highlighters.clear() } }

    private fun stateOf(editor: Editor): EditorHighlights =
        editor.getUserData(KEY) ?: EditorHighlights().also { editor.putUserData(KEY, it) }

    companion object {
        private val LOG = logger<LogMatcher>()
        private val KEY = Key.create<EditorHighlights>("deliberator.logHighlights")
        val MATCHED: TextAttributesKey = TextAttributesKey.createTextAttributesKey("DELIBERATOR_MARKED_LOG_LINE", EditorColors.SEARCH_RESULT_ATTRIBUTES)
        private val STRIPE = JBColor(java.awt.Color(0xE0A100), java.awt.Color(0xE0A100))
        fun getInstance(project: Project): LogMatcher = project.getService(LogMatcher::class.java)
    }
}
