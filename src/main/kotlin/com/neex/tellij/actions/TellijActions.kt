package com.neex.tellij.actions

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.project.DumbAware
import com.neex.tellij.WorkbenchService
import com.neex.tellij.engine.RunSession

/** Enabled only in `.tellij` editors; `update` stays read-only (it runs on the BGT). */
abstract class TellijAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null && e.project != null && WorkbenchService.isTellij(editor)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        perform(editor)
    }

    abstract fun perform(editor: Editor)
}

class RunBlockAction : TellijAction() {
    override fun perform(editor: Editor) = WorkbenchService.runBlockAtCaret(editor)
}

class RunAllAction : TellijAction() {
    override fun perform(editor: Editor) = WorkbenchService.runAll(editor)
}

class StopAction : TellijAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        val editor = e.getData(CommonDataKeys.EDITOR)
        if (e.presentation.isEnabled && (editor == null || RunSession.liveFor(editor.document) == null)) e.presentation.isEnabled = false
    }

    override fun perform(editor: Editor) {
        WorkbenchService.stop(editor)
    }
}

class ClearResultsAction : TellijAction() {
    override fun perform(editor: Editor) = WorkbenchService.clearResults(editor)
}

class ClearBlockResultAction : TellijAction() {
    override fun perform(editor: Editor) = WorkbenchService.clearBlockResult(editor)
}

class RunFromCaretAction : TellijAction() {
    override fun perform(editor: Editor) = WorkbenchService.runFromCaret(editor)
}

class CollapseResultsAction : TellijAction() {
    override fun perform(editor: Editor) = WorkbenchService.setAllResultsExpanded(editor, false)
}

class ExpandResultsAction : TellijAction() {
    override fun perform(editor: Editor) = WorkbenchService.setAllResultsExpanded(editor, true)
}

/** The "Tellij" submenu of the editor's right-click menu; only shows up in `.tellij` editors. */
class TellijEditorMenu : com.intellij.openapi.actionSystem.DefaultActionGroup(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null && WorkbenchService.isTellij(editor)
    }
}

/**
 * Ctrl+Enter is also EditorSplitLine's chord. Inside a `.tellij` editor our actions win; anywhere
 * else the promoter stays out of the way.
 */
class TellijActionPromoter : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
        val editor = CommonDataKeys.EDITOR.getData(context) ?: return emptyList()
        if (!WorkbenchService.isTellij(editor)) return emptyList()
        return actions.filterIsInstance<TellijAction>()
    }
}

/**
 * Esc stops a live run in this document; with nothing running it collapses the result section
 * under the caret; otherwise stock Esc is untouched.
 */
class TellijEscapeHandler(private val original: EditorActionHandler) : EditorActionHandler() {
    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        if (WorkbenchService.stop(editor)) return
        if (WorkbenchService.isTellij(editor) && WorkbenchService.collapseResultAtCaret(editor)) return
        original.execute(editor, caret, dataContext)
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
        RunSession.liveFor(editor.document) != null ||
            (WorkbenchService.isTellij(editor) && com.neex.tellij.editor.TellijFoldingBuilder.regionAt(editor, caret.offset)?.isExpanded == true) ||
            original.isEnabled(editor, caret, dataContext)
}
