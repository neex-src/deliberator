package com.neex.deliberator.environments

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.neex.deliberator.ci.CiPanel
import com.neex.deliberator.ci.RunNode
import com.neex.deliberator.ui.Ui
import com.neex.deliberator.workspace.WorkspaceService
import java.nio.file.Path
import javax.swing.tree.DefaultMutableTreeNode

/**
 * What the deploy view does with a deployment, shared by the Environments tab's buttons and menu and by the tests. Every function that talks to the daemon blocks
 * (call it from [Ui.background]); the editor and the tabs are touched on the EDT.
 */
object DeployController {
    val TARGET: DataKey<DeployTarget> = DataKey.create("deliberator.deploy.target")

    /** Enter on a deployment: its deploy text (header, events, tasks, container lines) in the editor. */
    fun openText(project: Project, t: DeployTarget) = Ui.background(project, "Deliberator: deploy text of ${t.spec}", {
        val p = DeployOps.showPath(WorkspaceService.getInstance(project), t.env, t.spec, t.row?.id, t.row?.rollout == "COMPLETED")
        Ui.openFileLater(project, p)
    })

    /** Container Logs of This Deployment: the deploy text, with the caret on its `== log` section. */
    fun openLogs(project: Project, t: DeployTarget) = Ui.background(project, "Deliberator: container logs of ${t.spec}", {
        val p = DeployOps.showPath(WorkspaceService.getInstance(project), t.env, t.spec, t.row?.id, t.row?.rollout == "COMPLETED")
        val line = runCatching { java.nio.file.Files.readAllLines(Path.of(p)).indexOfFirst { it.startsWith("== log") } }.getOrDefault(-1)
        Ui.openFileLater(project, p, maxOf(0, line))
    })

    /** Follow Deploy: a console tab streaming `deploy.line`, the deploy text opened beside it. EDT. */
    fun follow(project: Project, t: DeployTarget): DeployFollowHandler {
        val title = "Deploy: ${t.spec.substringAfterLast('/')}"
        val h = DeployFollowHandler(project, t.env, t.spec, t.row?.takeIf { it.rollout == "IN_PROGRESS" }?.id, title)
        DeployConsoles.show(project, h)
        return h
    }

    /** The CI tab of the tool window, when it is there. EDT. */
    fun ciPanel(project: Project): Pair<CiPanel, com.intellij.ui.content.Content>? {
        val cm = ToolWindowManager.getInstance(project).getToolWindow("Deliberator")?.contentManager ?: return null
        val c = cm.contents.firstOrNull { it.component is CiPanel } ?: return null
        return (c.component as CiPanel) to c
    }

    /** Open CI Run: selects the run [runId] (or, with none, the run of commit [sha]) in the CI tab. Says why when it is not in the tree. EDT. True when selected. */
    fun openCiRun(project: Project, runId: String?, sha: String?): Boolean {
        val (panel, content) = ciPanel(project) ?: run { Ui.notify(project, "CI", "The CI tab is not open.", NotificationType.WARNING); return false }
        val root = panel.tree.model.root as? DefaultMutableTreeNode
        val runs = root?.depthFirstEnumeration()?.toList().orEmpty().mapNotNull { (it as? DefaultMutableTreeNode)?.userObject as? RunNode }
        val hit = runs.firstOrNull { runId != null && it.run.id == runId }
            ?: runs.firstOrNull { sha != null && sha.length >= 7 && it.run.sha?.let { s -> s.startsWith(sha) || sha.startsWith(s) } == true }
        if (hit == null) {
            Ui.notify(project, "CI", "Run ${runId ?: sha?.take(7)} is not in the CI tab: refresh the repository's runs there (a run of a repository that is not listed, or older than the list, is not shown).", NotificationType.WARNING)
            return false
        }
        ToolWindowManager.getInstance(project).getToolWindow("Deliberator")?.let { it.contentManager.setSelectedContent(content); it.activate(null) }
        panel.select(hit.repo, hit.run.id)
        return true
    }

