package com.neex.deliberator.explorer

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.neex.deliberator.services.ServiceOps
import com.neex.deliberator.services.ServicesPanel
import com.neex.deliberator.ui.Ui
import com.neex.deliberator.workspace.ServiceRow

/** The workspace service an explorer row belongs to (M6.4) and why. */
data class ServiceLink(val service: ServiceRow, val kind: String, val resource: String, val why: String) {
    /** The grey text after the row: `service a · container deliberator-w-service-a · log/service-a`. */
    fun text(): String = "service ${service.name}" + (service.container?.let { " · container $it" } ?: "") + (service.logTarget?.let { " · $it" } ?: "")
}

/**
 * M6.4: which workspace service an ECS or log-group row of the explorer stands for, from `service.list` (its `container`, its `log-target` and the `log` that
 * target has in the environment). A log group is a service's when it is the CloudWatch group of the service's `log/` target, or is named after the service
 * (`/prefix/service-a`); an ECS cluster row when its name is the service's (`a`, `service-a`). An ECS *service* or *task definition* row (B31) is a service's
 * when its task definition runs a container named like the service (`service-a`), else when its name is or ends in `service-a` (`m6a-service-a`,
 * `m6a-service-a:3`). Nothing is guessed beyond the names.
 */
object ServiceLinks {
    private fun last(name: String) = name.trimEnd('/').substringAfterLast('/')

    fun forResource(kind: String, name: String, services: List<ServiceRow>, containers: List<String> = emptyList()): ServiceLink? {
        if (kind != "logs" && kind != "ecs" && kind != "ecs-services" && kind != "ecs-taskdefs") return null
        val tail = last(name)
        val deep = kind == "ecs-services" || kind == "ecs-taskdefs"
        val base = if (kind == "ecs-taskdefs") tail.substringBefore(':') else tail
        for (s in services) {
            if (kind == "logs" && s.logGroup != null && s.logGroup == name) return ServiceLink(s, kind, name, "the CloudWatch group of ${s.logTarget ?: "its log target"}")
            if (deep && containers.any { it == "service-${s.name}" || it == s.name }) return ServiceLink(s, kind, name, "its task definition runs the container of the service")
            if (base == "service-${s.name}" || base == s.name || (deep && base.endsWith("-service-${s.name}"))) return ServiceLink(s, kind, name, "named after the service")
        }
        return null
    }

    val LINK: DataKey<ServiceLink> = DataKey.create("deliberator.explorer.link")
}

/** `deliberator.explorer.serviceLog`: the log of the service an explorer row belongs to, through the `log-target` `service.list` gave (not a name convention). */
class OpenLinkedLogAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null && e.getData(ServiceLinks.LINK) != null }
    override fun actionPerformed(e: AnActionEvent) {
        val p = e.project ?: return
        val link = e.getData(ServiceLinks.LINK) ?: return
        Ui.background(p, "Deliberator: log of ${link.service.name}", {
            val path = ServiceOps.getInstance(p).fetchLog(link.service.name).get("path")?.asString
            if (path == null) Ui.notify(p, "Log of ${link.service.name}", "The daemon returned no log file.", NotificationType.WARNING)
            else Ui.openFileLater(p, path) { ok -> if (!ok) Ui.notify(p, "Log of ${link.service.name}", "Cannot open $path", NotificationType.WARNING) }
        })
    }
}

/** `deliberator.explorer.serviceRow`: select the service's row in the Services tab (its container is in the balloon). */
class RevealServiceAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null && e.getData(ServiceLinks.LINK) != null }
    override fun actionPerformed(e: AnActionEvent) {
        val p = e.project ?: return
        val link = e.getData(ServiceLinks.LINK) ?: return
        val tw = ToolWindowManager.getInstance(p).getToolWindow("Deliberator") ?: return
        tw.activate {
            val content = tw.contentManager.contents.firstOrNull { it.displayName == "Services" } ?: return@activate
            tw.contentManager.setSelectedContent(content)
            (content.component as? ServicesPanel)?.select(link.service.name)
        }
        Ui.notify(p, "Service ${link.service.name}", "container ${link.service.container ?: "?"}, ${link.service.status}")
    }
}

/** `deliberator.explorer.declaration`: a drift row's address opens the `.tf` file at the declaration (`terraform.locate`). */
class OpenDeclarationAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null && e.getData(AwsExplorerPanel.DRIFT_ADDRESS) != null }
    override fun actionPerformed(e: AnActionEvent) {
        val a = e.getData(AwsExplorerPanel.DRIFT_ADDRESS) ?: return
        e.getData(AwsExplorerPanel.EXPLORER)?.openDeclaration(a)
    }
}
