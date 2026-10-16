package com.neex.deliberator.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.neex.deliberator.repo.RepositoryAttacher
import com.neex.deliberator.ui.Ui
import com.neex.deliberator.workspace.WorkspaceService

/** `deliberator.repo.attach`: a directory chooser, then [RepositoryAttacher.attach] in the background. */
class AttachRepositoryAction : WorkspaceAction() {
    override fun perform(project: Project, svc: WorkspaceService) {
        val dir = FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Attach Repository"), project, null) ?: return
        val path = dir.toNioPath().toString()
        Ui.background(project, "Attaching $path", {
            val o = RepositoryAttacher.getInstance(project).attach(path)
            Ui.notify(project, "Repository attached", "${o.repo.name} (${o.repo.kind})" + (o.note?.let { ": $it" } ?: ""))
        })
    }
}

/** `deliberator.repo.detach`: a popup of the workspace's repositories, then [RepositoryAttacher.detach]. */
class DetachRepositoryAction : WorkspaceAction() {
    override fun perform(project: Project, svc: WorkspaceService) {
        Ui.background(project, "Reading repositories", {
            val repos = RepositoryAttacher.getInstance(project).list()
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                if (repos.isEmpty()) { Ui.notify(project, "Detach repository", "No repositories are attached to this workspace.", NotificationType.INFORMATION); return@invokeLater }
                JBPopupFactory.getInstance().createListPopup(object : BaseListPopupStep<RepositoryAttacher.Repo>("Detach Repository", repos) {
                    override fun getTextFor(r: RepositoryAttacher.Repo) = "${r.name} (${r.kind})  ${r.path}"
                    override fun onChosen(r: RepositoryAttacher.Repo, finalChoice: Boolean): PopupStep<*>? {
                        Ui.background(project, "Detaching ${r.name}", {
                            RepositoryAttacher.getInstance(project).detach(r.name)
                            Ui.notify(project, "Repository detached", "${r.name}: the files are untouched.")
                        })
                        return PopupStep.FINAL_CHOICE
                    }
                }).showCenteredInCurrentWindow(project)
            }
        })
    }
}
