package com.neex.deliberator.ci

import com.intellij.icons.AllIcons
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
import com.neex.deliberator.daemon.DeliberatorSettings
import com.neex.deliberator.work.WorkOps
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * What the selected row of the tree is, as the actions get it: a run of a repository, and when the row is a job or a step of it, that job and step. Nothing here
 * says where the run came from: [CiRun.origin] and [CiRun.url] do (a run without a url has no "Open in Browser").
 */
data class CiSelection(val repo: String, val run: CiRun, val job: CiJob? = null, val step: CiStep? = null, val detail: CiRunDetail? = null) {
    /** The row's text (Copy as Text): a run with its jobs and steps when they are loaded. */
    fun text(): String = when {
        step != null -> step.label()
        job != null -> job.label() + job.steps.joinToString("") { "\n  " + it.label() }
        detail != null -> detail.text()
        else -> run.label()
    }

    /** Where the CI system shows this row. */
    fun url(): String? = when {
        step != null && job?.url != null -> job.url + "#step:${step.number}:1"
        job != null -> job.url
        else -> run.url
    }
}

/** A workflow row of the tree: the repository and the workflow file (null file: a workflow only known from its runs). */
data class CiWorkflowSelection(val repo: String, val name: String, val workflow: CiWorkflow?)

/** A node's user object: a stable [key] (the tree reconciles by it, so a refresh keeps expansion and selection) and the way it takes a newer copy of itself. */
internal interface CiObj {
    val key: String
    fun adopt(o: CiObj)
}

internal class RepoNode(val name: String, val typed: Boolean) : CiObj {
    var runs: CiRuns? = null; var error: String? = null; var loading = false; var shown = false; var archiving = false
    var workflows: List<CiWorkflow> = emptyList(); var askedCached = false
    override val key get() = "r:$name"
    override fun adopt(o: CiObj) {}
    override fun toString() = name
}
internal class WorkflowNode(val repo: String, val name: String, var count: Int, var wf: CiWorkflow? = null) : CiObj {
    override val key get() = "w:$name"
    override fun adopt(o: CiObj) { o as WorkflowNode; count = o.count; wf = o.wf }
    override fun toString() = "$name ($count)"
}
internal class RunNode(val repo: String, var run: CiRun) : CiObj {
    var detail: CiRunDetail? = null; var loading = false; var opened = false; val autoOpened = HashSet<String>(); var lastPoll = 0L
    override val key get() = "u:${run.id}"
    override fun adopt(o: CiObj) { run = (o as RunNode).run }
    override fun toString() = run.label()
}
internal class JobNode(val repo: String, var run: CiRun, var job: CiJob, var detail: CiRunDetail) : CiObj {
    override val key get() = "j:${job.id}"
    override fun adopt(o: CiObj) { o as JobNode; job = o.job; run = o.run; detail = o.detail }
    override fun toString() = job.label()
}
internal class StepNode(val repo: String, var run: CiRun, var job: CiJob, var step: CiStep, var detail: CiRunDetail) : CiObj {
    override val key get() = "s:${step.number}"
    override fun adopt(o: CiObj) { o as StepNode; step = o.step; job = o.job; run = o.run; detail = o.detail }
    override fun toString() = step.label()
}
internal class NoteNode(val text: String, val error: Boolean) : CiObj {
    override val key get() = "n:$text"
    override fun adopt(o: CiObj) {}
    override fun toString() = text
}

/**
 * The "CI" tab: repository, workflow, run, job, step (`ci.runs`, `ci.jobs`). Rows carry their status (icon and colour) and duration; a run row says what started it.
 * Expanding a run loads its jobs (a finished run from the kept copy at once) and a red run opens down to its first failed step. Enter or a double-click on a step or
 * a job opens its log, on a run its default action (red: the diff against the last green run, running: follow, else its log); the diff of a red job or step is an
 * action. A refresh and the 5 s poll of what is visible and running RECONCILE the nodes by key in place, so nothing folds and the selection stays. After a refresh
 * `ci.archive` keeps the newest finished runs in the background (the limit is a setting) and the status line says so. It renders what the daemon answered; the logs
 * and diffs are text files it opens.
 */
