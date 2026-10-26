package com.neex.deliberator.logs

import com.google.gson.JsonObject
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.panel
import com.neex.deliberator.config.ConfigChains
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.environments.EnvironmentOps
import com.neex.deliberator.environments.LogGroupIndex
import com.neex.deliberator.environments.bytesText
import com.neex.deliberator.ui.Ui
import com.neex.deliberator.workspace.WorkspaceService
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import javax.swing.JComboBox
import javax.swing.JComponent

/**
 * M4.4: an exception class or message in the editor becomes a CloudWatch Logs Insights query. The query is built here as text the user can
 * edit in the dialog; the daemon runs it (`logs.insights`).
 */
object ExceptionQuery {
    /** The text to look for in a selection: its first non-blank line, trimmed, at most [MAX] characters. */
    fun needle(selection: String): String = selection.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(MAX)

    /** [s] as a literal inside an Insights `/regex/`: every regex metacharacter and the delimiter are escaped. */
    fun escape(s: String): String = buildString { for (c in s) { if (c in "\\^$.|?*+()[]{}/") append('\\'); append(c) } }

    fun query(selection: String, limit: Int = 200): String =
        "fields @timestamp, @logStream, @message | filter @message like /${escape(needle(selection))}/ | sort @timestamp desc | limit $limit"

    const val MAX = 200

    /** The windows the dialog offers: label to `since` (a duration ago, as `logs.insights` takes it). */
    val WINDOWS: List<Pair<String, String>> = listOf(
        "Last 15 minutes" to "15m", "Last hour" to "1h", "Last 6 hours" to "6h", "Last 24 hours" to "24h", "Last 7 days" to "168h",
    )
    const val DEFAULT_SINCE = "1h"
}

/** What the dialog asks `logs.insights` for. */
data class InsightsRequest(val groups: List<String>, val query: String, val since: String, val until: String?, val name: String?, val env: String)

/** The dialog of "Find in CloudWatch Logs": service, groups, window, the query text. */
class InsightsDialog(
    project: Project, private val env: String, selection: String, services: List<String>, preselect: String?, private val groupsOf: (String?) -> List<String>,
) : DialogWrapper(project) {
    private val serviceBox = JComboBox((listOf(NO_SERVICE) + services).toTypedArray())
    private val groups = JBTextField(48)
    private val window = JComboBox(ExceptionQuery.WINDOWS.map { it.first }.toTypedArray())
    private val since = JBTextField(ExceptionQuery.DEFAULT_SINCE, 12)
    private val until = JBTextField("", 24)
    private val name = JBTextField("", 24)
    private val query = JBTextArea(ExceptionQuery.query(selection), 4, 56)
    private var syncing = false

    init {
        title = "Find in CloudWatch Logs ($env)"
        setOKButtonText("Run Query")
        window.selectedIndex = ExceptionQuery.WINDOWS.indexOfFirst { it.second == ExceptionQuery.DEFAULT_SINCE }
        window.addActionListener { if (!syncing) since.text = ExceptionQuery.WINDOWS[window.selectedIndex].second }
        serviceBox.addActionListener { groups.text = groupsOf(selectedService()).joinToString(", ") }
        serviceBox.selectedItem = preselect?.takeIf { it in services } ?: NO_SERVICE
        groups.text = groupsOf(selectedService()).joinToString(", ")
        init()
    }

    private fun selectedService(): String? = (serviceBox.selectedItem as? String)?.takeIf { it != NO_SERVICE }

    override fun createCenterPanel(): JComponent = panel {
        row("Environment:") { label(env) }
        row("Service:") { cell(serviceBox).comment("Its log groups are filled in; none known: type the groups.") }
        row("Log groups:") { cell(groups).comment("Comma separated: group names (/aws/lambda/fn) or log/ targets of the environment.") }
        row("Window:") { cell(window); cell(since).comment("or any duration ago (90m) or ISO-8601 instant"); }
        row("Until:") { cell(until).comment("empty: now") }
        row("Query:") { cell(JBScrollPane(query)) }
        row("Name:") { cell(name).comment("optional: keeps the query as a named query of the session") }
    }

    override fun getPreferredFocusedComponent(): JComponent = query

    override fun doValidate(): ValidationInfo? = when {
        groupList().isEmpty() -> ValidationInfo("Give at least one log group.", groups)
        query.text.isBlank() -> ValidationInfo("The query is empty.", null)
        since.text.isBlank() -> ValidationInfo("Give the start of the window (1h, 30m, or an instant).", since)
        else -> null
    }

    private fun groupList() = groups.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** The request as the dialog stands. */
    fun request() = InsightsRequest(groupList(), query.text.trim(), since.text.trim(), until.text.trim().ifEmpty { null }, name.text.trim().ifEmpty { null }, env)

    internal fun set(groupsText: String? = null, sinceText: String? = null, queryText: String? = null) {
        groupsText?.let { groups.text = it }; sinceText?.let { since.text = it }; queryText?.let { query.text = it }
    }
    internal fun validation() = doValidate()
    internal fun windowText() = since.text

    companion object { const val NO_SERVICE = "(any: type the groups)" }
}

/** The result of the last Insights run per file: for the banner above the opened text. */
data class InsightsResult(val path: String, val status: String?, val rows: Int?, val bytes: Long?, val groups: List<String>, val since: String, val env: String, val queryFile: String?) {
    fun banner(): String = "CloudWatch Logs Insights ($env): ${rows ?: "?"} row(s), ${bytesText(bytes)} scanned" + (status?.let { ", $it" } ?: "") +
        ". Groups: ${groups.joinToString(", ")}; window: last $since."

