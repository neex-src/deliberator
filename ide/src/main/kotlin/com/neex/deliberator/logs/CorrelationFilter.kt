package com.neex.deliberator.logs

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.WindowManager

/** What a filter left: [kept] of [total] entries match [id]; [hiddenLines] lines sit in [regions] fold regions. */
data class FilterState(val id: String, val kept: Int, val total: Int, val hiddenLines: Int, val regions: Int) {
    val status: String get() = "Deliberator: $kept of $total log entries match $id ($hiddenLines lines folded)"
}

/**
 * The correlation filter of a log editor: entries (a timestamped header line and the lines under it, such as a stack
 * trace) that do not contain the id are folded in place with fold regions. The document is never changed and nothing is
 * copied; [clear] unfolds. The count goes to the status bar and to [stateOf].
 */
object CorrelationFilter {
    private class Folds(val state: FilterState, val regions: List<FoldRegion>)

    private val KEY = Key.create<Folds>("deliberator.correlationFilter")
    private val HEADER = Regex("^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}")

    /** Entries as inclusive 0-based line ranges: a new one at each header line; lines before the first header are one entry. */
    fun entries(lines: List<String>): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = 0
        for (i in lines.indices) if (i > start && HEADER.containsMatchIn(lines[i])) { out += start until i; start = i }
        if (lines.isNotEmpty()) out += start until lines.size
        return out
    }

    fun stateOf(editor: Editor): FilterState? = editor.getUserData(KEY)?.state

    /** Folds the entries without [id]; replaces an earlier filter. Needs the EDT. */
    fun apply(editor: Editor, project: Project?, id: String): FilterState {
        clear(editor, project)
        val doc = editor.document
        val lines = (0 until doc.lineCount).map { doc.getText(com.intellij.openapi.util.TextRange(doc.getLineStartOffset(it), doc.getLineEndOffset(it))) }
        val entries = entries(lines)
        val hide = entries.filter { r -> r.none { lines[it].contains(id) } }
        // adjacent hidden entries become one region
        val blocks = ArrayList<IntRange>()
        for (r in hide) if (blocks.isNotEmpty() && blocks.last().last + 1 == r.first) blocks[blocks.size - 1] = blocks.last().first..r.last else blocks += r
        val regions = ArrayList<FoldRegion>()
        editor.foldingModel.runBatchFoldingOperation {
            for (b in blocks) {
                val n = b.last - b.first + 1
                editor.foldingModel.addFoldRegion(doc.getLineStartOffset(b.first), doc.getLineEndOffset(b.last), "··· $n line" + (if (n == 1) "" else "s") + " hidden ···")
                    ?.also { it.isExpanded = false; regions += it }
            }
        }
        val state = FilterState(id, entries.size - hide.size, entries.size, blocks.sumOf { it.last - it.first + 1 }, regions.size)
        editor.putUserData(KEY, Folds(state, regions))
        status(project, state.status)
        return state
    }

    /** Unfolds what [apply] folded. Needs the EDT. */
    fun clear(editor: Editor, project: Project?) {
        val f = editor.getUserData(KEY) ?: return
        editor.foldingModel.runBatchFoldingOperation { f.regions.forEach { if (it.isValid) editor.foldingModel.removeFoldRegion(it) } }
        editor.putUserData(KEY, null)
        status(project, "")
    }

    /** The lines that are not hidden by a collapsed region of the filter, 1-based (what the user sees). */
    fun visibleLines(editor: Editor): List<Int> {
        val doc = editor.document
        return (0 until doc.lineCount).filter { !editor.foldingModel.isOffsetCollapsed(doc.getLineStartOffset(it)) }.map { it + 1 }
    }

    private fun status(project: Project?, text: String) {
        if (project == null) return
        WindowManager.getInstance().getStatusBar(project)?.info = text
    }
}
