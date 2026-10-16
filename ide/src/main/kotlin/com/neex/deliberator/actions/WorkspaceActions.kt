package com.neex.deliberator.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.google.gson.JsonObject
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.ui.dsl.builder.panel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBTextField
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.daemon.DaemonService
import com.neex.deliberator.ui.OperationsDialog
import com.neex.deliberator.ui.Ui
import com.neex.deliberator.workspace.WorkspaceService
import java.nio.file.Path
import javax.swing.JComponent

/** Base of the project-bound actions: visible with a project, enabled when [enabledFor] says so; the work is [perform]. */
abstract class WorkspaceAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    open fun enabledFor(svc: WorkspaceService): Boolean = svc.ws != null

    override fun update(e: AnActionEvent) {
        val p = e.project
        e.presentation.isEnabledAndVisible = p != null
        if (p != null) e.presentation.isEnabled = enabledFor(WorkspaceService.getInstance(p))
    }

    override fun actionPerformed(e: AnActionEvent) {
        val p = e.project ?: return
        perform(p, WorkspaceService.getInstance(p))
    }

    abstract fun perform(project: Project, svc: WorkspaceService)
}

/** `deliberator.workspace.refresh` */
class RefreshAction : WorkspaceAction() {
    override fun enabledFor(svc: WorkspaceService) = true
    override fun perform(project: Project, svc: WorkspaceService) = svc.refresh()
}

/** `deliberator.session.start`: key and optional title. */
class StartSessionAction : WorkspaceAction() {
    override fun perform(project: Project, svc: WorkspaceService) {
        val dlg = StartSessionDialog(project)
        if (!dlg.showAndGet()) return
        val (key, title) = dlg.answer()
        Ui.background(project, "Deliberator: start session", { svc.startSession(key, title) })
    }
}

/** The Start Session dialog: a key (required) and an optional title. */
class StartSessionDialog(project: Project) : DialogWrapper(project) {
    private val key = JBTextField(24)
    private val titleField = JBTextField(24)

    init { title = "Start session"; init() }

    override fun createCenterPanel(): JComponent = panel {
        row("Key:") { cell(key).focused() }
        row("Title:") { cell(titleField) }
    }

    override fun doValidate(): com.intellij.openapi.ui.ValidationInfo? =
        if (key.text.isBlank()) com.intellij.openapi.ui.ValidationInfo("A session needs a key (a ticket id, a date, anything short)", key) else null

    override fun getPreferredFocusedComponent() = key

    /** Key and title as `session.start` takes them. */
    fun answer(): Pair<String, String?> = key.text.trim() to titleField.text.trim().ifEmpty { null }

    internal fun type(k: String, t: String = "") { key.text = k; titleField.text = t }
    internal fun validation() = doValidate()
}

/** `deliberator.session.end`: ends the current session. */
class EndSessionAction : WorkspaceAction() {
    override fun enabledFor(svc: WorkspaceService) = svc.model.currentSession != null
    override fun perform(project: Project, svc: WorkspaceService) =
        Ui.background(project, "Deliberator: end session", { svc.endSession() })
}

/** `deliberator.runtime.up`: reconciles the workspace's runtime and waits until healthy. */
class RuntimeUpAction : WorkspaceAction() {
    override fun perform(project: Project, svc: WorkspaceService) =
        Ui.background(project, "Deliberator: runtime up", { svc.runtimeUp() })
}

/** `deliberator.runtime.down`: removes the workspace's runtime containers. */
class RuntimeDownAction : WorkspaceAction() {
    override fun perform(project: Project, svc: WorkspaceService) {
        if (Messages.showYesNoDialog(project, "Remove the runtime containers of workspace ${svc.ws}?", "Runtime down", null) != Messages.YES) return
        Ui.background(project, "Deliberator: runtime down", { svc.runtimeDown() })
    }
}

/** `deliberator.runtime.logs`: pick a role, open the log file the daemon mirrored. */
class RuntimeLogsAction : WorkspaceAction() {
    override fun perform(project: Project, svc: WorkspaceService) {
        val roles = svc.model.containers.map { it.role }
        if (roles.isEmpty()) { Ui.notify(project, "Runtime logs", "No runtime roles yet: run Runtime up first.", NotificationType.WARNING); return }
        JBPopupFactory.getInstance().createPopupChooserBuilder(roles).setTitle("Logs of role")
            .setItemChosenCallback { role -> openRoleLog(project, svc, role) }.createPopup().showCenteredInCurrentWindow(project)
    }

    companion object {
        fun openRoleLog(project: Project, svc: WorkspaceService, role: String) {
            var path: String? = null
            Ui.background(project, "Deliberator: logs of $role", {
                path = svc.runtimeLogs(role).getAsJsonArray("paths")?.firstOrNull()?.asString
            }, {
                path?.let { if (!Ui.openFile(project, it)) Ui.notify(project, "Runtime logs", "Cannot open $it", NotificationType.WARNING) }
                    ?: Ui.notify(project, "Runtime logs", "The daemon returned no log file for $role.", NotificationType.WARNING)
            })
        }
    }
}

