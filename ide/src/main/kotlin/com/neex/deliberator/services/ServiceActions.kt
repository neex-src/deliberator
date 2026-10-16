package com.neex.deliberator.services

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.logs.LogMatcher
import com.neex.deliberator.ui.Ui
import com.neex.deliberator.workspace.ServiceRow
import com.neex.deliberator.workspace.WorkspaceService
import java.nio.file.Path
import javax.swing.JComponent

/**
 * What the Services actions do beyond the op itself: the balloon after it, the reload balloon with links, the offer of `runtime.ensure`
 * after a topology change. Every function blocks (call from [Ui.background]).
 */
object ServiceActions {
    fun info(project: Project, title: String, text: String) = Ui.notify(project, title, text)

    /** Reload from the Services tab: into the running configuration's console when there is one, else a balloon. */
    fun reload(project: Project, name: String): ReloadOutcome {
        val console = ServiceConsoles.getInstance(project).of(name)
        console?.print("> service.reload $name")
        val outcome = ServiceOps.getInstance(project).reload(name) { console?.print(it) }
        if (console == null) reloadBalloon(project, name, outcome)
        return outcome
    }

    fun reloadBalloon(project: Project, name: String, o: ReloadOutcome) {
        val fell = o.result?.get("fallback") != null
        val type = if (!o.ok) NotificationType.ERROR else if (fell) NotificationType.WARNING else NotificationType.INFORMATION
        ApplicationManager.getApplication().invokeLater {
            val n = NotificationGroupManager.getInstance().getNotificationGroup("Deliberator")
                .createNotification("Reload $name", StringUtil.escapeXmlEntities(o.lines.takeLast(12).joinToString("\n")).replace("\n", "<br>"), type)
            o.failure?.errors?.take(3)?.forEach { e ->
                n.addAction(NotificationAction.createSimple("Open ${Path.of(e.file).fileName}:${e.line}") { openPosition(project, e.file, e.line) })
            }
            (o.failure?.log ?: o.result?.get("log")?.takeIf { it.isJsonPrimitive }?.asString)?.let { log ->
                n.addAction(NotificationAction.createSimple("Open reload log") { Ui.openFile(project, log) })
            }
            n.notify(project)
        }
    }

    fun openPosition(project: Project, file: String, line: Int) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(file)) ?: return
        Ui.openEditor(project, vf, maxOf(0, line - 1))
    }

    /** `topology.set` in the selected environment, then the offer to apply it (`runtime.ensure`). */
    fun setTopology(project: Project, name: String, mode: String, url: String? = null, containerUrl: String? = null, logContainer: String? = null) {
        ServiceOps.getInstance(project).setTopology(name, mode, url, containerUrl, logContainer)
        offerEnsure(project, "$name is now ${if (mode == "external") "external ($url)" else mode} in ${WorkspaceService.getInstance(project).selectedEnv}")
    }

    /** A balloon with an "Apply now" action; nothing runs unasked. */
    fun offerEnsure(project: Project, what: String) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance().getNotificationGroup("Deliberator")
                .createNotification("Topology", "$what. The containers follow at the next runtime.ensure.", NotificationType.INFORMATION)
                .addAction(NotificationAction.createSimple("Apply now (runtime.ensure)") {
                    Ui.background(project, "Deliberator: runtime.ensure", { ServiceOps.getInstance(project).ensure() })
                })
                .notify(project)
        }
    }

    /** The "Deliberator Service" configuration of [name], created and saved when absent. EDT or any thread (RunManager is thread safe). */
    fun configurationFor(project: Project, name: String) = RunManager.getInstance(project).let { rm ->
        val title = "Deliberator Service: $name"
        rm.findConfigurationByTypeAndName(ServiceRunConfigurationType.ID, title) ?: run {
            val factory = com.intellij.execution.configurations.ConfigurationTypeUtil.findConfigurationType(ServiceRunConfigurationType::class.java).configurationFactories[0]
            val s = rm.createConfiguration(title, factory)
            (s.configuration as ServiceRunConfiguration).serviceName = name
            rm.addConfiguration(s)
            s
        }
    }
}

/** Dialog of the topology popup's `external...`. */
class ExternalDialog(project: Project, name: String) : DialogWrapper(project) {
    private val url = JBTextField(30)
    private val containerUrl = JBTextField(30).apply { emptyText.text = "optional: how containers reach it" }
    private val logContainer = JBTextField(30).apply { emptyText.text = "optional: container whose log stands for log/service-$name" }

    init { title = "Serve $name externally"; init() }

    override fun createCenterPanel(): JComponent = panel {
        row("URL:") { cell(url).focused() }
        row("Container URL:") { cell(containerUrl) }
        row("Log container:") { cell(logContainer) }
    }

    override fun doValidate() = if (url.text.isBlank()) com.intellij.openapi.ui.ValidationInfo("The base URL of the external service", url) else null

    fun answer() = Triple(url.text.trim(), containerUrl.text.trim().ifEmpty { null }, logContainer.text.trim().ifEmpty { null })
}

