package com.neex.deliberator.scn

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.neex.deliberator.workspace.WorkspaceService
import java.util.function.Function
import javax.swing.JComponent

/** The environment a scenario file runs against: its own `# @env` directive, else the one picked in the tool window, else `local`. */
internal fun ScnFile.targetEnv(selected: String): String =
    (header?.directives?.firstOrNull { it.key == "env" }?.value?.trim()?.takeIf { it.isNotEmpty() }) ?: selected

/** Red banner above a scenario that targets a remote environment: reads run, every change asks first. */
class RemoteEnvBanner : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (file.extension != "scn") return null
        val svc = WorkspaceService.getInstance(project)
        val psi = PsiManager.getInstance(project).findFile(file) as? ScnFile ?: return null
        val env = psi.targetEnv(svc.selectedEnv)
        val row = svc.model.envs.firstOrNull { it.name == env } ?: return null
        if (row.kind != "remote") return null
        return Function { ed ->
            EditorNotificationPanel(ed, EditorNotificationPanel.Status.Error).apply {
                text = "REMOTE environment “$env”: reads run, every change asks for confirmation first."
            }
        }
    }
}
