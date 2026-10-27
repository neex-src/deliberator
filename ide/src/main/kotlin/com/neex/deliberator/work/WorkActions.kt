package com.neex.deliberator.work

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.neex.deliberator.actions.WorkspaceAction
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.repo.RepositoryAttacher
import com.neex.deliberator.ui.Ui
import com.neex.deliberator.workspace.WorkspaceService
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent

/** `repo.clone` behind a modal progress; answers the repository, or null after telling the user why not. EDT. */
internal fun cloneByUrl(project: Project, url: String): RepositoryAttacher.Repo? {
    var repo: RepositoryAttacher.Repo? = null
    var err: Exception? = null
    ProgressManager.getInstance().runProcessWithProgressSynchronously({
        try { repo = WorkOps.getInstance(project).clone(url) } catch (e: Exception) { err = e }
    }, "Cloning $url", true, project)
    err?.let { e ->
        Messages.showErrorDialog(project, (e.message ?: e.toString()) + ((e as? DaemonException)?.hint?.let { "\n$it" } ?: ""), "Clone Repository")
    }
    return repo
}

private fun repoLabel(r: RepositoryAttacher.Repo) = if (r.bare) "${r.name}  (clone of ${r.url ?: r.path})" else "${r.name}  (checkout, ${r.kind})"

/** The repository chooser shared by Start Working and Open Pull Request: every repository of the workspace, a button to clone one more by its URL. */
internal class RepoChooser(private val project: Project, repos: List<RepositoryAttacher.Repo>, preselect: String? = null) {
    val combo = ComboBox(DefaultComboBoxModel(repos.toTypedArray())).apply {
        renderer = textListCellRenderer { it?.let(::repoLabel) ?: "" }
        selectedItem = repos.firstOrNull { it.name == preselect } ?: repos.firstOrNull()
    }
    val cloneButton = JButton("Clone by URL...").apply {
        addActionListener {
            val url = Messages.showInputDialog(project, "Git URL or path to clone (a bare clone is made in the workspace):", "Clone Repository", null) ?: return@addActionListener
            if (url.isBlank()) return@addActionListener
            cloneByUrl(project, url)?.let { r ->
                val model = combo.model as DefaultComboBoxModel<RepositoryAttacher.Repo>
                if ((0 until model.size).none { model.getElementAt(it).name == r.name }) model.addElement(r)
                combo.selectedItem = (0 until model.size).map { model.getElementAt(it) }.first { it.name == r.name }
            }
        }
    }
    val selected: RepositoryAttacher.Repo? get() = combo.selectedItem as? RepositoryAttacher.Repo
}

/** M5.1: a repository (clone one by URL if needed) and an optional branch name; OK makes the worktree and attaches it. */
class StartWorkingDialog(project: Project, repos: List<RepositoryAttacher.Repo>, preselect: String? = null) : DialogWrapper(project) {
    internal val chooser = RepoChooser(project, repos, preselect)
    val branch = JBTextField(24).apply { emptyText.text = "default: work-<yyyyMMdd-HHmmss>" }

    init { title = "Start Working"; setOKButtonText("Start"); init() }

    override fun createCenterPanel(): JComponent = panel {
        row("Repository:") { cell(chooser.combo); cell(chooser.cloneButton) }
        row("Branch:") { cell(branch).focused() }
        row { comment("A fresh worktree from the latest default branch of origin; an existing branch is checked out as it is.") }
    }

    override fun doValidate() = if (chooser.selected == null) ValidationInfo("Clone a repository first", chooser.cloneButton) else null

    fun answer(): Pair<String, String?> = chooser.selected!!.name to branch.text.trim().ifEmpty { null }
}

/** M5.5: a repository and the number of its pull request; GitHub owner/name only when the clone's URL does not say. */
class OpenPrDialog(project: Project, repos: List<RepositoryAttacher.Repo>, preselect: String? = null) : DialogWrapper(project) {
    internal val chooser = RepoChooser(project, repos, preselect)
    val number = JBTextField(8)
    val github = JBTextField(24).apply { emptyText.text = "optional: owner/name" }

    init { title = "Open Pull Request"; setOKButtonText("Open"); init() }

    override fun createCenterPanel(): JComponent = panel {
        row("Repository:") { cell(chooser.combo); cell(chooser.cloneButton) }
        row("Pull request #:") { cell(number).focused() }
        row("GitHub:") { cell(github) }
    }

    override fun doValidate(): ValidationInfo? = when {
        chooser.selected == null -> ValidationInfo("Clone a repository first", chooser.cloneButton)
        number.text.trim().toIntOrNull()?.let { it > 0 } != true -> ValidationInfo("The pull request number", number)
        else -> null
    }

    fun answer(): Triple<String, Int, String?> = Triple(chooser.selected!!.name, number.text.trim().toInt(), github.text.trim().ifEmpty { null })
}

/** The repositories off the EDT, then [then] on the EDT; a failure is a balloon. */
private fun withRepos(project: Project, title: String, then: (List<RepositoryAttacher.Repo>) -> Unit) {
    ApplicationManager.getApplication().executeOnPooledThread {
        try {
            val repos = WorkOps.getInstance(project).repos()
            ApplicationManager.getApplication().invokeLater({ then(repos) }, ModalityState.nonModal())
        } catch (e: DaemonException) {
            Ui.notify(project, title, e.message + (e.hint?.let { "\n$it" } ?: ""), NotificationType.ERROR)
        }
    }
}

