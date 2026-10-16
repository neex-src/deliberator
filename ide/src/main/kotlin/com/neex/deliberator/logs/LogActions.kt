package com.neex.deliberator.logs

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorGutterComponentEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.ui.Ui
import com.neex.deliberator.workspace.WorkspaceService
import javax.swing.JComboBox
import javax.swing.JComponent

private fun Editor.logFile() = FileDocumentManager.getInstance().getFile(document)?.takeIf { LogFiles.isLog(it) }

/** `deliberator.logs.fetch`: target, window and filter, then `logs.fetch`, then the written file opens in the editor. */
class FetchLogsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project?.let { WorkspaceService.getInstance(it).ws != null } == true }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        Ui.background(project, "Deliberator: log targets", {
            val targets = try { LogService.getInstance(project).targets() } catch (ex: DaemonException) { emptyList() }
            ApplicationManager.getApplication().invokeLater { ask(project, targets)?.let { fetch(project, it) } }
        })
    }

    private fun ask(project: Project, targets: List<String>): FetchRequest? {
        val target = JComboBox(targets.toTypedArray()).apply { isEditable = true; selectedItem = targets.firstOrNull() ?: "" }
        val since = JBTextField("10m", 12)
        val forDur = JBTextField(12)
        val filter = JBTextField(24)
        val dlg = object : DialogWrapper(project) {
            init { title = "Fetch Logs"; init() }
            override fun createCenterPanel(): JComponent = panel {
                row("Target:") { cell(target) }
                row("Since:") { cell(since).comment("10m, 2h or an ISO-8601 instant") }
                row("Keep listening for:") { cell(forDur).comment("optional, e.g. 5s") }
                row("Filter:") { cell(filter).comment("optional text or /regex/") }
            }
        }
        if (!dlg.showAndGet()) return null
        val t = (target.selectedItem as? String)?.trim().orEmpty()
        return t.takeIf { it.isNotEmpty() }?.let { FetchRequest(it, since.text, forDur.text, filter.text) }
    }

    private fun fetch(project: Project, req: FetchRequest) {
        var path: String? = null
        Ui.background(project, "Deliberator: fetch ${req.target}", { path = LogService.getInstance(project).fetchNow(req).get("path")?.asString }, {
            path?.let { LogMatcher.getInstance(project).open(it, 1) }
        })
    }
}

/** Base of the actions that work on the log in the current editor. */
abstract class LogEditorAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR)?.logFile() != null
    }
}

/** `deliberator.logs.filter`: fold the entries that lack the correlation id (selection, else the latest run, else a prompt). */
class FilterByCorrelationAction : LogEditorAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val id = idFor(project, editor) ?: return
        CorrelationFilter.apply(editor, project, id)
    }

    companion object {
        /** The selection (one line, trimmed), else the latest run's id, else what the user types (pre-filled with the last choice). */
        fun idFor(project: Project, editor: Editor, ask: (String?) -> String? = { d -> Messages.showInputDialog(project, "Correlation id (or any text an entry must contain):", "Filter Log", null, d, null)?.trim() }): String? {
            editor.selectionModel.selectedText?.trim()?.takeIf { it.isNotEmpty() && !it.contains('\n') && it.length <= 200 }?.let { return it }
            val run = LogMatcher.getInstance(project).latestRun?.runId
            if (run != null) return run
            return ask(CorrelationFilter.stateOf(editor)?.id)?.takeIf { it.isNotEmpty() }
        }
    }
}

/** `deliberator.logs.unfilter`: unfold what the filter folded. */
class ClearCorrelationFilterAction : LogEditorAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        CorrelationFilter.clear(e.getData(CommonDataKeys.EDITOR) ?: return, project)
    }
}

/** `deliberator.logs.match`: annotate every open log file through `logs.match` now. */
class MatchMarkersAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project?.let { WorkspaceService.getInstance(it).ws != null } == true }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        MarkerService.getInstance(project).ensureLoaded()
        if (LogMatcher.getInstance(project).logEditors().isEmpty())
            Ui.notify(project, "Match markers", "No log file is open: Deliberator | Fetch Logs... opens one", NotificationType.INFORMATION)
        LogMatcher.getInstance(project).annotateAll()
    }
}

/** `deliberator.marker.toggle`: mark (or unmark) the source line under the caret, or the gutter line that was right-clicked. */
class ToggleMarkerAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        val ed = e.getData(CommonDataKeys.EDITOR)
        val vf = ed?.let { FileDocumentManager.getInstance().getFile(it.document) }
        val on = e.project != null && vf != null && vf.isInLocalFileSystem && !LogFiles.isLog(vf) && WorkspaceService.getInstance(e.project!!).ws != null
        e.presentation.isEnabledAndVisible = on
        if (on) {
            val line = lineOf(e, ed!!) + 1
            e.presentation.text = if (MarkerService.getInstance(e.project!!).markerAt(vf!!.path, line) != null) "Remove Deliberator Marker" else "Add Deliberator Marker"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        toggle(project, editor, lineOf(e, editor))
    }

    private fun lineOf(e: AnActionEvent, editor: Editor): Int =
        (e.getData(EditorGutterComponentEx.LOGICAL_LINE_AT_CURSOR) ?: editor.caretModel.logicalPosition.line).coerceIn(0, maxOf(0, editor.document.lineCount - 1))

    companion object {
        /** Marks 0-based [line0] of the editor's file (Java: with the class FQN) or removes the marker there; the daemon answers, the gutter follows. */
        fun toggle(project: Project, editor: Editor, line0: Int) {
            val vf = FileDocumentManager.getInstance().getFile(editor.document) ?: return
            val service = MarkerService.getInstance(project)
            val logger = service.loggerFor(vf, line0)
            Ui.background(project, "Deliberator: marker", { service.toggleNow(vf.path, line0 + 1, logger) })
        }
    }
}
