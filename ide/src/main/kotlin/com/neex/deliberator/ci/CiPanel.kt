package com.neex.deliberator.ci

import com.intellij.ide.util.PropertiesComponent
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
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.work.WorkOps
import com.neex.deliberator.workspace.WorkspaceService
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** A run of a repository, as the actions get it (the data of the selected tree node). */
data class CiSelection(val repo: String, val run: CiRun)

/**
 * The "CI" tab (M5.2/M5.3): the repositories of the workspace (its clones, plus the `owner/name` ones added here and remembered per project), their runs per
 * workflow from `ci.runs`, newest first. Enter or a double-click is the default action of the run (red: the diff against the last green run; running: follow;
 * otherwise its log), the other actions are in the toolbar and the popup. It renders what the daemon answered; the logs and diffs are text files it opens.
 */
class CiPanel(private val project: Project) : JPanel(BorderLayout()), UiDataProvider, Disposable {
    private val root = DefaultMutableTreeNode("ci")
    private val model = DefaultTreeModel(root)
    val tree = Tree(model)
    private val status = JBLabel(" ")
    private val loaded = HashMap<String, CiRuns>()

    internal class RepoNode(val name: String, val typed: Boolean) { var runs: CiRuns? = null; var error: String? = null; var loading = false; override fun toString() = name }
    internal class WorkflowNode(val name: String, val count: Int) { override fun toString() = "$name ($count)" }
    internal class RunNode(val repo: String, val run: CiRun) { override fun toString() = run.label() }
    internal class NoteNode(val text: String, val error: Boolean) { override fun toString() = text }

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(t: javax.swing.JTree, value: Any?, sel: Boolean, exp: Boolean, leaf: Boolean, row: Int, focus: Boolean) {
                when (val u = (value as? DefaultMutableTreeNode)?.userObject) {
                    is RepoNode -> { append(u.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES); if (u.typed) append("   added here", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                    is WorkflowNode -> append(u.toString(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    is RunNode -> append(u.run.label(), when {
                        u.run.red -> SimpleTextAttributes.ERROR_ATTRIBUTES
                        u.run.running -> SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES
                        else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                    })
                    is NoteNode -> append(u.text, if (u.error) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    else -> append(u.toString())
                }
            }
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) selection()?.let { CiController.open(project, it) } }
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) { if (e.keyCode == KeyEvent.VK_ENTER) selection()?.let { CiController.open(project, it) } }
        })
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(e: TreeExpansionEvent) {
                val n = (e.path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? RepoNode ?: return
                if (n.runs == null && !n.loading) load(n.name)
            }
            override fun treeCollapsed(e: TreeExpansionEvent) {}
        })
        val group = ActionManager.getInstance().getAction("deliberator.ci") as DefaultActionGroup
        val bar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_TOOLBAR_BAR, group, true)
        bar.targetComponent = this
        add(JPanel(BorderLayout()).apply { add(bar.component, BorderLayout.NORTH); add(status, BorderLayout.SOUTH) }, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
        PopupHandler.installPopupMenu(tree, group, ActionPlaces.POPUP)
        WorkOps.getInstance(project).addListener(this) { ApplicationManager.getApplication().invokeLater({ rebuildRepos() }, ModalityState.any()) }
        rebuildRepos()
    }

    // repositories ---------------------------------------------------------------------------------------------

    /** The repositories added here (`owner/name` or a GitHub URL), remembered per project. */
    fun typedRepos(): List<String> = PropertiesComponent.getInstance(project).getValue(KEY)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    fun addRepo(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        PropertiesComponent.getInstance(project).setValue(KEY, (typedRepos() + n).distinct().joinToString("\n"))
        rebuildRepos()
        load(n)
    }

    fun removeRepo(name: String) {
        PropertiesComponent.getInstance(project).setValue(KEY, typedRepos().filter { it != name }.joinToString("\n"))
        loaded.remove(name)
        rebuildRepos()
    }

    private fun repoNames(): List<Pair<String, Boolean>> {
        val typed = typedRepos()
        val clones = WorkOps.getInstance(project).snapshot.repos.filter { it.bare }.map { it.name }
        return (clones.map { it to false } + typed.map { it to true }).distinctBy { it.first }
    }

    private fun rebuildRepos() {
        val open = (0 until tree.rowCount).mapNotNull { tree.getPathForRow(it) }.filter { tree.isExpanded(it) }
            .mapNotNull { ((it.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? RepoNode)?.name }.toSet()
        root.removeAllChildren()
        val names = repoNames()
        for ((name, typed) in names) root.add(DefaultMutableTreeNode(RepoNode(name, typed).also { it.runs = loaded[name] }).also { n ->
            val r = loaded[name]
            if (r != null) fill(n, r) else n.add(DefaultMutableTreeNode(NoteNode("expand to load the runs", false)))
        })
        model.reload()
        for (i in 0 until root.childCount) {
            val n = root.getChildAt(i) as DefaultMutableTreeNode
            if ((n.userObject as RepoNode).name in open) tree.expandPath(TreePath(n.path))
        }
        status.text = if (names.isEmpty()) "No repositories: Add Repository... (owner/name of a GitHub repository), or Clone Repository... in the Work tab" else " "
    }

    // runs -----------------------------------------------------------------------------------------------------

    /** Loads the runs of [repo] off the EDT and shows them (the repository's node is found by name). */
    fun load(repo: String) {
        repoNode(repo)?.let { (it.userObject as RepoNode).loading = true }
        status.text = "ci.runs $repo ..."
        ApplicationManager.getApplication().executeOnPooledThread {
            var runs: CiRuns? = null
            var err: String? = null
            try { runs = CiOps.getInstance(project).runs(repo) } catch (e: DaemonException) { err = e.message + (e.hint?.let { " ($it)" } ?: "") }
            ApplicationManager.getApplication().invokeLater({ show(repo, runs, err) }, ModalityState.any())
        }
    }

    /** Renders an answer (or an error) for [repo]; the tree keeps the node expanded. */
    internal fun show(repo: String, runs: CiRuns?, error: String?) {
        val n = repoNode(repo) ?: return
        val rn = n.userObject as RepoNode
        rn.loading = false
        rn.runs = runs
        rn.error = error
        if (runs != null) loaded[repo] = runs else loaded.remove(repo)
        n.removeAllChildren()
        if (runs != null) fill(n, runs) else n.add(DefaultMutableTreeNode(NoteNode(error ?: "failed", true)))
        model.nodeStructureChanged(n)
        for (i in 0 until n.childCount) tree.expandPath(TreePath((n.getChildAt(i) as DefaultMutableTreeNode).path))
        tree.expandPath(TreePath(n.path))
        status.text = if (runs != null) "$repo: ${runs.rows.size} of ${runs.total} runs" + (runs.path?.let { "  ·  text: Open Runs Text" } ?: "") else "$repo: $error"
    }

    private fun fill(n: DefaultMutableTreeNode, runs: CiRuns) {
        if (runs.rows.isEmpty()) { n.add(DefaultMutableTreeNode(NoteNode("no runs", false))); return }
        for ((wf, rows) in runs.byWorkflow()) n.add(DefaultMutableTreeNode(WorkflowNode(wf, rows.size)).also { w -> rows.forEach { w.add(DefaultMutableTreeNode(RunNode(n.userObject.toString(), it))) } })
    }

    private fun repoNode(repo: String): DefaultMutableTreeNode? =
        (0 until root.childCount).map { root.getChildAt(it) as DefaultMutableTreeNode }.firstOrNull { (it.userObject as RepoNode).name == repo }

    /** The tree as text, one line per node, indented (tests, and what the UI-tree dump shows). */
    fun lines(): List<String> = buildList {
        fun walk(n: DefaultMutableTreeNode, depth: Int) {
            for (i in 0 until n.childCount) { val c = n.getChildAt(i) as DefaultMutableTreeNode; add("  ".repeat(depth) + c.userObject); walk(c, depth + 1) }
        }
        walk(root, 0)
    }

    fun statusText(): String = status.text

    /** The text file `ci.runs` wrote for the selected repository, or null. */
    fun runsPath(): String? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.let { sel ->
        var n: DefaultMutableTreeNode? = sel
        while (n != null && n.userObject !is RepoNode) n = n.parent as? DefaultMutableTreeNode
        (n?.userObject as? RepoNode)?.runs?.path
    }

    fun selectedRepoName(): String? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.let { sel ->
        var n: DefaultMutableTreeNode? = sel
        while (n != null && n.userObject !is RepoNode) n = n.parent as? DefaultMutableTreeNode
        (n?.userObject as? RepoNode)?.name
    }

    fun selection(): CiSelection? = ((tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? RunNode)?.let { CiSelection(it.repo, it.run) }

    override fun uiDataSnapshot(sink: DataSink) {
        sink[SELECTION] = selection()
        sink[PANEL] = this
    }

    override fun dispose() {}

    companion object {
        const val KEY = "deliberator.ci.repos"
        val SELECTION: DataKey<CiSelection> = DataKey.create("deliberator.ci.selection")
        val PANEL: DataKey<CiPanel> = DataKey.create("deliberator.ci.panel")
    }
}
