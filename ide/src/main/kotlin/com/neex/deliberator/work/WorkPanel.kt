package com.neex.deliberator.work

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.neex.deliberator.repo.RepositoryAttacher
import com.neex.deliberator.ui.Ui
import com.neex.deliberator.workspace.WorkspaceService
import java.awt.BorderLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.Timer
import javax.swing.table.DefaultTableModel

/**
 * The "Work" tab (M5.1): the repositories of the workspace (bare clones and checkouts) and their worktrees, from `repo.list` and `worktree.list`. It renders
 * [WorkOps.snapshot] only; every button is an action of `deliberator.work`. Double-click on a worktree attaches it to the project (and opens a project-less
 * IDE onto it through the same Attach Repository code); a repository row is the target of Start Working.
 */
class WorkPanel(private val project: Project) : JPanel(BorderLayout()), UiDataProvider, Disposable {
    private val ops = WorkOps.getInstance(project)
    private val repoModel = object : DefaultTableModel(REPO_COLUMNS, 0) { override fun isCellEditable(row: Int, column: Int) = false }
    private val wtModel = object : DefaultTableModel(WT_COLUMNS, 0) { override fun isCellEditable(row: Int, column: Int) = false }
    val repoTable = JBTable(repoModel).apply { setShowGrid(false); setSelectionMode(ListSelectionModel.SINGLE_SELECTION) }
    val worktreeTable = JBTable(wtModel).apply { setShowGrid(false); setSelectionMode(ListSelectionModel.SINGLE_SELECTION) }
    private val hint = JBLabel(" ")
    private var snap = WorkSnapshot()

    init {
        val group = ActionManager.getInstance().getAction("deliberator.work") as DefaultActionGroup
        val bar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_TOOLBAR_BAR, group, true)
        bar.targetComponent = this
        add(JPanel(BorderLayout()).apply { add(bar.component, BorderLayout.NORTH); add(hint, BorderLayout.SOUTH) }, BorderLayout.NORTH)
        add(JPanel(GridLayout(2, 1)).apply {
            add(JPanel(BorderLayout()).apply { border = IdeBorderFactory.createTitledBorder("Repositories"); add(JBScrollPane(repoTable), BorderLayout.CENTER) })
            add(JPanel(BorderLayout()).apply { border = IdeBorderFactory.createTitledBorder("Worktrees (double-click attaches)"); add(JBScrollPane(worktreeTable), BorderLayout.CENTER) })
        }, BorderLayout.CENTER)
        PopupHandler.installPopupMenu(repoTable, group, ActionPlaces.POPUP)
        PopupHandler.installPopupMenu(worktreeTable, group, ActionPlaces.POPUP)
        worktreeTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) selectedWorktree()?.let { wt -> Ui.background(project, "Attaching ${wt.name}", { reportAttach(ops.attach(wt)) }) }
            }
        })
        ops.addListener(this) { s -> ApplicationManager.getApplication().invokeLater({ render(s) }, ModalityState.any()) }
        render(ops.snapshot)
        refresh()
        val timer = Timer(15000) { if (isShowing) refresh() }
        timer.start()
        com.intellij.openapi.util.Disposer.register(this) { timer.stop() }
    }

    private fun reportAttach(w: Working) {
        Ui.notify(project, "Worktree ${w.worktree.name}", w.attached?.let { "attached as ${it.repo.kind}: ${it.note ?: w.worktree.path}" } ?: ("not attached: " + w.problem),
            if (w.attached == null) com.intellij.notification.NotificationType.WARNING else com.intellij.notification.NotificationType.INFORMATION)
    }

    /** Reads `repo.list` and `worktree.list` off the EDT; the listener renders. */
    fun refresh() {
        if (WorkspaceService.getInstance(project).ws == null) return
        ApplicationManager.getApplication().executeOnPooledThread { runCatching { ops.refreshNow() } }
    }

    fun selectedRepo(): RepositoryAttacher.Repo? = repoTable.selectedRow.takeIf { it >= 0 && it < snap.repos.size }?.let { snap.repos[it] }
    fun selectedWorktree(): Worktree? = worktreeTable.selectedRow.takeIf { it >= 0 && it < snap.worktrees.size }?.let { snap.worktrees[it] }

    override fun uiDataSnapshot(sink: DataSink) {
        sink[REPO] = selectedRepo()
        sink[WORKTREE] = selectedWorktree()
    }

    fun render(s: WorkSnapshot) {
        val keepRepo = selectedRepo()?.name
        val keepWt = selectedWorktree()?.let { it.repo to it.name }
        snap = s
        repoModel.rowCount = 0
        s.repos.forEach { repoModel.addRow(repoCells(it)) }
        wtModel.rowCount = 0
        s.worktrees.forEach { wtModel.addRow(worktreeCells(it)) }
        s.repos.indexOfFirst { it.name == keepRepo }.takeIf { it >= 0 }?.let { repoTable.setRowSelectionInterval(it, it) }
        s.worktrees.indexOfFirst { it.repo to it.name == keepWt }.takeIf { it >= 0 }?.let { worktreeTable.setRowSelectionInterval(it, it) }
        hint.text = when {
            WorkspaceService.getInstance(project).ws == null -> "Not a Deliberator workspace project"
            s.error != null -> "ERROR: ${s.error}"
            s.repos.isEmpty() -> "No repositories: Clone Repository... (a git url or path), then Start Working..."
            else -> "${s.repos.size} repositories, ${s.worktrees.size} worktrees"
        }
    }

    override fun dispose() {}

    companion object {
        val REPO: DataKey<RepositoryAttacher.Repo> = DataKey.create("deliberator.work.repo")
        val WORKTREE: DataKey<Worktree> = DataKey.create("deliberator.work.worktree")
        val REPO_COLUMNS = arrayOf("Name", "Kind", "Origin / path")
        val WT_COLUMNS = arrayOf("Repo", "Worktree", "Branch", "Head", "Changes", "Path")

        fun repoCells(r: RepositoryAttacher.Repo): Array<Any> =
            arrayOf(r.name, if (r.bare) "bare clone" else "checkout (${r.kind})", if (r.bare) (r.url ?: r.path) else r.path)

        fun worktreeCells(w: Worktree): Array<Any> =
            arrayOf(w.repo, w.name, w.branch ?: "", w.head ?: "", if (w.missing) "directory gone" else if (w.dirty > 0) "${w.dirty} changed" else "clean", w.path)
    }
}