    /** Open Deployment (from a CI run): the deployments `deploy.where` finds for the run's commit and id, the deploy text of the one chosen. */
    fun openDeploymentOf(project: Project, sha: String?, runId: String?) = Ui.background(project, "Deliberator: deployments of ${sha?.take(7) ?: runId}", {
        val hits = DeployOps.where(WorkspaceService.getInstance(project), sha, runId)
        when {
            hits.isEmpty() -> Ui.notify(project, "Deployments", "No deployment carries ${sha?.take(7) ?: "run $runId"} in the caches of this workspace's environments. Refresh Deployments in the Environments tab fetches them.", NotificationType.INFORMATION)
            hits.size == 1 -> openHit(project, hits[0])
            else -> ApplicationManager.getApplication().invokeLater({
                JBPopupFactory.getInstance().createPopupChooserBuilder(hits).setTitle("Deployments of ${sha?.take(7) ?: runId}")
                    .setRenderer(textListCellRenderer { it.label() }).setItemChosenCallback { openHit(project, it) }.createPopup().showCenteredInCurrentWindow(project)
            }, ModalityState.nonModal())
        }
    })

    fun openHit(project: Project, h: DeployHit) = Ui.background(project, "Deliberator: deploy text of ${h.spec}", {
        val p = DeployOps.showPath(WorkspaceService.getInstance(project), h.env, h.spec, h.deployment, h.status == "SUCCESSFUL")
        Ui.openFileLater(project, p)
    })
}

/** Base of the actions of the Environments tab's deployments table: the target comes from [DeployController.TARGET]. */
abstract class DeployAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    open fun needsRow(): Boolean = true
    override fun update(e: AnActionEvent) {
        val t = e.getData(DeployController.TARGET)
        e.presentation.isEnabled = t != null && (!needsRow() || t.row != null)
    }
    override fun actionPerformed(e: AnActionEvent) {
        val p = e.project ?: return
        val t = e.getData(DeployController.TARGET) ?: return
        perform(p, t)
    }
    abstract fun perform(project: Project, t: DeployTarget)
}

class DeployOpenTextAction : DeployAction() { override fun perform(project: Project, t: DeployTarget) = DeployController.openText(project, t) }
class DeployLogsAction : DeployAction() { override fun perform(project: Project, t: DeployTarget) = DeployController.openLogs(project, t) }
class DeployFollowAction : DeployAction() {
    override fun needsRow() = false
    override fun update(e: AnActionEvent) { super.update(e); if (e.getData(DeployController.TARGET)?.kind == "lambda") e.presentation.isEnabled = false }
    override fun perform(project: Project, t: DeployTarget) { DeployController.follow(project, t) }
}
class DeployCiRunAction : DeployAction() {
    override fun update(e: AnActionEvent) { super.update(e); val r = e.getData(DeployController.TARGET)?.row; if (r != null) e.presentation.isEnabled = r.runId != null || r.commit != null }
    override fun perform(project: Project, t: DeployTarget) { DeployController.openCiRun(project, t.row?.runId, t.row?.commit) }
}
class DeployRefreshAction : DeployAction() {
    override fun needsRow() = false
    override fun perform(project: Project, t: DeployTarget) { EnvironmentsPanelHolder.refreshDeployments(project) }
}

/** The CI tab's Open Deployment, kept out of the CI files: it reads the CI tab's selection and registers itself into its group (plugin.xml). */
class OpenDeploymentAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val sel = e.getData(CiPanel.SELECTION)
        e.presentation.isEnabled = e.project != null && sel != null && (sel.run.sha != null || sel.run.id.isNotBlank())
    }
    override fun actionPerformed(e: AnActionEvent) {
        val p = e.project ?: return
        val run = e.getData(CiPanel.SELECTION)?.run ?: return
        DeployController.openDeploymentOf(p, run.sha, run.id)
    }
}

/** The Environments tab of a project, for the actions that act on it from outside (a toolbar button, a test). */
object EnvironmentsPanelHolder {
    private val panels = java.util.concurrent.ConcurrentHashMap<Project, EnvironmentsPanel>()
    fun register(p: Project, panel: EnvironmentsPanel) { panels[p] = panel }
    fun forget(p: Project, panel: EnvironmentsPanel) { panels.remove(p, panel) }
    fun refreshDeployments(p: Project) { panels[p]?.refreshDeployments() }
}
