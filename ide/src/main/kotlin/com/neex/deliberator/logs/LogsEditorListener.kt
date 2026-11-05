package com.neex.deliberator.logs

import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager

/** Source editors get their marker icons, log editors their match highlights; both read what the daemon holds. */
class LogsEditorListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return
        val vf = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (!vf.isInLocalFileSystem) return
        MarkerService.getInstance(project).ensureLoaded()
        val matcher = LogMatcher.getInstance(project)
        if (LogFiles.isLog(vf)) { matcher.annotate(editor); LogLinks.getInstance(project).attach(editor) }
        else { MarkerGutter.attach(editor, project, vf); if (LogLinks.wants(vf)) LogLinks.getInstance(project).attach(editor) }
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        MarkerGutter.detach(event.editor)
        event.editor.project?.let { if (!it.isDisposed) { LogMatcher.getInstance(it).forget(event.editor); LogLinks.getInstance(it).forget(event.editor) } }
    }
}
