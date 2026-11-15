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
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
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
        sel.run.local && sel.run.running -> Ui.notify(project, "CI: ${sel.run.tag}", "The run is in progress on this machine: open it to see its jobs and steps move; a step's log opens when the step has ended.")
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
        sel.run.local -> false      // a local run's steps move in the tree; its console is a tab of its own
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

/** Starts, remembers and cancels the local runs of this session (the Stop of their console, from the tree). */
object CiLocal {
    private val handlers = java.util.concurrent.ConcurrentHashMap<String, DaemonOpProcessHandler>()

    /** `ci.local` of [workflowFile] (relative to the checkout [repo] names) as decided in [spec]; the console is a tab, the tree shows the run on top of its workflow. */
    fun start(project: Project, repo: String, workflowFile: String, spec: CiLocalSpec, selectConsole: Boolean = false) = Ui.background(project, "Deliberator: ci local $workflowFile", {
        val h = CiOps.getInstance(project).local(repo, workflowFile, spec.job, spec.event, spec.inputs)
        wire(project, repo, h)
        CiConsoles.showLater(project, h, selectConsole)
    })

    /** Makes [h] a local run of this IDE: it is remembered (so the tree can cancel it) while it runs, and the tree shows its row at the start and at the end. */
    fun wire(project: Project, repo: String, h: DaemonOpProcessHandler) {
        h.onEvent = { name, json ->
            if (name == "ci.run") {
                val id = json.get("run")?.takeIf { it.isJsonPrimitive }?.asString
                val status = json.get("status")?.takeIf { it.isJsonPrimitive }?.asString
                if (id != null && status == "in_progress") {
                    handlers["$repo|$id"] = h
                    ApplicationManager.getApplication().invokeLater({ CiPanel.of(project)?.reveal(repo, id) }, ModalityState.any())
                } else if (id != null) {
                    handlers.remove("$repo|$id")
                    ApplicationManager.getApplication().invokeLater({ CiPanel.of(project)?.load(repo, false) }, ModalityState.any())
                }
            }
        }
    }

    fun cancellable(repo: String, runId: String) = handlers["$repo|$runId"]?.let { !it.isProcessTerminated } ?: false
    fun cancel(repo: String, runId: String) { handlers["$repo|$runId"]?.destroyProcess() }
}

/** The Run Locally dialog: the event, one job or all, the dispatch inputs with their types and defaults (a choice is a combo box, a boolean a check box). */
class RunLocallyDialog(project: Project, private val wf: CiWorkflow, preJob: String? = null) : DialogWrapper(project) {
    private val event = javax.swing.JComboBox((wf.events.ifEmpty { listOf("workflow_dispatch") }).toTypedArray()).also { if ("workflow_dispatch" in wf.events) it.selectedItem = "workflow_dispatch" }
    private val job = javax.swing.JComboBox((listOf(ALL) + wf.jobs.map { it.id }).toTypedArray()).also { c -> preJob?.let { c.selectedItem = it } }
    private val fields = LinkedHashMap<String, javax.swing.JComponent>()

    init { title = "Run ${wf.name} Locally"; init() }

    override fun createCenterPanel(): javax.swing.JComponent {
        val p = javax.swing.JPanel(java.awt.GridBagLayout())
        var row = 0
        fun add(label: String, c: javax.swing.JComponent, hint: String? = null) {
            p.add(javax.swing.JLabel(label), java.awt.GridBagConstraints().apply { gridx = 0; gridy = row; anchor = java.awt.GridBagConstraints.WEST; insets = java.awt.Insets(2, 0, 2, 8) })
            p.add(c, java.awt.GridBagConstraints().apply { gridx = 1; gridy = row; fill = java.awt.GridBagConstraints.HORIZONTAL; weightx = 1.0; insets = java.awt.Insets(2, 0, 2, 0) })
            hint?.takeIf { it.isNotBlank() }?.let { row++; p.add(com.intellij.ui.components.JBLabel(it).apply { foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground() }, java.awt.GridBagConstraints().apply { gridx = 1; gridy = row; anchor = java.awt.GridBagConstraints.WEST }) }
            row++
        }
        add("Event:", event)
        add("Job:", job, "all jobs run in needs order; one job runs with the jobs it needs")
        for (i in wf.inputs) {
            val c: javax.swing.JComponent = when {
                i.options.isNotEmpty() -> javax.swing.JComboBox(i.options.toTypedArray()).also { b -> i.default?.let { d -> b.selectedItem = d } }
                i.type == "boolean" -> javax.swing.JCheckBox().also { it.isSelected = i.default == "true" }
                else -> javax.swing.JTextField(i.default ?: "", 24)
            }
            fields[i.name] = c
            add(i.name + (if (i.required) " *" else "") + ":", c, i.description)
        }
        return p
    }

