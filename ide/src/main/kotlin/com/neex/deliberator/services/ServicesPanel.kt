package com.neex.deliberator.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.neex.deliberator.workspace.ServiceRow
import com.neex.deliberator.workspace.WorkspaceModel
import com.neex.deliberator.workspace.WorkspaceService
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

/** The "Services" tab: one row per service of the workspace model, renders the model only; every button is an action of `deliberator.services`. */
class ServicesPanel(private val project: Project) : JPanel(BorderLayout()), UiDataProvider, Disposable {
    private val svc = WorkspaceService.getInstance(project)
    private val tableModel = object : DefaultTableModel(COLUMNS, 0) { override fun isCellEditable(row: Int, column: Int) = false }
    val table = JBTable(tableModel).apply { setShowGrid(false); setSelectionMode(ListSelectionModel.SINGLE_SELECTION) }
    private val hint = JBLabel()
    private var rows: List<ServiceRow> = emptyList()

    init {
        val group = ActionManager.getInstance().getAction("deliberator.services") as DefaultActionGroup
        val bar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_TOOLBAR_BAR, group, true)
        bar.targetComponent = this
        add(JPanel(BorderLayout()).apply { add(bar.component, BorderLayout.NORTH); add(hint, BorderLayout.SOUTH) }, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        PopupHandler.installPopupMenu(table, group, ActionPlaces.POPUP)
        svc.addListener(this, ::render)
        render(svc.model)
        // the Workspace tab's own tick runs only while that tab shows: this tab keeps itself current (every 15 s while showing), demo-m3 §9 item 1
        var ticks = 0
        val timer = javax.swing.Timer(5000) { if (isShowing && ++ticks % 3 == 0) svc.refresh() }
        timer.start()
        com.intellij.openapi.util.Disposer.register(this) { timer.stop() }
    }

    /** The selected row (the data of the actions). */
    fun selected(): ServiceRow? = table.selectedRow.takeIf { it >= 0 && it < rows.size }?.let { rows[it] }

    override fun uiDataSnapshot(sink: com.intellij.openapi.actionSystem.DataSink) {
        sink[SERVICE] = selected()
    }

    fun render(m: WorkspaceModel) {
        val keep = selected()?.name
        rows = m.services
        tableModel.rowCount = 0
        rows.forEach { tableModel.addRow(cells(it)) }
        rows.indexOfFirst { it.name == keep }.takeIf { it >= 0 }?.let { table.setRowSelectionInterval(it, it) }
        hint.text = if (m.ws == null) "Not a Deliberator workspace project"
        else if (rows.isEmpty()) "No services in ${m.ws}: adopt one (Adopt Service...)" else "environment ${m.selectedEnv}"
    }

    override fun dispose() {}

    companion object {
        val SERVICE: DataKey<ServiceRow> = DataKey.create("deliberator.service.row")
        val COLUMNS = arrayOf("Name", "Mode", "Status", "Port", "JDWP", "Reloads")

        /** One table row: host ports shown as `host:container` where the container publishes them. */
        fun cells(r: ServiceRow): Array<Any> = arrayOf(
            r.name,
            when (r.mode) { "external" -> "external" + (r.url?.let { " ($it)" } ?: ""); else -> r.mode },
            r.status + (if (r.drift) " (drift)" else ""),
            port(r.hostPort, r.port),
            port(r.hostJdwp, r.jdwp),
            r.reloadCount?.toString() ?: "",
        )

        private fun port(host: String?, container: Int?) = when {
            container == null -> ""
            host != null && host != container.toString() -> "$host:$container"
            else -> container.toString()
        }
    }
}
