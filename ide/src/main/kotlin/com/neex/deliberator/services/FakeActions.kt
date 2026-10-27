package com.neex.deliberator.services

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import com.neex.deliberator.ui.Ui
import com.neex.deliberator.workspace.ServiceRow
import com.neex.deliberator.workspace.WorkspaceService
import javax.swing.JComponent

/** What Record Fake asks: the fake's name (the service it stands in for), the upstream, and whether the service is routed through it. */
class RecordFakeDialog(project: Project, service: String?, val services: List<String>) : DialogWrapper(project) {
    val name = JBTextField(service ?: "", 20)
    val upstream = JBTextField(if (service != null) "service/$service" else "", 28).apply { emptyText.text = "service/<name> or an http(s) URL" }
    val route = JBCheckBox("Route the service through the fake (topology fake, runtime.ensure)", service != null)

    init { title = "Record Fake"; setOKButtonText("Record"); init() }

    override fun createCenterPanel(): JComponent = panel {
        row("Fake name:") { cell(name).focused() }
        row("Upstream:") { cell(upstream) }
        row { cell(route) }
        row { comment("A recording starts: every request the fake forwards is stored raw under the session before it is answered.") }
    }

    override fun doValidate(): ValidationInfo? = when {
        name.text.isBlank() -> ValidationInfo("A name for the fake", name)
        upstream.text.isBlank() -> ValidationInfo("service/<name> or an http(s) URL", upstream)
        else -> null
    }

    fun answer() = Triple(name.text.trim(), upstream.text.trim(), route.isSelected)
}

/** Services-tab actions for the fakes (M6.6): the row comes from [ServicesPanel.SERVICE] or [ServicesPanel.FAKE]; from a menu a popup asks. */
object FakeActions {
    /** The balloon text after a fake call. */
    fun describe(f: FakeRow) = "${f.name}: ${f.mode}" + (f.url?.let { ", $it" } ?: "") + (f.exchanges?.let { ", $it exchanges" } ?: "")

    fun record(project: Project, service: ServiceRow?) {
        val names = WorkspaceService.getInstance(project).model.services.map { it.name }
        val d = RecordFakeDialog(project, service?.name, names)
        if (!d.showAndGet()) return
        val (name, upstream, route) = d.answer()
        Ui.background(project, "Deliberator: record fake $name", {
            val f = FakeOps.getInstance(project).record(name, upstream)
            if (route && name in names) {
                ServiceOps.getInstance(project).setTopology(name, "fake", fake = name)
                ServiceOps.getInstance(project).ensure()
                FakeOps.getInstance(project).list()   // the service is attached now
            }
            Ui.notify(project, "Recording $name", describe(f) + (if (route && name in names) "\n$name is routed through the fake" else ""))
        })
    }

    /** Replay [fake] ([recording] = an id, default the newest). */
    fun replay(project: Project, fake: FakeRow, recording: String?) = Ui.background(project, "Deliberator: replay ${fake.name}", {
        Ui.notify(project, "Replaying ${fake.name}", describe(FakeOps.getInstance(project).replay(fake.name, recording)))
    })

    fun stop(project: Project, fake: FakeRow) = Ui.background(project, "Deliberator: stop fake ${fake.name}", {
        FakeOps.getInstance(project).stop(fake.name)
        Ui.notify(project, "Fake ${fake.name} stopped", "The services attached to it are local again.")
    })

    /** Topology: serve [row] by one of the declared fakes. */
    fun attach(project: Project, row: ServiceRow) = ApplicationManager.getApplication().executeOnPooledThread {
        val fakes = try { FakeOps.getInstance(project).list() } catch (e: Exception) { Ui.notify(project, "Fake", e.message ?: e.toString(), NotificationType.ERROR); return@executeOnPooledThread }
        ApplicationManager.getApplication().invokeLater({
            if (fakes.isEmpty()) { Ui.notify(project, "Fake", "No fake is declared: Record Fake... first.", NotificationType.WARNING); return@invokeLater }
            JBPopupFactory.getInstance().createPopupChooserBuilder(fakes.map { it.name }).setTitle("${row.name} served by the fake")
                .setItemChosenCallback { f ->
                    Ui.background(project, "Deliberator: topology ${row.name}", { ServiceActions.setTopology(project, row.name, "fake", fake = f) })
                }.createPopup().showCenteredInCurrentWindow(project)
        }, ModalityState.nonModal())
    }

    /** Pick a fake (the selected one, else a popup) and call [then]. */
    fun withFake(project: Project, selected: FakeRow?, title: String, then: (FakeRow) -> Unit) {
        if (selected != null) { then(selected); return }
        ApplicationManager.getApplication().executeOnPooledThread {
            val fakes = try { FakeOps.getInstance(project).list() } catch (e: Exception) { Ui.notify(project, title, e.message ?: e.toString(), NotificationType.ERROR); return@executeOnPooledThread }
            ApplicationManager.getApplication().invokeLater({
                if (fakes.isEmpty()) { Ui.notify(project, title, "No fake is declared: Record Fake... first.", NotificationType.WARNING); return@invokeLater }
                JBPopupFactory.getInstance().createPopupChooserBuilder(fakes).setTitle(title)
                    .setRenderer(com.intellij.ui.dsl.listCellRenderer.textListCellRenderer { "${it.name}  ${it.mode}" })
                    .setItemChosenCallback(then).createPopup().showCenteredInCurrentWindow(project)
            }, ModalityState.nonModal())
        }
    }
}


/** `deliberator.fake.record` */
class FakeRecordAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val p = e.project
        e.presentation.isEnabledAndVisible = p != null
        if (p != null) e.presentation.isEnabled = WorkspaceService.getInstance(p).ws != null
    }
    override fun actionPerformed(e: AnActionEvent) { FakeActions.record(e.project ?: return, e.getData(ServicesPanel.SERVICE)) }
}

/** `deliberator.fake.replay`: the selected fake (a popup from a menu); the selected recording of the list, else the newest. */
class FakeReplayAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val p = e.project
        e.presentation.isEnabledAndVisible = p != null
        if (p != null) e.presentation.isEnabled = WorkspaceService.getInstance(p).ws != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        val p = e.project ?: return
        val rec = e.getData(ServicesPanel.RECORDING)
        FakeActions.withFake(p, e.getData(ServicesPanel.FAKE), "Replay Fake") { FakeActions.replay(p, it, rec) }
    }
}

/** `deliberator.fake.stop` */
class FakeStopAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val p = e.project
        e.presentation.isEnabledAndVisible = p != null
        if (p != null) e.presentation.isEnabled = WorkspaceService.getInstance(p).ws != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        val p = e.project ?: return
        FakeActions.withFake(p, e.getData(ServicesPanel.FAKE), "Stop Fake") { FakeActions.stop(p, it) }
    }
}
