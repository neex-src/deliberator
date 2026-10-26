package com.neex.deliberator.logs

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.ui.Ui
import javax.swing.Icon

/** The gutter icon of one marker. Click: the marker's last matched log line. Right-click: remove. */
class MarkerIcon(private val gutter: MarkerGutter, val marker: Marker) : GutterIconRenderer(), DumbAware {
    override fun getIcon(): Icon = AllIcons.General.Locate
    override fun getTooltipText(): String {
        val last = gutter.matcher.lastMatch(marker.id)
        return "Deliberator marker ${marker.logger ?: "(file name)"}:${marker.line}" +
            (last?.let { " - last matched line ${it.second} of ${java.nio.file.Path.of(it.first).fileName}" + (gutter.matcher.beyond(it.first)?.takeIf { b -> it.second > b.loadedLines }?.let { b -> " (beyond the ${b.loadedLines} lines the editor loaded)" } ?: "") } ?: " - no matched log line yet")
    }
    override fun getAlignment(): Alignment = Alignment.LEFT
    override fun isNavigateAction(): Boolean = true
    override fun equals(other: Any?): Boolean = other is MarkerIcon && other.marker == marker
    override fun hashCode(): Int = marker.hashCode()

    override fun getClickAction(): AnAction = object : AnAction(), DumbAware {
        override fun actionPerformed(e: AnActionEvent) { gutter.navigate(marker) }
    }

    override fun getPopupMenuActions(): ActionGroup = DefaultActionGroup(object : AnAction("Remove Marker"), DumbAware {
        override fun actionPerformed(e: AnActionEvent) { gutter.remove(marker) }
    })
}

/** The marker icons of one source editor, drawn from [MarkerService]'s list; the daemon is the only place they live. */
class MarkerGutter(val editor: Editor, val project: Project, val file: VirtualFile) : Disposable {
    val matcher = LogMatcher.getInstance(project)
    private val service = MarkerService.getInstance(project)
    /** Mutated on the EDT (redraw), read by the save hook on a pooled thread ([syncMoved]): every access goes through [drawnNow]/[setDrawn]. */
    private val lock = Any()
    private var drawn: List<Pair<Marker, RangeHighlighter>> = emptyList()

    private fun drawnNow(): List<Pair<Marker, RangeHighlighter>> = synchronized(lock) { drawn }
    private fun setDrawn(l: List<Pair<Marker, RangeHighlighter>>) = synchronized(lock) { drawn = l }

    init {
        project.messageBus.connect(this).subscribe(MarkerListener.TOPIC, object : MarkerListener {
            override fun markersChanged() = redraw()
        })
        redraw()
    }

    /** The icons now in the gutter, by 1-based line. */
    fun icons(): Map<Int, MarkerIcon> = drawnNow().filter { it.second.isValid }.associate { it.first.line to (it.second.gutterIconRenderer as MarkerIcon) }

    fun redraw() {
        if (editor.isDisposed) return
        clear()
        val doc = editor.document
        val out = ArrayList<Pair<Marker, RangeHighlighter>>()
        for (m in service.markersOf(file.path)) {
            if (m.line < 1 || m.line > doc.lineCount) continue
            val h = editor.markupModel.addLineHighlighter(null, m.line - 1, HighlighterLayer.FIRST)
            h.gutterIconRenderer = MarkerIcon(this, m)
            out += m to h
        }
        setDrawn(out)
    }

    private fun clear() {
        val old = drawnNow()
        setDrawn(emptyList())
        if (!editor.isDisposed) old.forEach { editor.markupModel.removeHighlighter(it.second) }
    }

    /** Jump to the last log line the marker matched, opening that log. False (with a balloon) when it never matched. */
    fun navigate(marker: Marker): Boolean {
        val last = matcher.lastMatch(marker.id)
        if (last == null) {
            Ui.notify(project, "Marker", "${marker.logger ?: file.name}:${marker.line} has no matched log line yet: fetch a log, then Deliberator | Match Markers in Logs")
            return false
        }
        return matcher.open(last.first, last.second) != null
    }

    fun remove(marker: Marker) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try { service.removeNow(marker.file, marker.line) } catch (e: DaemonException) { Ui.notify(project, "Remove marker", e.message ?: "failed") }
        }
    }

    /**
     * Markers whose line moved with edits are re-saved at the new line (blocking; the save hook runs it in the background).
     * A marked line that was deleted has no valid highlighter left: its marker stays where it was, removed only by hand.
     */
    fun syncMoved(): Int {
        val snapshot = drawnNow()
        val moves = com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction(com.intellij.openapi.util.Computable<List<Pair<Marker, Int>>> {
            snapshot.filter { it.second.isValid }.mapNotNull { (m, h) ->
                val now = editor.document.getLineNumber(h.startOffset) + 1
                if (now != m.line) m to now else null
            }
        })
        for ((m, line) in moves) service.moveNow(m, line)
        return moves.size
    }

    override fun dispose() = clear()

    companion object {
        private val KEY = Key.create<MarkerGutter>("deliberator.markerGutter")
        fun of(editor: Editor): MarkerGutter? = editor.getUserData(KEY)
        internal fun attach(editor: Editor, project: Project, file: VirtualFile): MarkerGutter {
            of(editor)?.let { return it }
            return MarkerGutter(editor, project, file).also { editor.putUserData(KEY, it) }
        }
        internal fun detach(editor: Editor) { of(editor)?.let { Disposer.dispose(it); editor.putUserData(KEY, null) } }
    }
}