    companion object {
        fun parse(o: JsonObject, req: InsightsRequest) = InsightsResult(
            o.get("path")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
            o.get("status")?.takeIf { it.isJsonPrimitive }?.asString,
            o.get("rows")?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull(),
            o.get("bytes-scanned")?.takeIf { it.isJsonPrimitive }?.runCatching { asLong }?.getOrNull(),
            req.groups, req.since, req.env,
            o.get("query-file")?.takeIf { it.isJsonPrimitive }?.asString,
        )
    }
}

@Service(Service.Level.PROJECT)
class InsightsViews {
    private val byPath = ConcurrentHashMap<String, InsightsResult>()
    fun put(r: InsightsResult) { byPath[Path.of(r.path).toString()] = r }
    fun of(file: VirtualFile): InsightsResult? = byPath[file.path]

    companion object { fun getInstance(project: Project): InsightsViews = project.getService(InsightsViews::class.java) }
}

/** The banner above an Insights result: rows, bytes scanned (what Insights bills), groups and window. */
class InsightsBanner : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val r = InsightsViews.getInstance(project).of(file) ?: return null
        return Function { ed -> EditorNotificationPanel(ed, EditorNotificationPanel.Status.Info).apply { text = r.banner() } }
    }
}

/** Runs the query with `logs.insights` (cancellable), opens the text the daemon wrote and puts the bytes scanned in a banner. */
object InsightsRun {
    fun args(r: InsightsRequest) = JsonObject().apply {
        add("groups", com.google.gson.JsonArray().also { a -> r.groups.forEach(a::add) })
        addProperty("query", r.query); addProperty("env", r.env); addProperty("since", r.since)
        r.until?.let { addProperty("until", it) }; r.name?.let { addProperty("name", it) }
    }

    /** Blocking, background threads only: one `logs.insights`, cancelled with the indicator. Returns the parsed result. */
    fun runNow(project: Project, r: InsightsRequest, indicator: ProgressIndicator? = null): InsightsResult {
        val call = WorkspaceService.getInstance(project).start("logs.insights", args(r))
        try {
            while (!call.terminal.isDone) {
                if (indicator?.isCanceled == true) { call.cancel(); throw DaemonException("cancelled", "daemon/cancelled") }
                Thread.sleep(100)
            }
            val t = call.await()
            if (!t.ok) throw DaemonException(t.errorMessage ?: "logs.insights failed", t.errorType ?: "daemon/error", t.error?.get("hint")?.takeIf { it.isJsonPrimitive }?.asString)
            return InsightsResult.parse(t.resultObject(), r)
        } finally { if (!call.terminal.isDone) call.cancel() }
    }

    /** Opens the result file (EDT) and shows the banner; returns the file. */
    fun show(project: Project, res: InsightsResult): VirtualFile? {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(res.path)) ?: return null
        InsightsViews.getInstance(project).put(res)
        Ui.openEditor(project, vf, 0)
        EditorNotifications.getInstance(project).updateNotifications(vf)
        return vf
    }

    fun run(project: Project, r: InsightsRequest, after: (InsightsResult) -> Unit = {}) {
        var res: InsightsResult? = null
        object : Task.Backgroundable(project, "Deliberator: Logs Insights (${r.env})", true) {
            override fun run(indicator: ProgressIndicator) {
                try { res = runNow(project, r, indicator) } catch (e: DaemonException) {
                    if (e.type != "daemon/cancelled") Ui.notify(project, "Deliberator Logs Insights", e.message + (e.hint?.let { "\n$it" } ?: ""), NotificationType.ERROR)
                }
            }

            override fun onFinished() { res?.let { show(project, it); after(it) } }
        }.queue()
    }
}

/**
 * `deliberator.logs.insights`: the selection (an exception class or message) is searched in the CloudWatch log groups of the selected
 * environment's service over a time window (default 1 h); the answer opens as text with the bytes scanned in a banner.
 */
class FindInCloudWatchAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val p = e.project
        e.presentation.isEnabledAndVisible = p != null && e.getData(CommonDataKeys.EDITOR) != null
        if (p != null) e.presentation.isEnabled = WorkspaceService.getInstance(p).ws != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val selection = editor?.selectionModel?.selectedText.orEmpty()
        val path = e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { it.isInLocalFileSystem }?.path
        open(project, selection, path)
    }

    companion object {
        /** Finds the service of [path], asks the dialog (EDT) and runs. The environment is the project's. */
        fun open(project: Project, selection: String, path: String?, run: (InsightsRequest) -> Unit = { InsightsRun.run(project, it) }) {
            val svc = WorkspaceService.getInstance(project)
            var services: List<String> = emptyList()
            var service: String? = null
            val env = svc.selectedEnv
            var index: LogGroupIndex? = null
            Ui.background(project, "Deliberator: log groups", {
                services = svc.model.services.map { it.name }
                service = path?.let { p -> runCatching { ConfigChains.getInstance(project).loadIndex().serviceOf(p) }.getOrNull() }
                index = EnvironmentOps.logGroupIndex(svc, env)
            }, {
                val idx = index ?: LogGroupIndex(emptyMap(), emptyList())
                val dlg = InsightsDialog(project, env, selection, services, service) { s -> idx.forService(s) }
                if (dlg.showAndGet()) run(dlg.request())
            })
        }
    }
}
