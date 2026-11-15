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
    val table = object : JBTable(tableModel) {
        /** The daemon's hint of the ECS state (what the row means, how to get back) as the row's tooltip. */
        override fun getToolTipText(e: java.awt.event.MouseEvent): String? =
            rowAtPoint(e.point).takeIf { it >= 0 }?.let { rows.getOrNull(it) }?.let { r -> listOfNotNull(r.ecsHint, r.debugRefused).joinToString("\n").ifEmpty { null } }
    }.apply { setShowGrid(false); setSelectionMode(ListSelectionModel.SINGLE_SELECTION) }
    internal val events = ServiceEvents(project, this)
    private val hint = JBLabel()
    private var rows: List<ServiceRow> = emptyList()
    /** The fakes below the services (M6.6): `fake.list` and the recordings of the selected fake. */
    lateinit var fakes: FakesPanel; private set

    init {
        val group = ActionManager.getInstance().getAction("deliberator.services") as DefaultActionGroup
        val bar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_TOOLBAR_BAR, group, true)
        bar.targetComponent = this
        add(JPanel(BorderLayout()).apply { add(bar.component, BorderLayout.NORTH); add(hint, BorderLayout.SOUTH) }, BorderLayout.NORTH)
        fakes = FakesPanel(project)
        add(JPanel(java.awt.GridLayout(2, 1)).apply { add(JBScrollPane(table)); add(fakes) }, BorderLayout.CENTER)
        PopupHandler.installPopupMenu(table, group, ActionPlaces.POPUP)
        PopupHandler.installPopupMenu(fakes.table, group, ActionPlaces.POPUP)
        PopupHandler.installPopupMenu(fakes.recordings, group, ActionPlaces.POPUP)
        svc.addListener(this, ::render)
        render(svc.model)
        // the Workspace tab's own tick runs only while that tab shows: this tab keeps itself current (every 15 s while showing), demo-m3 §9 item 1
        var ticks = 0
        val timer = javax.swing.Timer(5000) { if (isShowing && ++ticks % 3 == 0) svc.refresh() }
        timer.start()
        events.start()   // the watch's service.started / service.stopped refresh the rows at once (piece 02c)
        com.intellij.openapi.util.Disposer.register(this) { timer.stop() }
    }

    /** Selects the row of service [name] (the explorer's link to its container). EDT. */
    fun select(name: String) { rows.indexOfFirst { it.name == name }.takeIf { it >= 0 }?.let { table.setRowSelectionInterval(it, it) } }

    /** The selected row (the data of the actions). */
    fun selected(): ServiceRow? = table.selectedRow.takeIf { it >= 0 && it < rows.size }?.let { rows[it] }

    override fun uiDataSnapshot(sink: com.intellij.openapi.actionSystem.DataSink) {
        sink[SERVICE] = selected()
        if (this::fakes.isInitialized) {
            sink[FAKE] = fakes.selected()
            sink[RECORDING] = fakes.recordings.selectedValue?.substringBefore("  ")
        }
    }

    /** Cell text of the table (tests). */
    internal fun cellText(row: Int, col: Int): String = tableModel.getValueAt(row, col).toString()

    fun render(m: WorkspaceModel) {
        val keep = selected()?.name
        rows = m.services
        tableModel.rowCount = 0
        rows.forEach { tableModel.addRow(cells(it)) }
        rows.indexOfFirst { it.name == keep }.takeIf { it >= 0 }?.let { table.setRowSelectionInterval(it, it) }
        hint.text = if (m.ws == null) "Not a Deliberator workspace project"
        else if (rows.isEmpty()) "No services in ${m.ws}: adopt one (Adopt Service...)" else "environment ${m.selectedEnv}"
    }

    override fun dispose() { events.stop(); if (this::fakes.isInitialized) Disposer.dispose(fakes) }

    companion object {
        val SERVICE: DataKey<ServiceRow> = DataKey.create("deliberator.service.row")
        val FAKE: DataKey<FakeRow> = DataKey.create("deliberator.service.fake")
        val RECORDING: DataKey<String> = DataKey.create("deliberator.service.recording")
        val COLUMNS = arrayOf("Name", "Mode", "Status", "Port", "JDWP", "Reloads", "ECS")

        /** One table row: host ports shown as `host:container` where the container publishes them. */
        fun cells(r: ServiceRow): Array<Any> = arrayOf(
            r.name,
            when (r.mode) {
                "external" -> "external" + (r.url?.let { " ($it)" } ?: "")
                "fake" -> "fake" + (r.fake?.let { " ($it)" } ?: "")
                else -> r.mode
            },
            r.status + (if (r.drift) " (drift)" else ""),
            port(r.hostPort, r.port),
            port(r.hostJdwp, r.jdwp),
            r.reloadCount?.toString() ?: "",
            r.ecsText ?: "",
        )

        private fun port(host: String?, container: Int?) = when {
            container == null -> ""
            host != null && host != container.toString() -> "$host:$container"
            else -> container.toString()
        }
    }
}
