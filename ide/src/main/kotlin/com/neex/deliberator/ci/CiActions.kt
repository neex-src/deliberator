package com.neex.deliberator.ci

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.neex.deliberator.actions.WorkspaceAction
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.ui.Ui
import com.neex.deliberator.workspace.WorkspaceService

/** What the CI view's gestures do with a [CiOpen]; shared by the tree's double-click and the actions. */
object CiController {
    /** The default action on a run (red: diff, running: follow, else log). Starts a background task. */
    fun open(project: Project, sel: CiSelection) = Ui.background(project, "Deliberator: ${sel.repo} run ${sel.run.id}", {
        handle(project, CiOps.getInstance(project).openDefault(sel.repo, sel.run))
    })

    /** Open the log of a job the user names (a chooser of the run's jobs). */
    fun jobLog(project: Project, sel: CiSelection) = pick(project, sel, CiDefault.LOG)

    /** The diff of a job against its last green run. */
    fun diff(project: Project, sel: CiSelection) =
        if (sel.run.red) Ui.background(project, "Deliberator: diff run ${sel.run.id}", { handle(project, CiOps.getInstance(project).diff(sel.repo, sel.run.id)) })
        else pick(project, sel, CiDefault.DIFF)

    fun follow(project: Project, sel: CiSelection) = pick(project, sel, CiDefault.FOLLOW)

    private fun pick(project: Project, sel: CiSelection, how: CiDefault) = Ui.background(project, "Deliberator: jobs of run ${sel.run.id}", {
        val names = CiOps.getInstance(project).jobNames(sel.repo, sel.run.id)
        handle(project, if (names.size == 1) CiOps.getInstance(project).openJob(sel.repo, sel.run.id, names[0], how) else CiOpen.Choose(sel.repo, sel.run.id, names, how))
    })

    /** Acts on a [CiOpen] from a background thread: opens the file, starts the follow, asks for the job. */
    fun handle(project: Project, o: CiOpen) {
        when (o) {
            is CiOpen.Text -> ApplicationManager.getApplication().invokeLater({
                if (!Ui.openFile(project, o.path)) Ui.notify(project, "CI", "Cannot open ${o.path}", NotificationType.WARNING)
                else o.note?.let { Ui.notify(project, "CI: ${o.what}", it) }
            }, ModalityState.nonModal())
            is CiOpen.Follow -> {
                val h = CiOps.getInstance(project).tail(o.repo, o.run, o.job)
                CiConsoles.showLater(project, h)
            }
            is CiOpen.Choose -> ApplicationManager.getApplication().invokeLater({
                if (o.jobs.isEmpty()) { Ui.notify(project, "CI", "Run ${o.run} has no jobs.", NotificationType.WARNING); return@invokeLater }
                JBPopupFactory.getInstance().createPopupChooserBuilder(o.jobs).setTitle("Job of run ${o.run}" + when (o.then) { CiDefault.FOLLOW -> " (follow)"; CiDefault.DIFF -> " (diff)"; else -> "" })
                    .setItemChosenCallback { job ->
                        Ui.background(project, "Deliberator: $job", { handle(project, CiOps.getInstance(project).openJob(o.repo, o.run, job, o.then)) })
                    }.createPopup().showCenteredInCurrentWindow(project)
            }, ModalityState.nonModal())
        }
    }
}

/** Base of the CI toolbar actions: enabled with a project bound to a workspace; the run (if any) comes from the tree's selection. */
abstract class CiAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    open val needsRun: Boolean get() = true

    override fun update(e: AnActionEvent) {
        val p = e.project
        e.presentation.isEnabledAndVisible = p != null
        if (p != null) e.presentation.isEnabled = WorkspaceService.getInstance(p).ws != null && (!needsRun || e.getData(CiPanel.SELECTION) != null)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val p = e.project ?: return
        val sel = e.getData(CiPanel.SELECTION)
        if (needsRun && sel == null) { Ui.notify(p, "CI", "Select a run in the CI tab.", NotificationType.WARNING); return }
        go(p, sel, e.getData(CiPanel.PANEL))
    }

    abstract fun go(project: Project, sel: CiSelection?, panel: CiPanel?)
}

/** `deliberator.ci.open`: the default action of the run. */
class CiOpenAction : CiAction() { override fun go(project: Project, sel: CiSelection?, panel: CiPanel?) = CiController.open(project, sel!!) }

/** `deliberator.ci.jobLog` */
class CiJobLogAction : CiAction() { override fun go(project: Project, sel: CiSelection?, panel: CiPanel?) = CiController.jobLog(project, sel!!) }