/** Base of the per-row actions: the row comes from [ServicesPanel.SERVICE]; from a menu it is chosen in a popup. */
abstract class ServiceAction(private val title: String) : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val p = e.project
        e.presentation.isEnabledAndVisible = p != null
        if (p != null) e.presentation.isEnabled = WorkspaceService.getInstance(p).ws != null && enabledFor(e.getData(ServicesPanel.SERVICE))
    }

    open fun enabledFor(row: ServiceRow?): Boolean = true

    override fun actionPerformed(e: AnActionEvent) {
        val p = e.project ?: return
        val row = e.getData(ServicesPanel.SERVICE)
        if (row != null) { go(p, row) ; return }
        val rows = WorkspaceService.getInstance(p).model.services
        if (rows.isEmpty()) { Ui.notify(p, title, "No services in this workspace: adopt one first.", NotificationType.WARNING); return }
        JBPopupFactory.getInstance().createPopupChooserBuilder(rows).setTitle(title)
            .setRenderer(textListCellRenderer { it.name })
            .setItemChosenCallback { go(p, it) }.createPopup().showCenteredInCurrentWindow(p)
    }

    private fun go(p: Project, row: ServiceRow) = perform(p, row)

    abstract fun perform(project: Project, row: ServiceRow)

    protected fun run(project: Project, what: String, work: () -> Unit) = Ui.background(project, "Deliberator: $what", work)
}

class ServiceStartAction : ServiceAction("Start Service") {
    override fun perform(project: Project, row: ServiceRow) = run(project, "start ${row.name}") {
        val s = ServiceOps.getInstance(project).start(row.name)
        ServiceActions.info(project, "Start ${row.name}", "${s.status}" + (s.container?.let { ", container $it" } ?: "") + (s.port?.let { ", port $it" } ?: "") + (s.jdwp?.let { ", JDWP $it" } ?: ""))
    }
}

class ServiceStopAction : ServiceAction("Stop Service") {
    override fun perform(project: Project, row: ServiceRow) = run(project, "stop ${row.name}") {
        ServiceActions.info(project, "Stop ${row.name}", ServiceOps.getInstance(project).stop(row.name).get("status")?.asString ?: "stopped")
    }
}

class ServiceRestartAction : ServiceAction("Restart Service") {
    override fun perform(project: Project, row: ServiceRow) = run(project, "restart ${row.name}") {
        val s = ServiceOps.getInstance(project).restart(row.name)
        ServiceActions.info(project, "Restart ${row.name}", s.status)
    }
}

class ServiceReloadAction : ServiceAction("Reload Service") {
    override fun perform(project: Project, row: ServiceRow) = run(project, "reload ${row.name}") { ServiceActions.reload(project, row.name) }
}

class ServiceLogsAction : ServiceAction("Service Log") {
    override fun perform(project: Project, row: ServiceRow) {
        var path: String? = null
        Ui.background(project, "Deliberator: log of ${row.name}", { path = ServiceOps.getInstance(project).fetchLog(row.name).get("path")?.asString },
            { path?.let { LogMatcher.getInstance(project).open(it, 1) } })
    }
}

class ServiceBuildAction : ServiceAction("Build Service") {
    override fun perform(project: Project, row: ServiceRow) = run(project, "build ${row.name}") {
        val r = ServiceOps.getInstance(project).build(row.name)
        ServiceActions.info(project, "Build ${row.name}", "built ${r.get("artifact")?.asString ?: ""} in ${r.get("took-ms")?.asString ?: "?"} ms" + (r.get("log")?.takeIf { it.isJsonPrimitive }?.let { "\nlog: ${it.asString}" } ?: ""))
    }
}

/** Debug = the "Deliberator Service" configuration of the row (created on first use) started with the Debug executor. */
class ServiceDebugAction : ServiceAction("Debug Service") {
    override fun perform(project: Project, row: ServiceRow) {
        ApplicationManager.getApplication().invokeLater {
            ProgramRunnerUtil.executeConfiguration(ServiceActions.configurationFor(project, row.name), DefaultDebugExecutor.getDebugExecutorInstance())
        }
    }
}

/** Not per row: pick a module directory, `service.adopt` it. */
class ServiceAdoptAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project?.let { WorkspaceService.getInstance(it).ws != null } == true }
    override fun actionPerformed(e: AnActionEvent) {
        val p = e.project ?: return
        val dir = FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Service module (with deliberator-service.edn)"), p, null) ?: return
        Ui.background(p, "Deliberator: adopt ${dir.name}", {
            val r = ServiceOps.getInstance(p).adopt(dir.path)
            ServiceActions.info(p, "Adopt", "${r.get("name")?.asString} ${if (r.get("created")?.asBoolean == true) "adopted" else "updated"}: ${r.get("path")?.asString ?: ""}")
        })
    }
}

/** local / external... / off for the row in the selected environment. */
class TopologySetAction : ServiceAction("Topology") {
    override fun perform(project: Project, row: ServiceRow) {
        val items = listOf("local", "external...", "off")
        ApplicationManager.getApplication().invokeLater {
            JBPopupFactory.getInstance().createPopupChooserBuilder(items).setTitle("${row.name}: topology in ${WorkspaceService.getInstance(project).selectedEnv} (now ${row.mode})")
                .setItemChosenCallback { choice ->
                    when (choice) {
                        "external..." -> {
                            val d = ExternalDialog(project, row.name)
                            if (d.showAndGet()) { val (u, c, l) = d.answer(); run(project, "topology ${row.name}") { ServiceActions.setTopology(project, row.name, "external", u, c, l) } }
                        }
                        else -> run(project, "topology ${row.name}") { ServiceActions.setTopology(project, row.name, choice) }
                    }
                }.createPopup().showCenteredInCurrentWindow(project)
        }
    }
}
