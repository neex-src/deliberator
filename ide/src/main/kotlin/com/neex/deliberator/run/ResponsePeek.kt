package com.neex.deliberator.run

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.neex.deliberator.scn.ScnFile
import com.neex.deliberator.scn.ScnStep
import com.neex.deliberator.ui.Ui
import java.awt.Dimension
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/** The line of `# @assert ...` that states the value on a [PrettyLine], null for a container line. */
fun assertLineFor(l: PrettyLine): String? = l.scalar?.let { v -> "# @assert ${l.path} == ${if (v.contains('\n')) v.replace('\n', ' ') else v}" }

/**
 * `deliberator.scenario.peekResponse`: the response of the step under the caret in a viewer you can read, with JSON colours,
 * folding and search. Enter on a line adds `# @assert $.path == value` to the step (above its body), so an assertion is
 * written by pointing at what came back instead of typing a path.
 */
class PeekResponseAction : ScnEditorAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val v = viewOf(e) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psi = e.getData(CommonDataKeys.PSI_FILE) as? ScnFile ?: return
        val step = psi.stepAt(editor.caretModel.offset)
        val snap = ScnRunner.getInstance(v.project).state(v.file.path).snapshot
        val section = step?.let { snap?.sections?.get(it.index) }
        val resp = com.neex.deliberator.scn.ResponsePaths.response(section)
        if (step == null || resp == null) {
            Ui.notify(v.project, "Response", "This step has no JSON response in the latest run (run it first: Ctrl+Enter).", NotificationType.INFORMATION)
            return
        }
        ResponsePeek.show(editor, step, PrettyJson.render(resp))
    }
}

object ResponsePeek {
    fun show(scn: Editor, step: ScnStep, lines: List<PrettyLine>) {
        val project = scn.project ?: return
        val doc = EditorFactory.getInstance().createDocument(lines.joinToString("\n") { it.text })
        val viewer = EditorFactory.getInstance().createViewer(doc, project) as EditorEx
        val json = FileTypeManager.getInstance().getFileTypeByExtension("json")
        viewer.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, json)
        viewer.settings.apply { isLineNumbersShown = false; isFoldingOutlineShown = false; isRightMarginShown = false; additionalLinesCount = 0 }
        viewer.setCaretEnabled(true)
        val n = step.index
        var popup: JBPopup? = null
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(viewer.component, viewer.contentComponent)
            .setTitle("Step $n response  ·  Enter: add @assert for the line  ·  Esc: close")
            .setResizable(true).setMovable(true).setRequestFocus(true).setCancelOnClickOutside(true)
            .setDimensionServiceKey(project, "deliberator.responsePeek", false)
            .setMinSize(Dimension(520, 260))
            .createPopup()
        Disposer.register(popup) { EditorFactory.getInstance().releaseEditor(viewer) }
        object : com.intellij.openapi.actionSystem.AnAction(), DumbAware {
            override fun actionPerformed(e: AnActionEvent) {
                val line = lines.getOrNull(doc.getLineNumber(viewer.caretModel.offset)) ?: return
                val text = assertLineFor(line)
                if (text == null) { Ui.notify(project, "Response", "Pick a line with a plain value (a string, number, true/false or null).", NotificationType.INFORMATION); return }
                addAssert(scn, step, text)
            }
        }.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)), viewer.contentComponent, popup)
        popup.showInBestPositionFor(scn)
    }

    /** Inserts [text] after the last directive of the step (after its head when it has none). */
    fun addAssert(scn: Editor, step: ScnStep, text: String) {
        val project = scn.project ?: return
        val d = scn.document
        val pm = PsiDocumentManager.getInstance(project)
        pm.commitDocument(d)
        val fresh = (pm.getPsiFile(d) as? ScnFile)?.steps?.getOrNull(step.index - 1) ?: return
        val anchor = fresh.directives.lastOrNull()?.textRange?.endOffset ?: fresh.head?.textRange?.endOffset ?: return
        WriteCommandAction.runWriteCommandAction(project, "Add @assert", null, {
            val at = if (anchor > 0 && anchor <= d.textLength && d.charsSequence.getOrNull(anchor - 1) == '\n') anchor - 1 else anchor
            d.insertString(at, "\n$text")
        })
    }
}