    override fun doValidate(): ValidationInfo? {
        if (event.selectedItem != "workflow_dispatch") return null
        for (i in wf.inputs) {
            val c = fields[i.name]
            if (i.required && c is javax.swing.JTextField && c.text.isBlank()) return ValidationInfo("${i.name} is required", c)
        }
        return null
    }

    fun spec(): CiLocalSpec {
        val ev = event.selectedItem as String
        val inputs = if (ev == "workflow_dispatch") fields.mapValues { (_, c) ->
            when (c) { is javax.swing.JComboBox<*> -> c.selectedItem.toString(); is javax.swing.JCheckBox -> c.isSelected.toString(); is javax.swing.JTextField -> c.text; else -> "" }
        }.filter { it.value.isNotEmpty() } else emptyMap()
        return CiLocalSpec(ev, (job.selectedItem as String).takeIf { it != ALL }, inputs)
    }

    companion object { const val ALL = "(all jobs)" }
}

/** `deliberator.ci.runLocally`: the selected workflow row (a workflow of a checkout, with or without runs) runs on this machine, as decided in the dialog. */
class RunLocallyAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val p = e.project
        val w = e.getData(CiPanel.WORKFLOW)?.workflow
        e.presentation.isEnabledAndVisible = p != null
        if (p != null) e.presentation.isEnabled = WorkspaceService.getInstance(p).ws != null && w != null && w.error == null
    }
    override fun actionPerformed(e: AnActionEvent) {
        val p = e.project ?: return
        val sel = e.getData(CiPanel.WORKFLOW) ?: run { Ui.notify(p, "CI", "Select a workflow in the CI tab.", NotificationType.WARNING); return }
        val wf = sel.workflow ?: run { Ui.notify(p, "CI", "${sel.name} has no workflow file in a checkout of this workspace.", NotificationType.WARNING); return }
        val d = RunLocallyDialog(p, wf)
        if (d.showAndGet()) CiLocal.start(p, sel.repo, wf.file, d.spec())
    }
}

/** `deliberator.ci.cancelLocal`: stops the selected local run that this IDE started (the daemon kills act and removes its containers). */
class CiCancelLocalAction : CiAction() {
    override fun enabledFor(sel: CiSelection) = sel.run.local && sel.run.running && CiLocal.cancellable(sel.repo, sel.run.id)
    override fun go(project: Project, sel: CiSelection?, panel: CiPanel?) { CiLocal.cancel(sel!!.repo, sel.run.id) }
}

/**
 * `deliberator.ci.localWorkflow`: the whole workflow of the file in the editor (every job in needs order, the Run Locally dialog for the event and the inputs);
 * next to Run Job Locally in the editor popup.
 */
class RunWorkflowLocallyAction : WorkspaceAction() {
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
        if (vf == null || loc == null) { Ui.notify(p, "Run workflow locally", "Open a workflow file (.github/workflows/*.yml) first.", NotificationType.WARNING); return }
        Ui.background(p, "Deliberator: ci workflows ${vf.name}", {
            val wf = CiOps.getInstance(p).workflows(loc.first).firstOrNull { it.path == vf.path || it.file.endsWith(loc.second) }
            ApplicationManager.getApplication().invokeLater({
                if (wf == null || wf.error != null) { Ui.notify(p, "Run workflow locally", "${vf.name}: ${wf?.error ?: "not a workflow the daemon could read"}", NotificationType.WARNING); return@invokeLater }
                val d = RunLocallyDialog(p, wf)
                if (d.showAndGet()) CiLocal.start(p, loc.first, wf.file, d.spec(), selectConsole = true)
            }, ModalityState.nonModal())
        })
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
        fun run(project: Project, dir: String, workflow: String, job: String) = CiLocal.start(project, dir, workflow, CiLocalSpec(null, job, emptyMap()), selectConsole = true)
    }
}
