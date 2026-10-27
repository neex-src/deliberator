package com.neex.deliberator.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.neex.deliberator.daemon.DaemonException
import java.nio.file.Path

/** Small shared helpers of the Deliberator UI: background tasks, balloons, opening files the daemon named. */
object Ui {
    /** Runs [work] off the EDT under a progress title; a [DaemonException] becomes an error balloon, [after] runs on the EDT. */
    fun background(project: Project, title: String, work: () -> Unit, after: () -> Unit = {}) {
        object : Task.Backgroundable(project, title, false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    work()
                } catch (e: DaemonException) {
                    notify(project, title, e.message + (e.hint?.let { "\n$it" } ?: ""), NotificationType.ERROR)
                }
            }

            override fun onFinished() = after()
        }.queue()
    }

    fun notify(project: Project?, title: String, text: String, type: NotificationType = NotificationType.INFORMATION) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance().getNotificationGroup("Deliberator")
                .createNotification(title, text, type).notify(project)
        }
    }

    /** Opens a file the daemon wrote (a path it returned) in the editor. EDT. */
    fun openFile(project: Project, path: String): Boolean {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(path)) ?: return false
        openEditor(project, vf, 0)
        return true
    }

    /**
     * [openFile] for any thread: the path is resolved in the VFS (a refresh, which is a slow operation) on a pooled thread, the editor opens on the EDT.
     * [done] gets false when there is no such file (called on the EDT).
     */
    fun openFileLater(project: Project, path: String, line0: Int = 0, done: (Boolean) -> Unit = {}) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(path))
            ApplicationManager.getApplication().invokeLater({
                if (vf != null) openEditor(project, vf, line0)
                done(vf != null)
            }, com.intellij.openapi.application.ModalityState.nonModal())
        }
    }

    /** Opens [vf] with the caret on 0-based [line0] and answers its text editor (null when the file has none). EDT. */
    fun openEditor(project: Project, vf: VirtualFile, line0: Int = 0): Editor? {
        OpenFileDescriptor(project, vf, maxOf(0, line0), 0).navigate(true)
        return FileEditorManager.getInstance(project).selectedTextEditor?.takeIf { it.virtualFile == vf || FileDocumentManager.getInstance().getFile(it.document) == vf }
    }
}
