package com.neex.tellij.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.util.SingleAlarm
import com.neex.tellij.Keys
import com.neex.tellij.WorkbenchService
import com.neex.tellij.engine.RunSession
import com.neex.tellij.model.WorkbenchParser
import org.jetbrains.annotations.TestOnly
import javax.swing.Icon

/**
 * One ▶ per `###` line, as gutter icons on our own line highlighters — no PSI, no line-marker
 * pass. Re-derived from a fresh parse 300 ms after the last edit, and whenever a session starts
 * or stops (▶ becomes ■ while a run is live).
 */
class BlockGutter private constructor(private val editor: Editor) : Disposable {

    private val highlighters = ArrayList<RangeHighlighter>()
    private val alarm = SingleAlarm(::refresh, 300, this)

    init {
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = alarm.cancelAndRequest()
        }, this)
        refresh()
    }

    fun refresh() {
        if (editor.isDisposed) return
        clear()
        val document = editor.document
        val model = WorkbenchParser.parse(document.immutableCharSequence)
        val running = RunSession.liveFor(document) != null
        for (block in model.blocks) {
            if (block.separatorLine >= document.lineCount) continue
            val h = editor.markupModel.addLineHighlighter(block.separatorLine, HighlighterLayer.ADDITIONAL_SYNTAX, null)
            h.gutterIconRenderer = BlockGutterRenderer(editor, block.index, block.displayName, running)
            highlighters += h
        }
    }

    @TestOnly
    fun refreshNow() = refresh()

    @TestOnly
    fun renderers(): List<BlockGutterRenderer> = highlighters.mapNotNull { it.gutterIconRenderer as? BlockGutterRenderer }

    private fun clear() {
        highlighters.forEach { it.dispose() }
        highlighters.clear()
    }

    override fun dispose() {
        clear()
        editor.putUserData(Keys.GUTTER, null)
    }

    companion object {
        fun install(editor: Editor): BlockGutter {
            editor.getUserData(Keys.GUTTER)?.let { return it }
            val gutter = BlockGutter(editor)
            editor.putUserData(Keys.GUTTER, gutter)
            EditorUtil.disposeWithEditor(editor, gutter)
            return gutter
        }

        fun refreshAll(document: Document) {
            for (editor in EditorFactory.getInstance().getEditors(document)) {
                editor.getUserData(Keys.GUTTER)?.refresh()
            }
        }
    }
}

class BlockGutterRenderer(
    private val editor: Editor,
    val blockIndex: Int,
    private val name: String,
    val running: Boolean,
) : GutterIconRenderer() {

    override fun getIcon(): Icon = if (running) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
    override fun getTooltipText(): String = if (running) "Stop (Esc)" else "Run $name (Ctrl+Enter)"
    override fun isNavigateAction(): Boolean = true
    override fun getAlignment(): Alignment = Alignment.LEFT

    override fun getClickAction(): AnAction = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            if (running) RunSession.cancelFor(editor.document)
            else WorkbenchService.runBlock(editor, blockIndex)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is BlockGutterRenderer && other.editor === editor && other.blockIndex == blockIndex && other.running == running

    override fun hashCode(): Int = blockIndex * 31 + (if (running) 1 else 0)
}