/** `deliberator.work.start`: M5.1. */
class StartWorkingAction : WorkspaceAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val p = e.project ?: return
        start(p, e.getData(WorkPanel.REPO)?.name)
    }

    override fun perform(project: Project, svc: WorkspaceService) = start(project, null)

    private fun start(project: Project, pre: String?) {
        withRepos(project, "Start Working") { repos ->
            val d = StartWorkingDialog(project, repos, pre)
            if (!d.showAndGet()) return@withRepos
            val (repo, branch) = d.answer()
            startWorking(project, repo, branch)
        }
    }

    companion object {
        fun startWorking(project: Project, repo: String, branch: String?) = Ui.background(project, "Deliberator: start working on $repo", {
            val w = WorkOps.getInstance(project).startWorking(repo, branch)
            Ui.notify(project, "Working on $repo", "${w.worktree.name} (${w.worktree.branch ?: "detached"}) at ${w.worktree.path}\n" +
                (w.attached?.let { "attached as ${it.repo.kind}" + (it.note?.let { n -> ": $n" } ?: "") } ?: ("not attached: " + w.problem)),
                if (w.attached == null) NotificationType.WARNING else NotificationType.INFORMATION)
        })
    }
}

/** `deliberator.work.clone`: a bare clone by URL. */
class CloneRepositoryAction : WorkspaceAction() {
    override fun perform(project: Project, svc: WorkspaceService) {
        val url = Messages.showInputDialog(project, "Git URL or path to clone:", "Clone Repository", null)?.trim() ?: return
        if (url.isEmpty()) return
        Ui.background(project, "Deliberator: clone $url", {
            val r = WorkOps.getInstance(project).clone(url)
            Ui.notify(project, "Cloned ${r.name}", "A bare clone (${r.path}). Start Working... makes a worktree of it.")
        })
    }
}

/** `deliberator.pr.open`: M5.5. */
class OpenPrAction : WorkspaceAction() {
    override fun perform(project: Project, svc: WorkspaceService) {
        withRepos(project, "Open Pull Request") { repos ->
            val d = OpenPrDialog(project, repos)
            if (!d.showAndGet()) return@withRepos
            val (repo, n, gh) = d.answer()
            openPr(project, repo, n, gh)
        }
    }

    companion object {
        /** `pr.open`, the worktree attached, the review comments and the checks opened as text. */
        fun openPr(project: Project, repo: String, n: Int, github: String?) = Ui.background(project, "Deliberator: open $repo#$n", {
            val pr = WorkOps.getInstance(project).openPr(repo, n, github)
            pr.reviewPath?.let { Ui.openFileLater(project, it) }
            pr.checksPath?.let { Ui.openFileLater(project, it) }
            val w = pr.working
            Ui.notify(project, "$repo#$n" + (pr.title?.let { ": $it" } ?: ""),
                "worktree ${w.worktree.name} at ${w.worktree.path}" + (if (w.attached == null) " (not attached: ${w.problem})" else " (attached)") +
                    (pr.checks?.let { "\nchecks: $it" } ?: "") + (pr.note?.let { "\n$it" } ?: ""),
                if (pr.note != null || w.attached == null) NotificationType.WARNING else NotificationType.INFORMATION)
        })
    }
}

/** `deliberator.work.remove`: the selected worktree (a popup of them from a menu); one with changes is refused by the daemon and then asked again with force. */
class RemoveWorktreeAction : WorkspaceAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val p = e.project ?: return
        val sel = e.getData(WorkPanel.WORKTREE)
        if (sel != null) { remove(p, sel); return }
        val all = WorkOps.getInstance(p).snapshot.worktrees
        if (all.isEmpty()) { Ui.notify(p, "Remove Worktree", "No worktrees.", NotificationType.WARNING); return }
        JBPopupFactory.getInstance().createPopupChooserBuilder(all).setTitle("Remove Worktree")
            .setRenderer(textListCellRenderer { "${it.repo}/${it.name}  ${it.branch ?: ""}" })
            .setItemChosenCallback { remove(p, it) }.createPopup().showCenteredInCurrentWindow(p)
    }

    override fun perform(project: Project, svc: WorkspaceService) {}

    private fun remove(project: Project, wt: Worktree) {
        if (Messages.showYesNoDialog(project, "Remove the worktree ${wt.repo}/${wt.name}?\n${wt.path}\nThe branch and its commits stay in the repository.", "Remove Worktree",
                "Remove", "Cancel", Messages.getQuestionIcon()) != Messages.YES) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val ops = WorkOps.getInstance(project)
            try {
                ops.removeWorktree(wt)
                Ui.notify(project, "Worktree removed", "${wt.repo}/${wt.name}")
            } catch (e: DaemonException) {
                if (e.type == "worktree/dirty") {
                    ApplicationManager.getApplication().invokeLater({
                        if (Messages.showYesNoDialog(project, "${e.message}\n\nRemove it anyway? The changes are lost.", "Remove Worktree", "Remove Anyway", "Keep", Messages.getWarningIcon()) == Messages.YES)
                            Ui.background(project, "Removing ${wt.name}", { ops.removeWorktree(wt, force = true); Ui.notify(project, "Worktree removed", "${wt.repo}/${wt.name}") })
                    }, ModalityState.nonModal())
                } else Ui.notify(project, "Remove Worktree", e.message + (e.hint?.let { "\n$it" } ?: ""), NotificationType.ERROR)
            }
        }
    }
}

/** `deliberator.work.refresh` */
class RefreshWorkAction : WorkspaceAction() {
    override fun perform(project: Project, svc: WorkspaceService) {
        ApplicationManager.getApplication().executeOnPooledThread { runCatching { WorkOps.getInstance(project).refreshNow() } }
    }
}