class CiPanel(private val project: Project) : JPanel(BorderLayout()), UiDataProvider, Disposable {
    private val root = DefaultMutableTreeNode("ci")
    private val model = DefaultTreeModel(root)
    val tree = Tree(model)
    private val status = JBLabel(" ")
    private val loaded = HashMap<String, CiRuns>()
    private var archiveNote = ""
    private var baseNote = ""
    private val timer = Timer(POLL_MS) { tick(false) }
    private val runsTimer = Timer(RUNS_POLL_MS) { pollRuns(false) }
    private var revealRun: Pair<String, String>? = null

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(t: javax.swing.JTree, value: Any?, sel: Boolean, exp: Boolean, leaf: Boolean, row: Int, focus: Boolean) {
                val now = java.time.Instant.now()
                fun stateLook(s: CiState) = when (s) {
                    CiState.RED -> SimpleTextAttributes.ERROR_ATTRIBUTES
                    CiState.RUNNING -> SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES
                    CiState.QUEUED, CiState.SKIPPED -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                    CiState.OK -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                }
                when (val u = (value as? DefaultMutableTreeNode)?.userObject) {
                    is RepoNode -> { append(u.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES); if (u.typed) append("   added here", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                    is WorkflowNode -> { append(u.toString(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES); if (u.wf != null && u.count == 0) append("   no runs yet", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                    is RunNode -> {
                        val st = Ci.state(u.run.status, u.run.conclusion)
                        icon = iconOf(st)
                        append("${u.run.tag}  ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        append(u.run.result, stateLook(st))
                        append("  " + listOfNotNull(u.run.event, u.run.branch, u.run.sha?.take(7)).joinToString(" "), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        u.run.title?.let { append("  $it") }
                        u.run.durationS(now)?.let { append("  ${Ci.fmt(it)}", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                        // what a deploy did with this commit (piece 04's notes), grey
                        if (u.run.notes.isNotEmpty()) append("  " + u.run.notes.joinToString("; "), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    }
                    is JobNode -> {
                        icon = iconOf(u.job.state)
                        append(u.job.name, if (u.job.state == CiState.RED) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        append("  " + u.job.result, stateLook(u.job.state))
                        u.job.duration(now)?.let { append("  ${Ci.fmt(it)}", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                    }
                    is StepNode -> {
                        val s = u.step
                        icon = iconOf(s.state)
                        // a step cut by time is a guess: it is grey and says so
                        val look = if (s.exact == false) SimpleTextAttributes.GRAYED_ATTRIBUTES else stateLook(s.state)
                        append("${s.number}  ${s.name}", look)
                        append("  " + s.result, look)
                        s.duration(now)?.let { append("  ${Ci.fmt(it)}", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                        if (s.exact == false) append("  (cut by time)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    }
                    is NoteNode -> append(u.text, if (u.error) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    else -> append(u.toString())
                }
            }
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) selection()?.let { CiController.open(project, it) } }
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) { if (e.keyCode == KeyEvent.VK_ENTER) { selection()?.let { CiController.open(project, it) }; e.consume() } }
        })
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(e: TreeExpansionEvent) {
                when (val n = (e.path.lastPathComponent as? DefaultMutableTreeNode)?.userObject) {
                    is RepoNode -> if (n.runs == null && !n.loading) load(n.name)
                    is RunNode -> if (n.detail == null && !n.loading) loadJobs(e.path.lastPathComponent as DefaultMutableTreeNode)
                }
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
        timer.start()
        runsTimer.start()
        INSTANCES[project] = this
    }

    private fun iconOf(s: CiState) = when (s) {
        CiState.OK -> AllIcons.RunConfigurations.TestPassed
        CiState.RED -> AllIcons.RunConfigurations.TestFailed
        CiState.RUNNING -> AllIcons.Process.Step_1
        CiState.QUEUED -> AllIcons.RunConfigurations.TestNotRan
        CiState.SKIPPED -> AllIcons.RunConfigurations.TestIgnored
    }

    // reconciling ----------------------------------------------------------------------------------------------

    private fun children(p: DefaultMutableTreeNode) = (0 until p.childCount).map { p.getChildAt(it) as DefaultMutableTreeNode }

    /**
     * Makes the children of [parent] the [wanted] objects, by key, IN PLACE: a node that is still wanted keeps its identity (so its expansion and the selection stay) and
     * adopts the new data, a new one is inserted (then [created] sees it), one that is gone is removed. EDT.
     */
    private fun reconcile(parent: DefaultMutableTreeNode, wanted: List<CiObj>, created: (DefaultMutableTreeNode) -> Unit = {}) {
        val keys = wanted.map { it.key }.toSet()
        // new ones first and stale ones last: a parent that loses its only child would fold (Swing drops its expanded state)
        for (w in wanted) {
            val cur = children(parent).firstOrNull { (it.userObject as? CiObj)?.key == w.key }
            if (cur == null) { val n = DefaultMutableTreeNode(w); model.insertNodeInto(n, parent, parent.childCount); created(n) }
            else (cur.userObject as CiObj).adopt(w)
        }
        for (n in children(parent)) if ((n.userObject as? CiObj)?.key !in keys) model.removeNodeFromParent(n)
        wanted.forEachIndexed { i, w ->
            val cur = children(parent).first { (it.userObject as? CiObj)?.key == w.key }
            if (parent.getIndex(cur) != i) { model.removeNodeFromParent(cur); model.insertNodeInto(cur, parent, i) } else model.nodeChanged(cur)
        }
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
        val names = repoNames()
        reconcile(root, names.map { (name, typed) -> RepoNode(name, typed).also { it.runs = loaded[name] } }) { n ->
            val rn = n.userObject as RepoNode
            val r = loaded[rn.name]
            if (r != null) fillRepo(n, r) else model.insertNodeInto(DefaultMutableTreeNode(NoteNode("expand to load the runs", false)), n, 0)
        }
        status.text = if (names.isEmpty()) "No repositories: Add Repository... (owner/name of a GitHub repository), or Clone Repository... in the Work tab" else statusLine()
    }

    private fun statusLine() = (baseNote + (if (archiveNote.isNotEmpty()) "  ·  $archiveNote" else "")).ifEmpty { " " }

    // runs -----------------------------------------------------------------------------------------------------

    /**
     * Loads the runs of [repo] off the EDT and shows them (the repository's node is found by name). The first time the LAST KNOWN listing is shown at once with its
     * age (01a: the tab is never empty while GitHub is asked), then the answer replaces it. [user] false is the poll: no archive afterwards, one overwritten text view.
     */
    fun load(repo: String, user: Boolean = true) {
        val rn = repoNode(repo)?.userObject as? RepoNode
        if (rn != null) { if (rn.loading) return; rn.loading = true }
        if (user) { baseNote = "ci.runs $repo ..."; status.text = statusLine() }
        ApplicationManager.getApplication().executeOnPooledThread {
            val ops = CiOps.getInstance(project)
            val wfs = try { ops.workflows(repo) } catch (e: Exception) { emptyList() }
            if (rn != null && !rn.askedCached) {
                rn.askedCached = true
                val kept = try { ops.runs(repo, cached = true) } catch (e: Exception) { null }
                if (kept != null && kept.rows.isNotEmpty())
                    ApplicationManager.getApplication().invokeLater({ show(repo, kept, null, user, wfs, final = false) }, ModalityState.any())
            }
            var runs: CiRuns? = null
            var err: String? = null
            try { runs = ops.runs(repo, poll = !user) } catch (e: DaemonException) { err = e.message + (e.hint?.let { " ($it)" } ?: "") }
            ApplicationManager.getApplication().invokeLater({ show(repo, runs, err, user, wfs) }, ModalityState.any())
        }
    }

    /** Renders an answer (or an error) for [repo], reconciling the nodes; the tree keeps what is expanded and selected. [final] false: a kept listing shown while the answer comes. */
    internal fun show(repo: String, runs: CiRuns?, error: String?, user: Boolean = true, workflows: List<CiWorkflow>? = null, final: Boolean = true) {
        val n = repoNode(repo) ?: return
        val rn = n.userObject as RepoNode
        if (final) rn.loading = false
        rn.error = error
        if (workflows != null) rn.workflows = workflows
        if (runs == null && rn.runs != null) {
            // a refresh that failed (no network, no token): the rows already shown stay, the reason goes to the status line
            baseNote = "$repo: refresh failed: $error  ·  the rows are from the last answer"
            status.text = statusLine()
            return
        }
        rn.runs = runs
        if (runs != null) loaded[repo] = runs else loaded.remove(repo)
        if (runs != null) fillRepo(n, runs) else if (rn.workflows.isNotEmpty()) fillRepo(n, CiRuns(repo, null, 0, emptyList())) else reconcile(n, listOf(NoteNode(error ?: "failed", true)))
        val first = !rn.shown
        rn.shown = true
        if (first) tree.expandPath(TreePath(n.path))
        baseNote = if (runs != null) runsNote(repo, runs) else "$repo: $error"
        status.text = statusLine()
        revealRun?.let { (r, id) -> if (r == repo) findRun(repo, id)?.let { node -> revealRun = null; tree.expandPath(TreePath(node.path)); tree.scrollPathToVisible(TreePath(node.path)) } }
        if (runs != null && user && final) archive(repo)
    }

    /** The status line of a listing: how many, where they came from (a kept listing says how old it is), what went wrong, the rate limit when it is low. */
    private fun runsNote(repo: String, runs: CiRuns): String {
        val base = "$repo: ${runs.rows.size} of ${runs.total} runs"
        val src = when {
            runs.stale -> "  ·  GitHub did not answer (${runs.error}): the last known runs, ${runs.ageS?.let { Ci.age(it) } ?: "age unknown"}"
            runs.source == "kept" -> "  ·  the last known runs, ${runs.ageS?.let { Ci.age(it) } ?: "age unknown"}"
            runs.source == "local" -> "  ·  local runs only"
            else -> ""
        }
        val rate = runs.rateRemaining?.takeIf { it < RATE_LOW }?.let { "  ·  GitHub rate limit: $it requests left" } ?: ""
        return base + src + rate + (runs.path?.let { "  ·  text: Open Runs Text" } ?: "")
    }

    private fun fillRepo(n: DefaultMutableTreeNode, runs: CiRuns) {
        val rnode = n.userObject as RepoNode
        val repo = rnode.name
        val byWf = runs.byWorkflow()
        // the workflows of the checkout come first (also those with no run yet), then the ones only the runs know
        val names = (rnode.workflows.map { it.name } + byWf.keys).distinct()
        if (names.isEmpty()) { reconcile(n, listOf(NoteNode("no runs", false))); return }
        reconcile(n, names.map { nm -> WorkflowNode(repo, nm, byWf[nm]?.size ?: 0, rnode.workflows.firstOrNull { it.name == nm }) }) { w -> tree.expandPath(TreePath(w.path)) }
        for (nm in names) {
            val rows = byWf[nm] ?: emptyList()
            val w = children(n).first { (it.userObject as? WorkflowNode)?.name == nm }
            reconcile(w, rows.map { RunNode(repo, it) }) { r -> model.insertNodeInto(DefaultMutableTreeNode(NoteNode("loading jobs...", false)), r, 0) }
            // a run whose state moved reloads its jobs (a loaded one only: the others load when expanded)
            for (rn in children(w)) {
                val ro = rn.userObject as RunNode
                val d = ro.detail
                if (d != null && !ro.loading && (d.run.status != ro.run.status || d.run.conclusion != ro.run.conclusion || d.run.attempt != ro.run.attempt)) loadJobs(rn)
            }
        }
    }

    /** Opens (and scrolls to) the row of run [runId] as soon as a listing has it: the run just started from this tab. */
    fun reveal(repo: String, runId: String) { revealRun = repo to runId; load(repo, false) }

    /** 01a: the poll of the runs: every expanded repository asks for its newest runs again (one request each), whatever its rows say. */
    internal fun pollRuns(force: Boolean) {
        if (!force && !isShowing) return
        for (repoNode in children(root)) {
            val rn = repoNode.userObject as RepoNode
            if (rn.runs != null && tree.isExpanded(TreePath(repoNode.path))) load(rn.name, false)
        }
    }

    // jobs and steps -------------------------------------------------------------------------------------------

    /** Loads the jobs of the run at [node] off the EDT and shows them in place. */
    internal fun loadJobs(node: DefaultMutableTreeNode) {
        val ro = node.userObject as? RunNode ?: return
        if (ro.loading) return
        ro.loading = true
        ro.lastPoll = System.currentTimeMillis()
        val repo = ro.repo
        val id = ro.run.id
        val attempt = ro.run.attempt
        ApplicationManager.getApplication().executeOnPooledThread {
            var d: CiRunDetail? = null
            var err: String? = null
            try { d = CiOps.getInstance(project).jobs(repo, id, attempt) } catch (e: DaemonException) { err = e.message + (e.hint?.let { " ($it)" } ?: "") }
            ApplicationManager.getApplication().invokeLater({ showJobs(repo, id, d, err) }, ModalityState.any())
        }
    }

    internal fun showJobs(repo: String, runId: String, d: CiRunDetail?, error: String?) {
        val node = findRun(repo, runId) ?: return
        val ro = node.userObject as RunNode
        ro.loading = false
        if (d == null) { reconcile(node, listOf(NoteNode(error ?: "failed", true))); return }
        val wasRunning = ro.run.running
        ro.detail = d
        ro.run = d.run.copy(workflow = ro.run.workflow)
        model.nodeChanged(node)
        val expanded = tree.isExpanded(TreePath(node.path))
        val firstOpen = expanded && !ro.opened
        if (expanded) ro.opened = true
        if (wasRunning && !ro.run.running) archive(repo)
        val jobs = d.jobs
        if (jobs.isEmpty()) { reconcile(node, listOf(NoteNode("no jobs", false))); return }
        val firstRedJob = jobs.firstOrNull { it.state == CiState.RED }?.id
        reconcile(node, jobs.map { JobNode(repo, ro.run, it, d) })
        // a job that runs (or the first red one) is opened once, when the run is open: the running step is in view; a run row the user has not opened is not unfolded
        if (expanded) for (j in children(node)) {
            val jo = j.userObject as JobNode
            if ((jo.job.state == CiState.RUNNING || jo.job.id == firstRedJob) && ro.autoOpened.add(jo.job.id)) tree.expandPath(TreePath(j.path))
        }
        var firstRed: DefaultMutableTreeNode? = null
        for (j in children(node)) {
            val jo = j.userObject as JobNode
            reconcile(j, jo.job.steps.map { StepNode(repo, ro.run, jo.job, it, d) })
            if (firstRed == null && jo.job.state == CiState.RED) firstRed = children(j).firstOrNull { (it.userObject as StepNode).step.state == CiState.RED }
        }
        // a red run opens down to its first failed step, once: the user's own expansion is never undone
        if (firstOpen) firstRed?.let { s -> tree.expandPath(TreePath((s.parent as DefaultMutableTreeNode).path)); tree.scrollPathToVisible(TreePath(s.path)) }
    }

    // archive ----------------------------------------------------------------------------------------------------

    /** `ci.archive` of [repo] in the background (the newest `ciArchiveLimit` finished runs), reported in the status line. */
    internal fun archive(repo: String) {
        val limit = DeliberatorSettings.getInstance().state.ciArchiveLimit
        val rn = repoNode(repo)?.userObject as? RepoNode ?: return
        if (limit <= 0 || rn.archiving) return
        rn.archiving = true
        archiveNote = "keeping the newest $limit finished runs of $repo ..."
        status.text = statusLine()
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = try { CiOps.getInstance(project).archive(repo, limit).text() } catch (e: DaemonException) { "$repo: not kept (${e.message})" }
            ApplicationManager.getApplication().invokeLater({ rn.archiving = false; archiveNote = text; status.text = statusLine() }, ModalityState.any())
        }
    }

    // the poll ---------------------------------------------------------------------------------------------------

    /**
     * Every 5 s while the tab is showing: every run in progress of an expanded repository reloads its jobs (`ci.jobs` also answers the run's own row, so the run's
     * status moves with it: queued, running, done) and the nodes are reconciled in place. A new run shows up on Refresh. [force] runs it though the tab is not showing
     * (tests).
     */
    internal fun tick(force: Boolean) {
        if (!force && !isShowing) return
        for (repoNode in children(root)) {
            if ((repoNode.userObject as RepoNode).runs == null || !tree.isExpanded(TreePath(repoNode.path))) continue
            for (wf in children(repoNode)) for (runNode in children(wf)) {
                val ro = runNode.userObject as? RunNode ?: continue
                val every = if (ro.run.local) LOCAL_POLL_MS else GITHUB_RUN_POLL_MS
                if (ro.run.running && (force || System.currentTimeMillis() - ro.lastPoll >= every)) loadJobs(runNode)
            }
        }
    }

    // lookups ------------------------------------------------------------------------------------------------------

    private fun repoNode(repo: String): DefaultMutableTreeNode? = children(root).firstOrNull { (it.userObject as RepoNode).name == repo }

    private fun findRun(repo: String, runId: String): DefaultMutableTreeNode? {
        val r = repoNode(repo) ?: return null
        for (wf in children(r)) for (n in children(wf)) if ((n.userObject as? RunNode)?.run?.id == runId) return n
        return null
    }

    /** The tree as text, one line per node, indented (tests, and what the UI-tree dump shows). */
    fun lines(): List<String> = buildList {
        fun walk(n: DefaultMutableTreeNode, depth: Int) {
            for (i in 0 until n.childCount) { val c = n.getChildAt(i) as DefaultMutableTreeNode; add("  ".repeat(depth) + c.userObject); walk(c, depth + 1) }
        }
        walk(root, 0)
    }

    /** The paths of the expanded rows as text (tests: a refresh must not change them). */
    fun expandedKeys(): List<String> = (0 until tree.rowCount).mapNotNull { tree.getPathForRow(it) }.filter { tree.isExpanded(it) }
        .map { p -> p.path.drop(1).joinToString("/") { ((it as DefaultMutableTreeNode).userObject as? CiObj)?.key ?: "?" } }

    fun statusText(): String = status.text

    /** Anything loading (runs, jobs, an archive): the panel is not at rest. */
    fun busy(): Boolean = children(root).any { r ->
        val rn = r.userObject as RepoNode
        rn.loading || rn.archiving || children(r).any { w -> children(w).any { (it.userObject as? RunNode)?.loading == true } }
    }

    /** Expands the row of run [runId] of [repo] (tests, and "Reveal"): its jobs load as for a click. */
    fun expandRun(repo: String, runId: String) { findRun(repo, runId)?.let { tree.expandPath(TreePath(it.path)) } }

    /** Whether the node under run [runId] named by the keys of [rest] (`j:2001`, `s:2`) is visible (all its ancestors expanded). */
    fun isVisible(repo: String, runId: String, vararg rest: String): Boolean = pathTo(repo, runId, *rest)?.let { tree.isVisible(it) } ?: false

    fun select(repo: String, runId: String, vararg rest: String) { pathTo(repo, runId, *rest)?.let { tree.selectionPath = it } }

    private fun pathTo(repo: String, runId: String, vararg rest: String): TreePath? {
        var n = findRun(repo, runId) ?: return null
        for (k in rest) n = children(n).firstOrNull { (it.userObject as? CiObj)?.key == k }
            ?: children(n).flatMap { children(it) }.firstOrNull { (it.userObject as? CiObj)?.key == k } ?: return null
        return TreePath(n.path)
    }

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

    fun selection(): CiSelection? = when (val u = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject) {
        is RunNode -> CiSelection(u.repo, u.run, detail = u.detail)
        is JobNode -> CiSelection(u.repo, u.run, u.job, null, u.detail)
        is StepNode -> CiSelection(u.repo, u.run, u.job, u.step, u.detail)
        else -> null
    }

    fun workflowSelection(): CiWorkflowSelection? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject.let { u ->
        (u as? WorkflowNode)?.let { CiWorkflowSelection(it.repo, it.name, it.wf) }
    }

    override fun uiDataSnapshot(sink: DataSink) {
        sink[SELECTION] = selection()
        sink[WORKFLOW] = workflowSelection()
        sink[PANEL] = this
    }

    override fun dispose() { timer.stop(); runsTimer.stop(); INSTANCES.remove(project, this) }

    companion object {
        const val KEY = "deliberator.ci.repos"
        const val POLL_MS = 2000
        /** A running GitHub run is asked again at most this often (the API's rate limit), a local one every [LOCAL_POLL_MS]. */
        const val GITHUB_RUN_POLL_MS = 5000L
        const val LOCAL_POLL_MS = 1500L
        /** 01a: the newest runs of every visible repository are asked again this often (one request per repository). */
        const val RUNS_POLL_MS = 30000
        const val RATE_LOW = 200
        private val INSTANCES = java.util.concurrent.ConcurrentHashMap<Project, CiPanel>()
        fun of(project: Project): CiPanel? = INSTANCES[project]
        val SELECTION: DataKey<CiSelection> = DataKey.create("deliberator.ci.selection")
        val PANEL: DataKey<CiPanel> = DataKey.create("deliberator.ci.panel")
        val WORKFLOW: DataKey<CiWorkflowSelection> = DataKey.create("deliberator.ci.workflow")
    }
}