/** `deliberator.env.select`: pick the environment of this project from `env.list`. */
class SelectEnvAction : WorkspaceAction() {
    override fun perform(project: Project, svc: WorkspaceService) {
        val names = svc.model.envs.map { it.name }.ifEmpty { listOf(svc.selectedEnv) }
        JBPopupFactory.getInstance().createPopupChooserBuilder(names).setTitle("Environment")
            .setItemChosenCallback { svc.selectEnv(it) }.createPopup().showCenteredInCurrentWindow(project)
    }
}

/** `deliberator.runs.open`: pick one of the current session's latest runs and open its `result.txt`. */
class OpenRunAction : WorkspaceAction() {
    override fun perform(project: Project, svc: WorkspaceService) {
        val runs = svc.model.runs
        if (runs.isEmpty()) { Ui.notify(project, "Runs", "No runs in the current session.", NotificationType.WARNING); return }
        JBPopupFactory.getInstance().createPopupChooserBuilder(runs).setTitle("Run")
            .setRenderer(com.intellij.ui.dsl.listCellRenderer.textListCellRenderer { "${it.runId}  ${it.status ?: ""}  ${it.scenario ?: ""}" })
            .setItemChosenCallback { open(project, it.path) }.createPopup().showCenteredInCurrentWindow(project)
    }

    companion object {
        fun open(project: Project, runDir: String) {
            val f = Path.of(runDir).resolve("result.txt")
            if (!Ui.openFile(project, f.toString())) Ui.notify(project, "Run", "No result.txt in $runDir", NotificationType.WARNING)
        }
    }
}

/** `deliberator.ops.show`: the generic operations dialog. */
class OperationsAction : WorkspaceAction() {
    override fun perform(project: Project, svc: WorkspaceService) = OperationsDialog(project, svc).show()
}

/** `deliberator.workspace.bind`: bind the current project to a workspace by name. */
class BindWorkspaceAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null }
    override fun actionPerformed(e: AnActionEvent) {
        val p = e.project ?: return
        val svc = WorkspaceService.getInstance(p)
        val name = Messages.showInputDialog(p, "Workspace name:", "Use workspace for this project", null, svc.ws ?: "default", null) ?: return
        if (name.isBlank()) return
        svc.bind(name.trim())
        svc.refresh()
        ApplicationManager.getApplication().executeOnPooledThread { com.neex.deliberator.repo.ReattachOnOpen.reattach(p) }
    }
}

/** `deliberator.workspace.create` ("Open Workspace" in the tool window): the bound workspace does not exist yet; `workspace.open` creates it. */
class CreateWorkspaceAction : WorkspaceAction() {
    override fun enabledFor(svc: WorkspaceService) = svc.ws != null && svc.model.missing
    override fun perform(project: Project, svc: WorkspaceService) =
        Ui.background(project, "Deliberator: open workspace ${svc.ws}", { svc.openWorkspace() })
}

/** `deliberator.workspace.open`: open (creating, through `workspace.open`) the project directory of a workspace as a project. */
class OpenWorkspaceAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        val name = Messages.showInputDialog(e.project, "Workspace name:", "Open workspace", null, "default", null)?.trim() ?: return
        if (name.isEmpty()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val dir = projectDirOf(name)
                ApplicationManager.getApplication().invokeLater {
                    val project = com.intellij.openapi.project.ProjectManager.getInstance().loadAndOpenProject(dir.toString())
                    if (project != null) {
                        WorkspaceService.getInstance(project).bind(name)
                        WorkspaceService.getInstance(project).refresh()
                    }
                }
            } catch (ex: DaemonException) {
                Ui.notify(e.project, "Open workspace", ex.message + (ex.hint?.let { "\n$it" } ?: ""), NotificationType.ERROR)
            }
        }
    }

    companion object {
        /**
         * The project directory of workspace [ws], created on purpose: `workspace.open` answers it (`project`).
         * The IDE does not know where workspaces live; the daemon says.
         */
        fun projectDirOf(ws: String): Path {
            val t = DaemonService.getInstance().client().call("workspace.open", JsonObject().apply { addProperty("ws", ws) })
            if (!t.ok) throw DaemonException(t.errorMessage ?: "workspace.open failed", t.errorType ?: "daemon/error", t.error?.get("hint")?.takeIf { it.isJsonPrimitive }?.asString)
            val project = t.resultObject().get("project")?.asString ?: throw DaemonException("workspace.open returned no project", "workspace/no-path")
            return Path.of(project)
        }
    }
}
