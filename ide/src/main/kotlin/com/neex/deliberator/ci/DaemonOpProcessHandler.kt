package com.neex.deliberator.ci

import com.google.gson.JsonObject
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.neex.deliberator.daemon.DaemonClient
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.workspace.WorkspaceService
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel

/**
 * A daemon op that streams `logs.line` events as a process: the console is its output (act's lines for `ci.local`, a job's lines for `ci.tail`), Stop cancels the
 * request (the daemon kills act and its containers, or leaves the follow), and the end is [summary] of the answer (its lines, the exit code) or the typed error.
 * [exit] is null while it runs. Nothing is decided here: what is printed is the daemon's text.
 */
class DaemonOpProcessHandler(
    private val project: Project,
    val op: String,
    private val args: JsonObject,
    val title: String,
    private val summary: (JsonObject) -> Pair<List<String>, Int>,
) : ProcessHandler() {
    private val stopped = AtomicBoolean(false)
    @Volatile private var call: DaemonClient.Call? = null
    @Volatile var result: JsonObject? = null; private set
    @Volatile var failure: DaemonException? = null; private set
    @Volatile var exit: Int? = null; private set
    private val record = StringBuilder()
    private val pending = StringBuilder()
    @Volatile private var live = false

    /** Everything printed (tests, and a late reader). */
    @Synchronized fun transcript(): String = record.toString()

    @Synchronized fun print(line: String) {
        val text = if (line.endsWith("\n")) line else line + "\n"
        record.append(text)
        if (live) notifyTextAvailable(text, ProcessOutputTypes.STDOUT) else pending.append(text)
    }

    @Synchronized private fun flush() { live = true; if (pending.isNotEmpty()) { notifyTextAvailable(pending.toString(), ProcessOutputTypes.STDOUT); pending.setLength(0) } }

    /** Starts the request on a pooled thread; call after the console is attached. */
    fun begin() {
        startNotify()
        flush()
        ApplicationManager.getApplication().executeOnPooledThread { work() }
    }

    private fun work() {
        try {
            print("> $op ${args.entrySet().joinToString(" ") { "${it.key}=${it.value.asString}" }}")
            val c = WorkspaceService.getInstance(project).start(op, args, true) { ev ->
                if (ev.name == "logs.line") ev.json.get("text")?.takeIf { it.isJsonPrimitive }?.asString?.let { print(it) }
            }
            call = c
            if (stopped.get()) c.cancel()
            val t = c.await()
            if (stopped.get()) { print("-- stopped"); finish(130); return }
            if (t.ok) {
                val r = t.resultObject()
                result = r
                val (lines, code) = summary(r)
                lines.forEach(::print)
                finish(code)
            } else {
                val e = DaemonException(t.errorMessage ?: "$op failed", t.errorType ?: "daemon/error", t.error?.get("hint")?.takeIf { it.isJsonPrimitive }?.asString, null, t.error)
                failure = e
                print("!! ${e.type}: ${e.message}")
                e.hint?.let { print("   hint: $it") }
                finish(2)
            }
        } catch (e: DaemonException) {
            if (stopped.get()) { print("-- stopped"); finish(130); return }
            failure = e
            print("!! ${e.type}: ${e.message}")
            e.hint?.let { print("   hint: $it") }
            finish(2)
        } catch (e: Exception) {
            // Stop cancels the request: the answer's future ends cancelled, which is the stop and not a failure
            if (stopped.get()) { print("-- stopped"); finish(130) } else { print("!! $e"); finish(2) }
        }
    }

    private fun finish(code: Int) {
        exit = code
        if (!isProcessTerminated) notifyProcessTerminated(code)   // also from "terminating": that is how a Stop ends
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

/** Makes the absolute paths the daemon prints (`-- text: /…/ci/x.log`) openable. */
class DaemonPathFilter(private val project: Project) : Filter {
    private val re = Regex("(/[^\\s:]+\\.(?:log|txt|diff|json))")
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val m = re.find(line) ?: return null
        val vf = LocalFileSystem.getInstance().findFileByNioFile(Path.of(m.value)) ?: return null
        val start = entireLength - line.length
        return Filter.Result(start + m.range.first, start + m.range.last + 1, OpenFileHyperlinkInfo(project, vf, 0, 0))
    }
}

/** Shows [handler]'s output as a closable tab of the Deliberator tool window, with a Stop button; starts it. EDT. */
object CiConsoles {
    fun show(project: Project, handler: DaemonOpProcessHandler): Content? {
        val tw = ToolWindowManager.getInstance(project).getToolWindow("Deliberator") ?: return null
        val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply { addFilter(DaemonPathFilter(project)) }.console
        console.attachToProcess(handler)
        val stop = object : AnAction("Stop", "Cancel the request: the daemon ends it", AllIcons.Actions.Suspend), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = !handler.isProcessTerminated }
            override fun actionPerformed(e: AnActionEvent) { handler.destroyProcess() }
        }
        val group = DefaultActionGroup(stop)
        val panel = JPanel(java.awt.BorderLayout())
        val bar = com.intellij.openapi.actionSystem.ActionManager.getInstance().createActionToolbar("DeliberatorCiConsole", group, false)
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

    val HANDLER = com.intellij.openapi.util.Key.create<DaemonOpProcessHandler>("deliberator.ci.handler")

    /** [show] from any thread. */
    fun showLater(project: Project, handler: DaemonOpProcessHandler) =
        ApplicationManager.getApplication().invokeLater({ show(project, handler) }, ModalityState.nonModal())
}
