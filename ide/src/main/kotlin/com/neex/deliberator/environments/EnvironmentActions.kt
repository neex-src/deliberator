package com.neex.deliberator.environments

import com.intellij.openapi.project.Project
import com.neex.deliberator.actions.WorkspaceAction
import com.neex.deliberator.workspace.WorkspaceService

/** `deliberator.env.create`: the New Environment form (`env.create`). */
class NewEnvironmentAction : WorkspaceAction() {
    override fun perform(project: Project, svc: WorkspaceService) {
        NewEnvironmentDialog(project) { c ->
            svc.selectEnv(c.name).also { svc.refresh() }
        }.show()
    }
}
