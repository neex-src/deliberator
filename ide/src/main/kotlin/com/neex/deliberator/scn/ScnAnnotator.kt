package com.neex.deliberator.scn

import com.google.gson.JsonObject
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile


/**
 * Parse errors of `scenario.parse {text}` as errors on their line. Runs on the platform's external-annotator
 * pass: a background thread, after the editor has been quiet for the auto-reparse delay, results dropped when
 * the document changed meanwhile. It talks only over the connection the editor already knows ([ScnRegistry.knownClient],
 * made when the `.scn` editor opened) with a budget of [ScnDaemon.TIMEOUT_MS]: it never starts a daemon and never hangs the
 * pass. A daemon that is not there (or says no) gives no annotations, never a dialog. DumbAware: no index is used.
 */
class ScnExternalAnnotator : ExternalAnnotator<ScnExternalAnnotator.Info, ScnExternalAnnotator.Result>(), com.intellij.openapi.project.DumbAware {
    class Info(val file: PsiFile, val text: String, val path: String?)
    class Error(val line: Int, val message: String)
    class Result(val errors: List<Error>)

    override fun collectInformation(file: PsiFile): Info? {
        if (file !is ScnFile) return null
        val vf = file.virtualFile
        return Info(file, file.text, vf?.takeIf { it.isInLocalFileSystem }?.path)
    }

    override fun doAnnotate(info: Info): Result? {
        return try {
            // the connection the editor's warm-up made; none yet (or the daemon not there) = no annotations this pass
            val client = ScnRegistry.getInstance().knownClient() ?: return null
            val args = JsonObject().apply {
                addProperty("text", info.text)
                info.path?.let { addProperty("path", it) }
            }
            val r = ScnDaemon.call(client, info.file.project, "scenario.parse", args)
            Result(errors(r.asJsonObject))
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.debug("scenario.parse unavailable: ${e.message}")
            null
        }
    }

    override fun apply(file: PsiFile, result: Result?, holder: AnnotationHolder) {
        if (result == null) return
        val doc = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        for (e in result.errors) {
            val line = (e.line - 1).coerceIn(0, maxOf(doc.lineCount - 1, 0))
            if (doc.lineCount == 0) continue
            var from = doc.getLineStartOffset(line)
            var to = doc.getLineEndOffset(line)
            if (to == from) { to = minOf(from + 1, doc.textLength); if (to == from) from = maxOf(from - 1, 0) }
            holder.newAnnotation(HighlightSeverity.ERROR, e.message).range(TextRange(from, to)).create()
        }
    }

    companion object {
        private val LOG = logger<ScnExternalAnnotator>()

        fun errors(parse: JsonObject): List<Error> =
            parse.get("errors")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e ->
                val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                Error(o.get("line")?.asInt ?: 1, o.get("message")?.asString ?: "error")
            } ?: emptyList()
    }
}

/** Opening an `.scn` editor loads the registry (and the project's targets) in the background. */
class ScnEditorListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val editor: Editor = event.editor
        val project = editor.project ?: return
        val vf = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (vf.fileType == ScnFileType) ScnRegistry.getInstance().warm(project)
    }
}
