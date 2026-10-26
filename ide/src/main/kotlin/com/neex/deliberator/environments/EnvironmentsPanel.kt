package com.neex.deliberator.environments

import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
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
class EnvironmentsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val envModel = object : DefaultTableModel(arrayOf("Name", "Kind", "Account", "Region", "Mode", "Profile / endpoint"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val envTable = JBTable(envModel).apply { setSelectionMode(ListSelectionModel.SINGLE_SELECTION); setShowGrid(false) }
    private val depModel = object : DefaultTableModel(arrayOf("Kind", "Cluster", "Service / function", "Revision / version", "Image tag", "Running", "Updated"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val depTable = JBTable(depModel).apply { setShowGrid(false) }
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
        val auditButton = JButton("Audit log...").apply { toolTipText = "The audit trail of a session as text, the confirmations highlighted"; addActionListener { AuditLogs.choose(project) } }
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(newButton); add(removeButton); add(useButton); add(refreshButton); add(textButton); add(auditButton) }, BorderLayout.NORTH)
        val lower = JPanel(BorderLayout()).apply { add(status, BorderLayout.NORTH); add(JBScrollPane(depTable), BorderLayout.CENTER) }
        add(OnePixelSplitter(true, 0.4f).apply { firstComponent = JBScrollPane(envTable); secondComponent = lower }, BorderLayout.CENTER)
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
        view = v
        depModel.rowCount = 0
        v?.rows?.forEach { depModel.addRow(arrayOf(it.kind, it.cluster ?: "", it.name, it.revision, it.image, it.running, it.updated ?: "")) }
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

    private fun openText() {
        val p = view?.text ?: return
        Ui.openFile(project, p)
    }

    override fun dispose() {}
}
