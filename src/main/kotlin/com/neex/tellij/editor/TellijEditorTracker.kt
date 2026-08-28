package com.neex.tellij.editor

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.neex.tellij.WorkbenchService
import com.neex.tellij.engine.RunSession

/** Installs the block gutter on every `.tellij` editor; the last editor closing stops its run. */
class TellijEditorTracker : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        if (WorkbenchService.isTellij(editor)) BlockGutter.install(editor)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val document = event.editor.document
        if (EditorFactory.getInstance().getEditors(document).none { it !== event.editor }) {
            RunSession.cancelFor(document)
        }
    }
}
