package com.neex.deliberator.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
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
        FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, vf), true)
        return true
    }
}
