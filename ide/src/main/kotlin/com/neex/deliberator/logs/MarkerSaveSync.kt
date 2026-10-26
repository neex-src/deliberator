package com.neex.deliberator.logs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManagerListener

/** Saving a source file re-saves, through the daemon, the markers whose line moved with the edits (see [MarkerGutter.syncMoved]). */
class MarkerSaveSync : FileDocumentManagerListener {
    override fun beforeDocumentSaving(document: Document) {
        val gutters = EditorFactory.getInstance().getEditors(document).mapNotNull { MarkerGutter.of(it) }.take(1)
        if (gutters.isEmpty()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try { gutters.forEach { it.syncMoved() } } catch (e: Exception) { LOG.warn("markers of ${document} were not re-saved after a save: $e") }
        }
    }

    private companion object {
        val LOG = com.intellij.openapi.diagnostic.logger<MarkerSaveSync>()
    }
}
