package com.neex.tellij.java.summary

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.project.DumbAware

/** Alt+Shift+E in a Java file: toggle the endpoint summary under the method at the caret. */
class EndpointSummaryAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.project
        e.presentation.isEnabledAndVisible = editor != null && project != null && EndpointSummaryInlay.javaFileOf(editor, project) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        EndpointSummaryInlay.toggle(editor, project)
    }
}

/** Esc closes the latest open summary in this editor; otherwise the next handler (Tellij's, then stock) runs. */
class EndpointSummaryEscapeHandler(private val original: EditorActionHandler) : EditorActionHandler() {
    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        if (!EndpointSummaryInlay.collapseLatest(editor)) original.execute(editor, caret, dataContext)
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
        EndpointSummaryInlay.openCount(editor) > 0 || original.isEnabled(editor, caret, dataContext)
}
