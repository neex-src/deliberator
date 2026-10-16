package com.neex.deliberator.repo

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.ui.Ui
import com.neex.deliberator.workspace.WorkspaceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** On project open, brings the project's repositories in line with the workspace's record (see [RepositoryAttacher.reattachAll]). */
class ReattachOnOpen : ProjectActivity {
    override suspend fun execute(project: Project) {
        withContext(Dispatchers.IO) { reattach(project) }
    }

    companion object {
        /** Blocking; quiet when the project is unbound, the daemon unreachable or nothing changed. */
        fun reattach(project: Project) {
            val report = try {
                WorkspaceService.getInstance(project).resolve() ?: return
                RepositoryAttacher.getInstance(project).reattachAll()
            } catch (_: DaemonException) { return }
            if (report.empty) return
            val text = listOfNotNull(
                report.attached.takeIf { it.isNotEmpty() }?.let { "attached: ${it.joinToString()}" },
                report.detached.takeIf { it.isNotEmpty() }?.let { "detached (no longer in the workspace): ${it.joinToString()}" },
                report.problems.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            ).joinToString("\n")
            Ui.notify(project, "Deliberator repositories", text, if (report.problems.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING)
        }
    }
}
