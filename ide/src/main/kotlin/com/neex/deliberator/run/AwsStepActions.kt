package com.neex.deliberator.run

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.psi.PsiDocumentManager
import com.neex.deliberator.scn.AwsModels
import com.neex.deliberator.scn.AwsStepTools
import com.neex.deliberator.scn.ScnFile
import com.neex.deliberator.scn.ScnRegistry
import com.neex.deliberator.ui.Ui
import java.awt.datatransfer.DataFlavor

/**
 * `deliberator.scenario.pasteAwsCli`: the clipboard holds an `aws sqs send-message --queue-url ...` command (from a runbook, a chat,
 * the console's "copy as CLI"): it becomes a `### aws sqs.SendMessage` step with the request as JSON, after the step at the caret.
 */
class PasteAwsCliAction : ScnEditorAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val v = viewOf(e) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val text = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)?.trim().orEmpty()
        if (!text.startsWith("aws ")) {
            Ui.notify(v.project, "Paste aws command", "The clipboard does not hold an `aws ...` command.", NotificationType.INFORMATION)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val verbs = ScnRegistry.getInstance().let { it.kinds() ?: runCatching { it.refresh() }.getOrNull() }?.get("aws")?.verbs?.toSet().orEmpty()
            val r = AwsStepTools.fromCli(text, verbs) { AwsModels.getInstance().fetch(v.project, it) }
            ApplicationManager.getApplication().invokeLater({
                val step = r.text()
                if (step == null) { Ui.notify(v.project, "Paste aws command", r.error ?: "not understood", NotificationType.WARNING); return@invokeLater }
                AwsStepEdits.appendStep(editor, step)
                if (r.unknown.isNotEmpty()) Ui.notify(v.project, "Paste aws command", "Not members of ${r.verb}: ${r.unknown.joinToString(" ")} (kept as written, check the names).", NotificationType.WARNING)
            }, ModalityState.nonModal())
        }
    }
}

/** `deliberator.scenario.fillBody`: the step at the caret is an `aws` step without a body: its required request members go in. */
class FillAwsBodyAction : ScnEditorAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val v = viewOf(e) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psi = e.getData(CommonDataKeys.PSI_FILE) as? ScnFile ?: return
        val step = psi.stepAt(editor.caretModel.offset)
        val verb = step?.verb
        if (step == null || step.kind != "aws" || verb == null) { Ui.notify(v.project, "Fill body", "The caret is not in a `### aws service.Operation` step.", NotificationType.INFORMATION); return }
        if (step.body?.text?.isNotBlank() == true) { Ui.notify(v.project, "Fill body", "The step already has a body.", NotificationType.INFORMATION); return }
        val index = step.index
        ApplicationManager.getApplication().executeOnPooledThread {
            val node = AwsModels.getInstance().fetch(v.project, verb)
            ApplicationManager.getApplication().invokeLater({
                if (node == null) { Ui.notify(v.project, "Fill body", "No request model for $verb (is the daemon reachable?).", NotificationType.WARNING); return@invokeLater }
                AwsStepEdits.setBody(editor, index, AwsStepTools.skeleton(node))
            }, ModalityState.nonModal())
        }
    }
}

/** `deliberator.scenario.copyAsCli`: the aws step at the caret as an `aws ...` command on the clipboard (with `--endpoint-url` of the environment when it has one). */
class CopyStepAsCliAction : ScnEditorAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val v = viewOf(e) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psi = e.getData(CommonDataKeys.PSI_FILE) as? ScnFile ?: return
        val step = psi.stepAt(editor.caretModel.offset)
        val verb = step?.verb
        if (step == null || step.kind != "aws" || verb == null) { Ui.notify(v.project, "Copy as CLI", "The caret is not in a `### aws service.Operation` step.", NotificationType.INFORMATION); return }
        val body = step.body?.text.orEmpty()
        val cli = AwsStepTools.toCli(verb, body)
        if (cli == null) { Ui.notify(v.project, "Copy as CLI", "The body is not a JSON object.", NotificationType.WARNING); return }
        CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(cli))
        Ui.notify(v.project, "Copy as CLI", cli.take(300), NotificationType.INFORMATION)
    }
}

internal object AwsStepEdits {
    /** [step] (`### ...\nbody\n`) after the step at the caret, or at the end of the file; blank line before. */
    fun appendStep(editor: Editor, step: String) {
        val project = editor.project ?: return
        val d = editor.document
        val pm = PsiDocumentManager.getInstance(project)
        pm.commitDocument(d)
        val psi = pm.getPsiFile(d) as? ScnFile
        val here = psi?.stepAt(editor.caretModel.offset)
        val end = (here?.let { s -> d.getLineEndOffset(d.getLineNumber(maxOf(s.textRange.startOffset, s.textRange.endOffset - 1))) }) ?: d.textLength
        val ins = "\n\n" + step.trimEnd()
        WriteCommandAction.runWriteCommandAction(project, "Paste aws command", null, {
            d.insertString(end, ins)
            editor.caretModel.moveToOffset(end + 2)
        })
    }

    /** Puts [body] under the head and directives of step number [index]. */
    fun setBody(editor: Editor, index: Int, body: String) {
        val project = editor.project ?: return
        val d = editor.document
        val pm = PsiDocumentManager.getInstance(project)
        pm.commitDocument(d)
        val step = (pm.getPsiFile(d) as? ScnFile)?.steps?.getOrNull(index - 1) ?: return
        val anchor = step.directives.lastOrNull()?.textRange?.endOffset ?: step.head?.textRange?.endOffset ?: return
        val at = if (anchor > 0 && d.charsSequence.getOrNull(anchor - 1) == '\n') anchor - 1 else anchor
        WriteCommandAction.runWriteCommandAction(project, "Fill request body", null, { d.insertString(at, "\n" + body) })
    }
}
