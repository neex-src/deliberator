package com.neex.deliberator.config

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.neex.deliberator.actions.WorkspaceAction
import com.neex.deliberator.ui.Ui
import com.neex.deliberator.workspace.WorkspaceService

/** `deliberator.config.refresh`: `config.refresh` for the selected environment; the cached chains reload afterwards and open views re-render. */
class ConfigRefreshAction : WorkspaceAction() {
    override fun perform(project: Project, svc: WorkspaceService) = ConfigRefresh.run(project)
}

/**
 * `deliberator.config.chain`: the whole chain of the service the current file belongs to (else the only service, else a chooser) as a read-only
 * text view, rendered from the `config.chain` result.
 */
class ConfigChainAction : WorkspaceAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        open(project, e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { it.isInLocalFileSystem }?.path)
    }

    override fun perform(project: Project, svc: WorkspaceService) = open(project, null)

    companion object {
        /** Finds the service of [path] (asking the daemon off the EDT), loads its chain and opens the view. */
        fun open(project: Project, path: String?) {
            var service: String? = null
            var all: List<String> = emptyList()
            Ui.background(project, "Deliberator: config chain", {
                val ix = ConfigChains.getInstance(project).loadIndex()
                all = ix.services
                service = path?.let { ix.serviceOf(it) } ?: all.singleOrNull()
                service?.let { ConfigChains.getInstance(project).load(it) }
            }, {
                val s = service
                when {
                    s != null -> showChain(project, s)
                    all.isEmpty() -> Ui.notify(project, "Deliberator config chain", "No service is adopted in this workspace (service.adopt).")
                    else -> JBPopupFactory.getInstance().createPopupChooserBuilder(all).setTitle("Config chain of service")
                        .setItemChosenCallback { pick ->
                            Ui.background(project, "Deliberator: config chain", { ConfigChains.getInstance(project).load(pick) }, { showChain(project, pick) })
                        }.createPopup().showCenteredInCurrentWindow(project)
                }
            })
        }

        fun showChain(project: Project, service: String) {
            ApplicationManager.getApplication().assertIsDispatchThread()
            ConfigChains.getInstance(project).cached(service)?.let { ConfigViews.getInstance(project).openChain(it) }
        }
    }
}
