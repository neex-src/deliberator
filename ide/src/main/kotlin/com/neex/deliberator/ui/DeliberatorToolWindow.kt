package com.neex.deliberator.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.neex.deliberator.actions.OpenRunAction
import com.neex.deliberator.workspace.RunRow
import com.neex.deliberator.workspace.WorkspaceModel
import com.neex.deliberator.workspace.WorkspaceService
import java.awt.BorderLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Duration
import java.time.Instant
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.table.DefaultTableModel

/** The "Deliberator" tool window: it only renders [WorkspaceModel]; every button is an action with an id. */
class DeliberatorToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DeliberatorPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

/** Header, sessions, runtime roles and latest runs of the project's workspace; refreshed by the model's listener and a 15 s tick while showing. */
class DeliberatorPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val svc = WorkspaceService.getInstance(project)
    private val header = JBLabel()
    private val sessions = DefaultListModel<String>()
    private val runtime = DefaultTableModel(arrayOf("Role", "State", "Health", "Ports"), 0)
    private val runs = DefaultListModel<RunRow>()
    private val runList = JBList(runs)
    private var ticks = 0

    init {
        val group = ActionManager.getInstance().getAction("deliberator.toolwindow.actions") as DefaultActionGroup
        val bar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_TOOLBAR_BAR, group, true)
        bar.targetComponent = this
        val top = JPanel(BorderLayout()).apply { add(bar.component, BorderLayout.NORTH); add(header, BorderLayout.SOUTH) }
        add(top, BorderLayout.NORTH)

        runList.cellRenderer = SimpleListCellRenderer.create("") { "${it.runId}  ${mark(it)}  ${it.scenario ?: ""}  ${it.env ?: ""}" }
        runList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) runList.selectedValue?.let { OpenRunAction.open(project, it.path) }
            }
        })
        val body = JPanel(GridLayout(3, 1))
        body.add(section("Sessions", JBScrollPane(JBList(sessions))))
        body.add(section("Runtime", JBScrollPane(JBTable(runtime).apply { setShowGrid(false) })))
        body.add(section("Latest runs (double-click opens result.txt)", JBScrollPane(runList)))
        add(body, BorderLayout.CENTER)

        svc.addListener(this, ::render)
        render(svc.model)
        svc.refresh()
        val timer = Timer(5000) {
            render(svc.model)
            if (isShowing && ++ticks % 3 == 0) svc.refresh()
        }
        timer.start()
        Disposer.register(this) { timer.stop() }
    }

    private fun section(title: String, c: java.awt.Component) = JPanel(BorderLayout()).apply {
        border = IdeBorderFactory.createTitledBorder(title)
        add(c, BorderLayout.CENTER)
    }

    private fun mark(r: RunRow) = when {
        r.status == null -> "?"
        (r.failed ?: 0) > 0 -> "${r.status} (${r.failed} failed)"
        else -> r.status
    }

    fun render(m: WorkspaceModel) {
        header.text = headerText(m)
        sessions.clear()
        m.sessions.forEach { sessions.addElement((if (it.key == m.currentSession) "> " else "  ") + it.key + (it.title?.let { t -> "  $t" } ?: "") + (if (it.isEnded) "  (ended)" else "")) }
        runtime.rowCount = 0
        m.containers.forEach { runtime.addRow(arrayOf(it.role, it.state, it.health ?: "", it.ports)) }
        if (m.containers.isEmpty() && m.podmanReachable == false) runtime.addRow(arrayOf("podman unreachable", "", "", m.podmanHint ?: ""))
        runs.clear()
        m.runs.forEach { runs.addElement(it) }
    }

    companion object {
        /** The one-line summary above the lists (also what the tests read). */
        fun headerText(m: WorkspaceModel): String {
            if (m.ws == null) return "Not a Deliberator workspace project: Deliberator | Open workspace... or Use workspace for this project..."
            val age = m.fetchedAt?.let { Duration.between(it, Instant.now()).seconds.coerceAtLeast(0).toString() + "s ago" } ?: "not fetched yet"
            return "workspace ${m.ws} | env ${m.selectedEnv} | session ${m.currentSession ?: "none"} | $age" + (m.error?.let { " | ERROR: $it" } ?: "")
        }
    }

    override fun dispose() {}
}
