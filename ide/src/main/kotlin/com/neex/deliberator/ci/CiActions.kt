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
    /**
     * Enter or a double-click. A step opens its own log, a job its log (a job that still runs is followed); a red step or job gets its diff from the action
     * (Diff Against Last Green), not from Enter. A run keeps its default: red: the diff against the last green run, running: follow, else its log.
     */
    fun open(project: Project, sel: CiSelection) = when {
        sel.step != null || sel.job != null -> log(project, sel)
        else -> Ui.background(project, "Deliberator: ${sel.repo} run ${sel.run.id}", { handle(project, CiOps.getInstance(project).openDefault(sel.repo, sel.run)) })
    }

    /** Open Log: the step's lines, the job's log, or (a run) the log of a job the user names. */
    fun log(project: Project, sel: CiSelection) = when {
        sel.step != null -> Ui.background(project, "Deliberator: step ${sel.step.number} of ${sel.job?.name}", {
            val job = sel.job!!.id
            handle(project, try {
                CiOps.getInstance(project).log(sel.repo, sel.run.id, job, sel.step.number.toString())
            } catch (e: DaemonException) {
                // a job that still runs has no log yet: its console follows it
                if (e.type == "ci/log-not-available" && sel.job.state == CiState.RUNNING) CiOpen.Follow(sel.repo, sel.run.id, job) else throw e
            })
        })
        sel.job != null -> Ui.background(project, "Deliberator: ${sel.job.name}", { handle(project, CiOps.getInstance(project).openJob(sel.repo, sel.run.id, sel.job.id, CiDefault.LOG)) })
        else -> pick(project, sel, CiDefault.LOG)
    }

    /** Diff Against Last Green: the failed step's lines (or the job's log) against the same of the last green run. */
    fun diff(project: Project, sel: CiSelection) = when {
        sel.step != null -> Ui.background(project, "Deliberator: diff step ${sel.step.number}", { handle(project, CiOps.getInstance(project).diff(sel.repo, sel.run.id, sel.job!!.id, sel.step.number.toString())) })
        sel.job != null -> Ui.background(project, "Deliberator: diff ${sel.job.name}", { handle(project, CiOps.getInstance(project).diff(sel.repo, sel.run.id, sel.job.id)) })
        sel.run.red -> Ui.background(project, "Deliberator: diff run ${sel.run.id}", { handle(project, CiOps.getInstance(project).diff(sel.repo, sel.run.id)) })
        else -> pick(project, sel, CiDefault.DIFF)
    }

    fun follow(project: Project, sel: CiSelection) =
        if (sel.job != null) handleLater(project, CiOpen.Follow(sel.repo, sel.run.id, sel.job.id)) else pick(project, sel, CiDefault.FOLLOW)

    /** Where the CI system shows the row (the run, the job, the step's place in the job). */
    fun browse(project: Project, sel: CiSelection) {
        val url = sel.url()
        if (url == null) Ui.notify(project, "CI", "This run has no page of its own.", NotificationType.WARNING) else com.intellij.ide.BrowserUtil.browse(url)
    }

    fun copy(sel: CiSelection) = com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(sel.text()))

    private fun handleLater(project: Project, o: CiOpen) = Ui.background(project, "Deliberator: follow", { handle(project, o) })

    private fun pick(project: Project, sel: CiSelection, how: CiDefault) = Ui.background(project, "Deliberator: jobs of run ${sel.run.id}", {
        val names = CiOps.getInstance(project).jobNames(sel.repo, sel.run.id)
        handle(project, if (names.size == 1) CiOps.getInstance(project).openJob(sel.repo, sel.run.id, names[0], how) else CiOpen.Choose(sel.repo, sel.run.id, names, how))
    })

    /** Acts on a [CiOpen] from a background thread: opens the file, starts the follow, asks for the job. */
    fun handle(project: Project, o: CiOpen) {
        when (o) {
            is CiOpen.Text -> Ui.openFileLater(project, o.path) { ok ->
                if (!ok) Ui.notify(project, "CI", "Cannot open ${o.path}", NotificationType.WARNING)
                else o.note?.let { Ui.notify(project, "CI: ${o.what}", it) }
            }
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

    /** Whether the action applies to the selected row (a red step has a diff, a finished job has nothing to follow). */
    open fun enabledFor(sel: CiSelection): Boolean = true

    override fun update(e: AnActionEvent) {
        val p = e.project
        e.presentation.isEnabledAndVisible = p != null
        if (p != null) {
            val sel = e.getData(CiPanel.SELECTION)
            e.presentation.isEnabled = WorkspaceService.getInstance(p).ws != null && (!needsRun || (sel != null && enabledFor(sel)))
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val p = e.project ?: return
        val sel = e.getData(CiPanel.SELECTION)
        if (needsRun && sel == null) { Ui.notify(p, "CI", "Select a run, job or step in the CI tab.", NotificationType.WARNING); return }
        go(p, sel, e.getData(CiPanel.PANEL))
    }

    abstract fun go(project: Project, sel: CiSelection?, panel: CiPanel?)
}

/** `deliberator.ci.open`: the default action of the row. */
class CiOpenAction : CiAction() { override fun go(project: Project, sel: CiSelection?, panel: CiPanel?) = CiController.open(project, sel!!) }

/** `deliberator.ci.jobLog` (Open Log): a step's lines, a job's log, or a job of the run to choose. */
class CiJobLogAction : CiAction() { override fun go(project: Project, sel: CiSelection?, panel: CiPanel?) = CiController.log(project, sel!!) }

/** `deliberator.ci.diff`: against the last green run; a job or a step must be red, a run is asked for its job. */
class CiDiffAction : CiAction() {
    override fun enabledFor(sel: CiSelection) = when {
        sel.step != null -> sel.step.state == CiState.RED
        sel.job != null -> sel.job.state == CiState.RED
        else -> true
    }
    override fun go(project: Project, sel: CiSelection?, panel: CiPanel?) = CiController.diff(project, sel!!)
}

/** `deliberator.ci.follow`: a job or a run that is in progress. */
class CiFollowAction : CiAction() {
    override fun enabledFor(sel: CiSelection) = when {
        sel.job != null -> sel.job.state == CiState.RUNNING || sel.job.state == CiState.QUEUED
        else -> sel.run.running
    }
    override fun go(project: Project, sel: CiSelection?, panel: CiPanel?) = CiController.follow(project, sel!!)
}

/** `deliberator.ci.browser`: the run, job or step on the page of the CI system (a URL: nothing is asked of it). */
class CiBrowserAction : CiAction() {
    override fun enabledFor(sel: CiSelection) = sel.url() != null
    override fun go(project: Project, sel: CiSelection?, panel: CiPanel?) = CiController.browse(project, sel!!)
}

/** `deliberator.ci.copy`: the row as text (a run with its jobs and steps). */
class CiCopyAction : CiAction() { override fun go(project: Project, sel: CiSelection?, panel: CiPanel?) = CiController.copy(sel!!) }

/** `deliberator.ci.runsText`: the text file `ci.runs` wrote for the selected repository. */
class CiRunsTextAction : CiAction() {
    override val needsRun get() = false
    override fun go(project: Project, sel: CiSelection?, panel: CiPanel?) {
        val path = panel?.runsPath()
        if (path == null) Ui.notify(project, "CI", "Select a repository whose runs are loaded.", NotificationType.WARNING)
        else Ui.openFileLater(project, path) { ok -> if (!ok) Ui.notify(project, "CI", "Cannot open $path", NotificationType.WARNING) }
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
 * `ci.local`: act on the runtime's container engine with the local environment's endpoint; the output is a console tab, the result its last line.
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
