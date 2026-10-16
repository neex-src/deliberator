package com.neex.deliberator.run

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.diff.DiffManager
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.scn.ScnFile
import com.neex.deliberator.scn.ScnFileType
import com.neex.deliberator.ui.Ui
import com.neex.deliberator.workspace.WorkspaceService

private val CTRL_ENTER = com.intellij.openapi.actionSystem.CustomShortcutSet(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, java.awt.event.InputEvent.CTRL_DOWN_MASK))

/** Creates the [RunView] of every `.scn` editor and binds Ctrl+Enter and Esc to it (component level, before the stock bindings). */
class RunEditorListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return
        val vf = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (vf.fileType != ScnFileType || !vf.isInLocalFileSystem) return
        val view = RunView.attach(editor, project, vf)
        val am = ActionManager.getInstance()
        am.getAction("deliberator.scenario.runStep")?.registerCustomShortcutSet(CTRL_ENTER, editor.contentComponent, view)
        am.getAction("deliberator.scenario.cancel")?.registerCustomShortcutSet(CommonShortcuts.ESCAPE, editor.contentComponent, view)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        RunView.detach(event.editor)
    }
}

/** The editor, its run view and the step under the caret, for the actions of this file. */
abstract class ScnEditorAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    protected fun viewOf(e: AnActionEvent): RunView? = e.getData(CommonDataKeys.EDITOR)?.let { RunView.of(it) }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = enabled(e)
    }

    protected open fun enabled(e: AnActionEvent): Boolean = viewOf(e) != null

    /** The step the caret is in, from the PSI (committed text). */
    protected fun stepAtCaret(e: AnActionEvent): Int? {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val psi = e.getData(CommonDataKeys.PSI_FILE) as? ScnFile ?: return null
        return psi.stepAt(editor.caretModel.offset)?.index
    }
}

/** `deliberator.scenario.runStep` (Ctrl+Enter): the step at the caret. */
class RunStepAction : ScnEditorAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val v = viewOf(e) ?: return
        val n = stepAtCaret(e)
        if (n == null) { Ui.notify(v.project, "Run step", "The caret is not in a step (### line).", NotificationType.WARNING); return }
        v.runSteps(listOf(n))
    }
}

/** `deliberator.scenario.runFrom`: the step at the caret and every step after it. */
class RunFromHereAction : ScnEditorAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val v = viewOf(e) ?: return
        val n = stepAtCaret(e)
        if (n == null) { Ui.notify(v.project, "Run from here", "The caret is not in a step (### line).", NotificationType.WARNING); return }
        v.runFrom(n)
    }
}

/** `deliberator.scenario.runAll`: the whole file. */
class RunAllAction : ScnEditorAction() {
    override fun actionPerformed(e: AnActionEvent) { viewOf(e)?.runSteps(null) }
}

/** `deliberator.scenario.cancel` (Esc): cancels the run of this editor's file, and is enabled only while that run exists. */
class CancelRunAction : ScnEditorAction() {
    override fun enabled(e: AnActionEvent): Boolean {
        val v = viewOf(e) ?: return false
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return false
        if (LookupManager.getActiveLookup(editor) != null) return false
        return ScnRunner.getInstance(v.project).isRunning(v.file.path)
    }

    override fun actionPerformed(e: AnActionEvent) { viewOf(e)?.cancel() }
}

/** `deliberator.scenario.toggleResults`: expand or collapse every inline result. */
class ToggleResultsAction : ScnEditorAction() {
    override fun actionPerformed(e: AnActionEvent) { viewOf(e)?.toggleAll() }
}

/** `deliberator.scenario.runs`: the runs of this scenario; open result.txt, show inline, diff with another run. */
class RunHistoryAction : ScnEditorAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val v = viewOf(e) ?: return
        RunHistory.show(v.project, v.file.path)
    }
}

object RunHistory {
    fun show(project: Project, path: String) {
        var rows: List<HistoryRow> = emptyList()
        Ui.background(project, "Deliberator: runs of ${path.substringAfterLast('/')}", {
            rows = ScnRunner.getInstance(project).history(path)
        }, {
            if (rows.isEmpty()) Ui.notify(project, "Runs", "No runs of this scenario yet.", NotificationType.INFORMATION)
            else pick(project, path, "Runs of ${path.substringAfterLast('/')}", rows) { actions(project, path, it, rows) }
        })
    }

    private fun pick(project: Project, path: String, title: String, rows: List<HistoryRow>, chosen: (HistoryRow) -> Unit) {
        JBPopupFactory.getInstance().createPopupChooserBuilder(rows).setTitle(title)
            .setRenderer(textListCellRenderer { it.label })
            .setItemChosenCallback(chosen).createPopup().showCenteredInCurrentWindow(project)
    }

    private fun actions(project: Project, path: String, row: HistoryRow, rows: List<HistoryRow>) {
        val items = listOf("Open result.txt", "Show inline", "Diff with previous run", "Diff with...")
        JBPopupFactory.getInstance().createPopupChooserBuilder(items).setTitle(row.runId).setItemChosenCallback { a ->
            when (a) {
                items[0] -> openResult(project, row)
                items[1] -> Ui.background(project, "Deliberator: loading ${row.runId}", { ScnRunner.getInstance(project).let { it.show(path, it.loadSnapshot(row.path.ifBlank { row.runId })) } })
                items[2] -> rows.getOrNull(rows.indexOf(row) + 1)?.let { diff(project, it, row) }
                    ?: Ui.notify(project, "Runs", "There is no earlier run.", NotificationType.INFORMATION)
                else -> pick(project, path, "Diff ${row.runId} with", rows.filter { it != row }) { other ->
                    // older on the left
                    if (rows.indexOf(other) > rows.indexOf(row)) diff(project, other, row) else diff(project, row, other)
                }
            }
        }.createPopup().showCenteredInCurrentWindow(project)
    }

    fun openResult(project: Project, row: HistoryRow) {
        var result: String? = null
        Ui.background(project, "Deliberator: ${row.runId}", { result = ScnRunner.getInstance(project).loadSnapshot(row.path.ifBlank { row.runId }).resultPath }, {
            val p = result
            if (p == null || !Ui.openFile(project, p)) Ui.notify(project, "Run", "No result.txt for ${row.runId}", NotificationType.WARNING)
        })
    }

    fun diff(project: Project, a: HistoryRow, b: HistoryRow) {
        var texts: DiffTexts? = null
        Ui.background(project, "Deliberator: diff ${a.runId} / ${b.runId}", {
            texts = RunDiff.fetch(WorkspaceService.getInstance(project), a, b)
        }, {
            texts?.let { ApplicationManager.getApplication().invokeLater { DiffManager.getInstance().showDiff(project, RunDiff.request(project, it)) } }
        })
    }
}
