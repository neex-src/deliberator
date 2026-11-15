package com.neex.deliberator.environments

import com.google.gson.JsonObject
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleView
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.neex.deliberator.ci.DaemonPathFilter
import com.neex.deliberator.daemon.DaemonClient
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.ui.Ui
import com.neex.deliberator.workspace.WorkspaceService
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel

/**
 * `deploy.follow` as a process: the console is the `deploy.line` events as they arrive (events, task states, the containers' lines, the end), Stop leaves the
 * follow. The daemon rewrites the deploy text after every change and names it in its first line; it is opened in the editor beside the console and re-read from
 * disk (at most once a second) while lines come, so the file in the editor grows as the rollout runs. Nothing is decided here: what is printed is the daemon's text.
 */
class DeployFollowHandler(private val project: Project, val env: String, val spec: String, val deployment: String?, val title: String) : ProcessHandler() {
    private val stopped = AtomicBoolean(false)
    @Volatile private var call: DaemonClient.Call? = null
    @Volatile var result: JsonObject? = null; private set
    @Volatile var failure: DaemonException? = null; private set
    @Volatile var exit: Int? = null; private set
    /** The deploy text the daemon named (null until its first line). */
    @Volatile var path: String? = null; private set
    private val record = StringBuilder()
    private val pending = StringBuilder()
    @Volatile private var live = false
    @Volatile private var vf: VirtualFile? = null
    @Volatile private var lastRefresh = 0L

    @Synchronized fun transcript(): String = record.toString()

    @Synchronized fun print(line: String) {
        val text = if (line.endsWith("\n")) line else line + "\n"
        record.append(text)
        if (live) notifyTextAvailable(text, ProcessOutputTypes.STDOUT) else pending.append(text)
    }

    @Synchronized private fun flush() { live = true; if (pending.isNotEmpty()) { notifyTextAvailable(pending.toString(), ProcessOutputTypes.STDOUT); pending.setLength(0) } }

    fun begin() {
        startNotify()
        flush()
        ApplicationManager.getApplication().executeOnPooledThread { work() }
    }

    private fun onLine(ev: com.neex.deliberator.daemon.DaemonEvent) {
        if (ev.name != "deploy.line") return
        ev.json.get("text")?.takeIf { it.isJsonPrimitive }?.asString?.let { print(it) }
        ev.json.get("path")?.takeIf { it.isJsonPrimitive }?.asString?.let { p -> if (path == null) { path = p; openText(p) } }
        refreshText()
    }

    private fun openText(p: String) {
        val f = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(p)) ?: return
        vf = f
        ApplicationManager.getApplication().invokeLater({ Ui.openEditor(project, f, 0) }, ModalityState.nonModal())
    }

    /** The daemon replaces the file (write beside, rename): the editor learns of it through a VFS refresh. */
    private fun refreshText() {
        val f = vf ?: return
        val now = System.currentTimeMillis()
        if (now - lastRefresh < 1000) return
        lastRefresh = now
        f.refresh(true, false)
    }

    private fun work() {
        try {
            val a = JsonObject().apply { addProperty("service", spec); addProperty("env", env); deployment?.let { addProperty("deployment", it) } }
            print("> deploy.follow service=$spec env=$env" + (deployment?.let { " deployment=$it" } ?: ""))
            val c = WorkspaceService.getInstance(project).start("deploy.follow", a, true) { onLine(it) }
            call = c
            if (stopped.get()) c.cancel()
            val t = c.await()
            if (stopped.get()) { print("-- stopped following"); finish(130); return }
            if (t.ok) {
                val r = t.resultObject()
                result = r
                path = r.get("path")?.takeIf { it.isJsonPrimitive }?.asString ?: path
                print("-- ${r.get("ended")?.asString ?: "ended"}: ${r.get("deployment")?.asString ?: ""} ${r.get("status")?.asString ?: ""}")
                path?.let { print("-- text: $it") }
                vf?.refresh(true, false)
                finish(0)
            } else {
                val e = DaemonException(t.errorMessage ?: "deploy.follow failed", t.errorType ?: "daemon/error", t.error?.get("hint")?.takeIf { it.isJsonPrimitive }?.asString, null, t.error)
                failure = e
                print("!! ${e.type}: ${e.message}")
                e.hint?.let { print("   hint: $it") }
                finish(2)
            }
        } catch (e: DaemonException) {
            if (stopped.get()) { print("-- stopped following"); finish(130); return }
            failure = e
            print("!! ${e.type}: ${e.message}")
            e.hint?.let { print("   hint: $it") }
            finish(2)
        } catch (e: Exception) {
            if (stopped.get()) { print("-- stopped following"); finish(130) } else { print("!! $e"); finish(2) }
        }
    }

    private fun finish(code: Int) {
        exit = code
        if (!isProcessTerminated) notifyProcessTerminated(code)
    }

    override fun destroyProcessImpl() {
        stopped.set(true)
        val c = call
        if (c != null) c.cancel() else if (!live) finish(130)
    }

    override fun detachProcessImpl() { stopped.set(true); call?.cancel(); notifyProcessDetached() }
    override fun detachIsDefault() = false
    override fun getProcessInput(): OutputStream? = null
}

/** The follow's console as a closable tab of the Deliberator tool window, with a Stop button. */
object DeployConsoles {
    val HANDLER = com.intellij.openapi.util.Key.create<DeployFollowHandler>("deliberator.deploy.handler")

    /** Shows [handler]'s output as a tab and starts it. EDT. */
    fun show(project: Project, handler: DeployFollowHandler): Content? {
        val tw = ToolWindowManager.getInstance(project).getToolWindow("Deliberator") ?: return null
        val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply { addFilter(DaemonPathFilter(project)) }.console
        console.attachToProcess(handler)
        val stop = object : AnAction("Stop", "Stop following: the rollout goes on", AllIcons.Actions.Suspend), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = !handler.isProcessTerminated }
            override fun actionPerformed(e: AnActionEvent) { handler.destroyProcess() }
        }
        val panel = JPanel(java.awt.BorderLayout())
        val bar = ActionManager.getInstance().createActionToolbar("DeliberatorDeployConsole", DefaultActionGroup(stop), false)
        bar.targetComponent = panel
        panel.add(bar.component, java.awt.BorderLayout.WEST)
        panel.add(console.component, java.awt.BorderLayout.CENTER)
        val content = ContentFactory.getInstance().createContent(panel, handler.title, true)
        content.isCloseable = true
        content.setDisposer(console)
        content.putUserData(HANDLER, handler)
        tw.contentManager.addContent(content)
        tw.contentManager.setSelectedContent(content)
        tw.activate(null)
        handler.begin()
        return content
    }
}
