package com.neex.deliberator.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.neex.deliberator.workspace.WorkspaceService
import java.awt.BorderLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

/**
 * The fakes of the Services tab: one row per declared HTTP fake (`fake.list`) and the recordings of the selected one. A double-click on a recording replays it;
 * Record, Replay and Stop are the actions of `deliberator.services`. It renders [FakeOps.fakes] only.
 */
class FakesPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val ops = FakeOps.getInstance(project)
    private val tableModel = object : DefaultTableModel(COLUMNS, 0) { override fun isCellEditable(row: Int, column: Int) = false }
    val table = JBTable(tableModel).apply { setShowGrid(false); setSelectionMode(ListSelectionModel.SINGLE_SELECTION) }
    private val recModel = DefaultListModel<String>()
    val recordings = JBList(recModel).apply { selectionMode = ListSelectionModel.SINGLE_SELECTION }
    private var rows: List<FakeRow> = emptyList()

    init {
        add(JPanel(GridLayout(1, 2)).apply {
            add(JPanel(BorderLayout()).apply { border = IdeBorderFactory.createTitledBorder("Fakes"); add(JBScrollPane(table), BorderLayout.CENTER) })
            add(JPanel(BorderLayout()).apply { border = IdeBorderFactory.createTitledBorder("Recordings of the selected fake (double-click replays)"); add(JBScrollPane(recordings), BorderLayout.CENTER) })
        }, BorderLayout.CENTER)
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) showRecordings() }
        recordings.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val f = selected() ?: return
                    val id = recordings.selectedValue?.substringBefore("  ") ?: return
                    com.neex.deliberator.ui.Ui.background(project, "Deliberator: replay ${f.name}", { ops.replay(f.name, id) })
                }
            }
        })
        ops.addListener(this) { r -> ApplicationManager.getApplication().invokeLater({ render(r) }, ModalityState.any()) }
        render(ops.fakes)
        refresh()
    }

    fun refresh() {
        if (WorkspaceService.getInstance(project).ws == null) return
        ApplicationManager.getApplication().executeOnPooledThread { runCatching { ops.list() } }
    }

    fun selected(): FakeRow? = table.selectedRow.takeIf { it >= 0 && it < rows.size }?.let { rows[it] }

    fun render(r: List<FakeRow>) {
        val keep = selected()?.name
        rows = r
        tableModel.rowCount = 0
        r.forEach { tableModel.addRow(cells(it)) }
        r.indexOfFirst { it.name == keep }.takeIf { it >= 0 }?.let { table.setRowSelectionInterval(it, it) }
        showRecordings()
    }

    private fun showRecordings() {
        recModel.clear()
        val f = selected()
        f?.recordings?.reversed()?.forEach { recModel.addElement(it + (if (it == f.recording) "  (in use${f.exchanges?.let { n -> ", $n exchanges" } ?: ""})" else "")) }
    }

    /** The recordings list as text (tests). */
    fun recordingLines(): List<String> = (0 until recModel.size()).map { recModel.getElementAt(it) }

    override fun dispose() {}

    companion object {
        val COLUMNS = arrayOf("Fake", "Mode", "Serving", "Upstream", "Port", "Services")

        fun cells(f: FakeRow): Array<Any> = arrayOf(f.name, f.mode, f.serving ?: "", f.upstream ?: "", f.port?.toString() ?: "", f.attached.joinToString(", "))
    }
}