/** `deliberator.ci.diff` */
class CiDiffAction : CiAction() { override fun go(project: Project, sel: CiSelection?, panel: CiPanel?) = CiController.diff(project, sel!!) }

/** `deliberator.ci.follow` */
class CiFollowAction : CiAction() { override fun go(project: Project, sel: CiSelection?, panel: CiPanel?) = CiController.follow(project, sel!!) }

/** `deliberator.ci.runsText`: the text file `ci.runs` wrote for the selected repository. */
class CiRunsTextAction : CiAction() {
    override val needsRun get() = false
    override fun go(project: Project, sel: CiSelection?, panel: CiPanel?) {
        val path = panel?.runsPath()
        if (path == null) Ui.notify(project, "CI", "Select a repository whose runs are loaded.", NotificationType.WARNING)
        else if (!Ui.openFile(project, path)) Ui.notify(project, "CI", "Cannot open $path", NotificationType.WARNING)
    }
}

/** `deliberator.ci.refresh`: reload the runs of the selected repository (all loaded ones without a selection). */
class CiRefreshAction : CiAction() {
    override val needsRun get() = false
    override fun go(project: Project, sel: CiSelection?, panel: CiPanel?) {
        val repo = panel?.selectedRepoName()
        if (repo != null) panel.load(repo) else Ui.notify(project, "CI", "Select a repository.", NotificationType.WARNING)
    }
}

/** `deliberator.ci.addRepo`: a GitHub repository (owner/name or URL) to show next to the clones. */
class CiAddRepoAction : CiAction() {
    override val needsRun get() = false
    override fun go(project: Project, sel: CiSelection?, panel: CiPanel?) {
        val name = Messages.showInputDialog(project, "GitHub repository (owner/name, or a github.com URL):", "Add Repository to CI", null) ?: return
        panel?.addRepo(name)
    }
}

/** `deliberator.ci.removeRepo` */
class CiRemoveRepoAction : CiAction() {
    override val needsRun get() = false
    override fun go(project: Project, sel: CiSelection?, panel: CiPanel?) {
        val repo = panel?.selectedRepoName() ?: return
        if (repo in (panel.typedRepos())) panel.removeRepo(repo) else Ui.notify(project, "CI", "$repo is a clone of the workspace; only repositories added here can be removed.")
    }
}

/**
 * `deliberator.ci.local` (M5.4): the workflow job at the caret of a workflow file (`.github/workflows/NAME.yml`) (a chooser when the caret is not in one) runs locally with
 * `ci.local`: act on the runtime's Podman with the local environment's endpoint; the output is a console tab, the result its last line.
 */
class RunJobLocallyAction : WorkspaceAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        val vf = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val workflow = vf != null && WorkflowFiles.locate(vf.path) != null
        e.presentation.isVisible = e.project != null && (workflow || e.place != com.intellij.openapi.actionSystem.ActionPlaces.EDITOR_POPUP)
        e.presentation.isEnabled = e.presentation.isEnabled && workflow
    }

    override fun perform(project: Project, svc: WorkspaceService) {}

    override fun actionPerformed(e: AnActionEvent) {
        val p = e.project ?: return
        val vf = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val loc = vf?.let { WorkflowFiles.locate(it.path) }
        if (vf == null || loc == null) { Ui.notify(p, "Run job locally", "Open a workflow file (.github/workflows/*.yml) first.", NotificationType.WARNING); return }
        val text = e.getData(CommonDataKeys.EDITOR)?.document?.text ?: String(vf.contentsToByteArray(), Charsets.UTF_8)
        val jobs = WorkflowFiles.jobs(text)
        if (jobs.isEmpty()) { Ui.notify(p, "Run job locally", "${vf.name} has no jobs: section.", NotificationType.WARNING); return }
        val line = e.getData(CommonDataKeys.EDITOR)?.caretModel?.logicalPosition?.line ?: -1
        val at = if (line >= 0) WorkflowFiles.jobAt(jobs, line) else null
        if (at != null) { run(p, loc.first, loc.second, at.id); return }
        JBPopupFactory.getInstance().createPopupChooserBuilder(jobs.map { it.id }).setTitle("Run job locally (${loc.second})")
            .setItemChosenCallback { run(p, loc.first, loc.second, it) }.createPopup().showCenteredInCurrentWindow(p)
    }

    companion object {
        /** `ci.local` into a console tab; the session is asked for first (off the EDT). */
        fun run(project: Project, dir: String, workflow: String, job: String) = Ui.background(project, "Deliberator: ci local $workflow:$job", {
            CiConsoles.showLater(project, CiOps.getInstance(project).local(dir, workflow, job))
        })
    }
}
