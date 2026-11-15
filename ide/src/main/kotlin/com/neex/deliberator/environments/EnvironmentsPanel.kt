package com.neex.deliberator.environments

import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.ui.PopupHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.ui.Ui
import com.neex.deliberator.workspace.WorkspaceService
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

/**
 * The "Environments" tab (M4.1, M4.2): the environments of the workspace (`env.list`: kind, account, region, whether a mutation is refused
 * or asks), New environment... and Remove, and under them what is deployed in the chosen one (`deployed.show`: image tag, task definition
 * revision or function version per service, from the cache with its age; Refresh = `deployed.refresh`, read calls only). The pattern is the
 * parameter view's: cached first, refresh on demand. Every daemon call is off the EDT; the tab writes nothing itself.
 */
class EnvironmentsPanel(private val project: Project) : JPanel(BorderLayout()), UiDataProvider, Disposable {
    private val envModel = object : DefaultTableModel(arrayOf("Name", "Kind", "Account", "Region", "Mode", "Profile / endpoint"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val envTable = JBTable(envModel).apply { setSelectionMode(ListSelectionModel.SINGLE_SELECTION); setShowGrid(false) }
    private val depModel = object : DefaultTableModel(arrayOf("Kind", "Cluster", "Service / function", "Revision / version", "Image tag", "Running", "Updated"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val depTable = JBTable(depModel).apply { setShowGrid(false) }
    /** The newest deployments of the service row chosen above (`deploy.list`): one table, reconciled by deployment id, never rebuilt while nothing changed. */
    private val deploysModel = object : DefaultTableModel(arrayOf("Status", "Rollout", "Started", "Revision", "Image", "Commit", "Run", "Id"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val deploysTable = object : JBTable(deploysModel) {
        override fun getToolTipText(e: java.awt.event.MouseEvent): String? =
            rowAtPoint(e.point).takeIf { it >= 0 }?.let { deployments?.rows?.getOrNull(it) }?.let { r ->
                listOfNotNull(r.text(), r.reason?.let { "reason: $it" }, r.source?.let { "from $it" }, r.finished?.let { "finished $it" }).joinToString("\n")
            }
    }.apply { setShowGrid(false); setSelectionMode(ListSelectionModel.SINGLE_SELECTION) }
    private val deploysStatus = JBLabel(" ")
    private val deploysSeq = AtomicInteger()
    @Volatile private var deployments: DeploymentList? = null
    private var filling = false
    private val status = JBLabel(" ")
    private val refreshButton = JButton("Refresh").apply { toolTipText = "deployed.refresh of the chosen environment (read calls only), then the cached view again" }
    private val removeButton = JButton("Remove...")
    private val seq = AtomicInteger()

    @Volatile private var envs: List<EnvInfo> = emptyList()
    @Volatile private var view: DeployedView? = null

    /** The environment to select once the next listing arrives (a just created one). */
    @Volatile private var pick: String? = null

    /** The last failure of a button (the text of its balloon), for tests. */
    @Volatile internal var lastError: String? = null; private set

    /** Asked before an environment is removed (the text says what stays); true removes. A dialog, replaced by tests. EDT. */
    @Volatile internal var removeConfirmer: (String) -> Boolean = { text ->
        Messages.showYesNoDialog(project, text, "Remove Environment", "Remove", "Cancel", Messages.getQuestionIcon()) == Messages.YES
    }

    init {
        val newButton = JButton("New environment...").apply { toolTipText = "env.create: a remote environment from a profile, an account and a region"; addActionListener { newEnvironment() } }
        removeButton.toolTipText = "env.remove: delete the record (not `local`); what was fetched through it stays"
        removeButton.addActionListener { removeSelected() }
        val useButton = JButton("Use").apply { toolTipText = "Make the chosen environment the project's (config views, AWS tab, scenarios)"; addActionListener { useSelected() } }
        refreshButton.addActionListener { refreshDeployed() }
        val textButton = JButton("Open text").apply { toolTipText = "The deployed view as text (tab separated)"; addActionListener { openText() } }
        val recordButton = JButton("Edit record").apply {
            toolTipText = "The environment's record (targets, read-only, profile) as text in the editor; the next run reads it"
            addActionListener { editRecord() }
        }
        val auditButton = JButton("Audit log...").apply { toolTipText = "The audit trail of a session as text, the confirmations highlighted"; addActionListener { AuditLogs.choose(project) } }
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(newButton); add(removeButton); add(useButton); add(refreshButton); add(textButton); add(recordButton); add(auditButton) }, BorderLayout.NORTH)
        val deploysBar = ActionManager.getInstance().getAction("deliberator.deploy")?.let { g ->
            ActionManager.getInstance().createActionToolbar("DeliberatorDeployments", g as DefaultActionGroup, true).also { it.targetComponent = this }
        }
        val deploysPane = JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply { add(deploysStatus, BorderLayout.NORTH); deploysBar?.let { add(it.component, BorderLayout.SOUTH) } }, BorderLayout.NORTH)
            add(JBScrollPane(deploysTable), BorderLayout.CENTER)
        }
        deploysBar?.let { PopupHandler.installPopupMenu(deploysTable, ActionManager.getInstance().getAction("deliberator.deploy") as DefaultActionGroup, ActionPlaces.POPUP) }
        deploysTable.inputMap.put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "deliberator.deploy.open")
        deploysTable.actionMap.put("deliberator.deploy.open", object : javax.swing.AbstractAction() { override fun actionPerformed(e: java.awt.event.ActionEvent?) { openDeploymentText() } })
        deploysTable.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) { if (e.clickCount == 2) openDeploymentText() }
        })
        depTable.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting && !filling) loadDeployments(false) }
        val lower = JPanel(BorderLayout()).apply {
            add(status, BorderLayout.NORTH)
            add(OnePixelSplitter(true, 0.5f).apply { firstComponent = JBScrollPane(depTable); secondComponent = deploysPane }, BorderLayout.CENTER)
        }
        EnvironmentsPanelHolder.register(project, this)
        add(OnePixelSplitter(true, 0.2f).apply { firstComponent = JBScrollPane(envTable); secondComponent = lower }, BorderLayout.CENTER)
        envTable.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) selected()?.let(::showDeployed) }
        envTable.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) { if (e.clickCount == 2) useSelected() }
        })
        WorkspaceService.getInstance(project).addListener(this) { m -> if (m.envs.map { it.name } != envs.map { it.name }) reload() }
        reload()
    }

    /** The environment the table has selected. */
    internal fun selected(): EnvInfo? = envTable.selectedRow.takeIf { it >= 0 }?.let { envs.getOrNull(it) }

    /** The status line (tests). */
    internal fun statusText(): String = status.text

    /** Names in the environment table, in order (tests). */
    internal fun envNames(): List<String> = (0 until envModel.rowCount).map { envModel.getValueAt(it, 0) as String }

    /** Cell text of the environment table (tests). */
    internal fun envRow(i: Int): List<String> = (0 until envModel.columnCount).map { envModel.getValueAt(i, it).toString() }

    /** The deployed rows as shown (tests). */
    internal fun deployedRows(): List<List<String>> = (0 until depModel.rowCount).map { r -> (0 until depModel.columnCount).map { depModel.getValueAt(r, it).toString() } }

    internal fun selectByName(name: String) {
        val i = envs.indexOfFirst { it.name == name }
        if (i >= 0) envTable.setRowSelectionInterval(i, i)
    }

    private fun setStatus(text: String) = ApplicationManager.getApplication().invokeLater({ status.text = text }, ModalityState.any())

    private fun failed(title: String, e: Exception) {
        com.intellij.openapi.diagnostic.Logger.getInstance(EnvironmentsPanel::class.java).warn("$title failed", e)
        val text = (e.message ?: e.toString()) + ((e as? DaemonException)?.hint?.let { "\n$it" } ?: "")
        lastError = text
        setStatus("$title failed: ${e.message}")
        Ui.notify(project, title, text, NotificationType.ERROR)
    }

    /** Reads `env.list` and fills the table; keeps the selection by name, else selects the project's environment. */
    fun reload() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val svc = WorkspaceService.getInstance(project)
            try {
                val list = EnvironmentOps.list(svc)
                ApplicationManager.getApplication().invokeLater({ fill(list, svc.selectedEnv) }, ModalityState.any())
            } catch (e: DaemonException) { failed("Environments", e) }
        }
    }

    private fun fill(list: List<EnvInfo>, projectEnv: String) {
        val keep = pick ?: selected()?.name ?: projectEnv
        pick = null
        envs = list
        envModel.rowCount = 0
        list.forEach { envModel.addRow(arrayOf(it.name + (if (it.name == projectEnv) "  (used)" else ""), it.kind, it.account ?: "", it.region ?: "", it.mode, it.profile ?: it.endpoint ?: "")) }
        val i = list.indexOfFirst { it.name == keep }.takeIf { it >= 0 } ?: list.indexOfFirst { it.remote }.takeIf { it >= 0 } ?: 0
        if (list.isNotEmpty()) {
            if (envTable.selectedRow == i) showDeployed(list[i]) else envTable.setRowSelectionInterval(i, i)
        } else renderDeployed(null)
    }

    /** The cached deployed view of [e]: at once, no AWS call (principle 2). */
    private fun showDeployed(e: EnvInfo) {
        val mine = seq.incrementAndGet()
        setStatus("${e.name}: reading the cached view...")
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val v = EnvironmentOps.deployed(WorkspaceService.getInstance(project), e.name)
                ApplicationManager.getApplication().invokeLater({ if (seq.get() == mine) renderDeployed(v) }, ModalityState.any())
            } catch (x: DaemonException) { failed("Deployed versions", x) }
        }
    }

    private fun renderDeployed(v: DeployedView?) {
        val keepKey = selectedService()?.let { it.kind + "/" + it.cluster + "/" + it.name }
        view = v
        filling = true
        depModel.rowCount = 0
        v?.rows?.forEach { depModel.addRow(arrayOf(it.kind, it.cluster ?: "", it.name, it.revision, it.image, it.running, it.updated ?: "")) }
        v?.rows?.indexOfFirst { it.kind + "/" + it.cluster + "/" + it.name == keepKey }?.takeIf { it >= 0 }?.let { depTable.setRowSelectionInterval(it, it) }
        filling = false
        if (v == null || selectedService() == null) renderDeployments(null, "Select a service or function above: its newest deployments show here.") else loadDeployments(false)
        status.text = v?.status() ?: "No environment."
        refreshButton.isEnabled = v != null
        removeButton.isEnabled = selected()?.let { it.name != "local" } ?: false
    }

    /** `deployed.refresh` of the selected environment, then its cached view. */
    fun refreshDeployed() {
        val e = selected() ?: return
        val mine = seq.incrementAndGet()
        refreshButton.isEnabled = false
        status.text = "${e.name}: fetching (read calls only)..."
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val v = EnvironmentOps.refreshDeployed(WorkspaceService.getInstance(project), e.name)
                ApplicationManager.getApplication().invokeLater({ if (seq.get() == mine) renderDeployed(v) else refreshButton.isEnabled = true }, ModalityState.any())
            } catch (x: DaemonException) {
                ApplicationManager.getApplication().invokeLater({ refreshButton.isEnabled = true }, ModalityState.any())
                failed("Deployed refresh", x)
            }
        }
    }

    private fun newEnvironment() {
        NewEnvironmentDialog(project) { c -> pick = c.name; reload() }.show()
    }

    private fun useSelected() {
        val e = selected() ?: return
        WorkspaceService.getInstance(project).selectEnv(e.name)
        reload()
    }

    /** Remove after a confirmation naming what stays. */
    fun removeSelected() {
        val e = selected() ?: return
        if (e.name == "local") { setStatus("`local` follows the runtime and cannot be removed."); return }
        val ok = removeConfirmer(
            "Remove environment ${e.name}${e.account?.let { " (account $it)" } ?: ""}?\n\nThe record is deleted. What was fetched through it " +
                "(the config snapshot, the deployed view, raw responses, session runs) stays where it is.")
        if (!ok) return
        Ui.background(project, "Deliberator: remove environment ${e.name}", { EnvironmentOps.remove(WorkspaceService.getInstance(project), e.name) }, { reload() })
    }

    /** Opens the environment's record file (`env.describe` names the path) in the editor: targets are added by writing them down. */
    fun editRecord() {
        val e = selected() ?: return
        var vf: com.intellij.openapi.vfs.VirtualFile? = null
        Ui.background(project, "Deliberator: open the record of ${e.name}", {
            val p = EnvironmentOps.recordPath(WorkspaceService.getInstance(project), e.name)
            if (p != null) vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(java.nio.file.Path.of(p))
        }, { vf?.let { Ui.openEditor(project, it, 0) } })
    }

    private fun openText() {
        val p = view?.text ?: return
        var vf: com.intellij.openapi.vfs.VirtualFile? = null
        // the VFS refresh is a slow operation: off the EDT, then the editor on it
        Ui.background(project, "Deliberator: open the deployed view", { vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(java.nio.file.Path.of(p)) }, {
            vf?.let { Ui.openEditor(project, it, 0) }
        })
    }

    // --- the deployments of the chosen service (04 stage 2) ----------------------------------------------------------------

    /** The service or function row of the deployed table that is selected. */
    internal fun selectedService(): DeployedRow? = depTable.selectedRow.takeIf { it >= 0 }?.let { view?.rows?.getOrNull(it) }

    /** The deployment row that is selected. */
    internal fun selectedDeployment(): DeploymentRow? = deploysTable.selectedRow.takeIf { it >= 0 }?.let { deployments?.rows?.getOrNull(it) }

    /** What the deploy actions act on: the chosen service in the chosen environment and, when one is chosen, its deployment. */
    internal fun target(): DeployTarget? {
        val e = selected() ?: return null
        val s = selectedService() ?: return null
        return DeployTarget(e.name, DeployOps.spec(s), s.kind, selectedDeployment())
    }

    override fun uiDataSnapshot(sink: DataSink) { sink[DeployController.TARGET] = target() }

    /** The deployments as shown (tests). */
    internal fun deploymentRows(): List<List<String>> = (0 until deploysModel.rowCount).map { r -> (0 until deploysModel.columnCount).map { deploysModel.getValueAt(r, it).toString() } }

    internal fun deploymentsStatus(): String = deploysStatus.text

    internal fun selectService(name: String) {
        val i = view?.rows?.indexOfFirst { it.name == name } ?: -1
        if (i >= 0) depTable.setRowSelectionInterval(i, i)
    }

    internal fun selectDeployment(id: String) {
        val i = deployments?.rows?.indexOfFirst { it.id == id } ?: -1
        if (i >= 0) deploysTable.setRowSelectionInterval(i, i)
    }

    /** Fills the deployments table: from the cache at once (principle 2), or with [fetch] from AWS (`deploy.list`, read calls). Off the EDT. */
    private fun loadDeployments(fetch: Boolean) {
        val e = selected() ?: return
        val s = selectedService() ?: run { renderDeployments(null, "Select a service or function above: its newest deployments show here."); return }
        val spec = DeployOps.spec(s)
        val mine = deploysSeq.incrementAndGet()
        deploysStatus.text = if (fetch) "$spec in ${e.name}: fetching the deployments (read calls only)..." else "$spec in ${e.name}: reading the cached deployments..."
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val svc = WorkspaceService.getInstance(project)
                val l = if (fetch) DeployOps.list(svc, e.name, spec) else DeployOps.listCached(svc, e.name, spec)
                ApplicationManager.getApplication().invokeLater({
                    if (deploysSeq.get() == mine) renderDeployments(l, "$spec in ${e.name}: no deployments fetched yet. Refresh Deployments asks AWS (read calls only).")
                }, ModalityState.any())
            } catch (x: DaemonException) {
                ApplicationManager.getApplication().invokeLater({ if (deploysSeq.get() == mine) deploysStatus.text = "$spec: ${x.message}" + (x.hint?.let { " ($it)" } ?: "") }, ModalityState.any())
                failed("Deployments", x)
            }
        }
    }

    /** Refresh Deployments: `deploy.list` of the chosen service (read calls only), then the table follows it. */
    fun refreshDeployments() = ApplicationManager.getApplication().invokeLater({ loadDeployments(true) }, ModalityState.any())

    /** Reconciles the table with [l] by deployment id: the same ids in the same order only change the cells that differ, selection survives either way. EDT. */
    internal fun renderDeployments(l: DeploymentList?, empty: String) {
        val keep = selectedDeployment()?.id   // by id, from the list that is still shown
        deployments = l
        val rows = l?.rows ?: emptyList()
        val same = rows.size == deploysModel.rowCount && rows.indices.all { deploysModel.getValueAt(it, 7) == rows[it].id }
        filling = true
        if (same) {
            rows.forEachIndexed { i, r -> val c = r.cells(); for (j in c.indices) if (deploysModel.getValueAt(i, j) != c[j]) deploysModel.setValueAt(c[j], i, j) }
        } else {
            deploysModel.rowCount = 0
            rows.forEach { deploysModel.addRow(it.cells()) }
            rows.indexOfFirst { it.id == keep }.takeIf { it >= 0 }?.let { deploysTable.setRowSelectionInterval(it, it) }
        }
        filling = false
        deploysStatus.text = l?.status() ?: empty
    }

    private fun openDeploymentText() { target()?.takeIf { it.row != null }?.let { DeployController.openText(project, it) } }

    override fun dispose() { EnvironmentsPanelHolder.forget(project, this) }
}
