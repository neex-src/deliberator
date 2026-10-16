package com.neex.deliberator.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.daemon.DaemonService

/** Deliberator | Daemon status: finds or starts the daemon and shows what answered. */
class DaemonStatusAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        object : Task.Backgroundable(e.project, "Deliberator: daemon", false) {
            override fun run(indicator: ProgressIndicator) {
                val (title, text, ok) = try {
                    Triple("Deliberator daemon", DaemonService.getInstance().ensure().describe(), true)
                } catch (ex: DaemonException) {
                    Triple("Deliberator daemon: ${ex.type}", ex.message + (ex.hint?.let { "\n\n$it" } ?: ""), false)
                }
                ApplicationManager.getApplication().invokeLater {
                    if (ok) Messages.showInfoMessage(e.project, text, title) else Messages.showErrorDialog(e.project, text, title)
                }
            }
        }.queue()
    }
}
