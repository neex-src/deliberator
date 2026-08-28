package com.neex.tellij

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.neex.tellij.engine.ResultWriter
import com.neex.tellij.engine.RunSession
import com.neex.tellij.lang.TellijFileType
import com.neex.tellij.model.WorkbenchParser
import com.neex.tellij.spi.StepError

/** Entry points shared by actions and gutter clicks. */
object WorkbenchService {

    fun isTellij(editor: Editor): Boolean {
        val vf = editor.virtualFile ?: FileDocumentManager.getInstance().getFile(editor.document) ?: return false
        return vf.fileType == TellijFileType || vf.extension == TellijFileType.defaultExtension
    }

    fun runBlockAtCaret(editor: Editor) {
        val project = editor.project ?: return
        val offset = editor.caretModel.offset
        guarded(editor) {
            val session = RunSession.start(project, editor.document, { model -> listOfNotNull(model.blockAt(offset)) }, freshVars = false)
            if (session == null) hint(editor, "Caret is not inside a ### block")
        }
    }

    fun runBlock(editor: Editor, index: Int) {
        val project = editor.project ?: return
        guarded(editor) {
            RunSession.start(project, editor.document, { model -> listOfNotNull(model.blocks.getOrNull(index)) }, freshVars = false)
        }
    }

    fun runAll(editor: Editor) {
        val project = editor.project ?: return
        guarded(editor) {
            val session = RunSession.start(project, editor.document, { it.blocks }, freshVars = true)
            if (session == null) hint(editor, "No ### blocks in this file")
        }
    }

    fun stop(editor: Editor): Boolean {
        val live = RunSession.liveFor(editor.document) ?: return false
        live.cancel()
        return true
    }

    fun clearResults(editor: Editor) {
        val project = editor.project ?: return
        if (RunSession.liveFor(editor.document) != null) { hint(editor, "A run is in progress — Esc stops it"); return }
        var n = 0
        WriteCommandAction.runWriteCommandAction(project, "Clear Tellij Results", null, { n = ResultWriter.removeAll(editor.document) })
        if (n == 0) hint(editor, "No result sections to clear")
    }

    private fun guarded(editor: Editor, body: () -> Unit) {
        try {
            body()
        } catch (e: StepError) {
            hint(editor, e.message + (e.hint?.let { " — $it" } ?: ""))
        }
    }

    fun hint(editor: Editor, message: String) {
        if (editor.component.isShowing) HintManager.getInstance().showInformationHint(editor, message)
    }

    @Suppress("unused")
    fun blockCount(editor: Editor): Int = WorkbenchParser.parse(editor.document.immutableCharSequence).blocks.size

    @Suppress("unused")
    fun project(editor: Editor): Project? = editor.project
}
