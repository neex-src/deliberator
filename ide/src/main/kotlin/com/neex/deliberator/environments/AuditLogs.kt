package com.neex.deliberator.environments

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import com.intellij.ui.JBColor
import com.neex.deliberator.actions.WorkspaceAction
import com.neex.deliberator.ui.Ui
import com.neex.deliberator.workspace.WorkspaceService
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import javax.swing.JComponent

/** The audit trails the IDE opened, by file path: what `audit.show` said, for the banner above the file. */
@Service(Service.Level.PROJECT)
class AuditViews {
    private val byPath = ConcurrentHashMap<String, AuditSummary>()
    fun put(s: AuditSummary) { byPath[Path.of(s.path).toString()] = s }
    fun of(file: VirtualFile): AuditSummary? = byPath[file.path]
    fun forget() = byPath.clear()

    companion object { fun getInstance(project: Project): AuditViews = project.getService(AuditViews::class.java) }
}

/**
 * M4.6: the audit trail of a session as a text view. The daemon names the file and the lines (`audit.show`); the IDE opens that file
 * in the editor (it is the plain text the gate appended, grep-able and diff-able: principle 3) and marks the lines that matter: a
 * confirmation asked, given, or a mutation refused.
 */
object AuditLogs {
    private val MARK = Key.create<Boolean>("deliberator.audit.mark")

    private val ASKED = TextAttributes().apply { backgroundColor = JBColor(0xFFF3C4, 0x4A4220) }
    private val GIVEN = TextAttributes().apply { backgroundColor = JBColor(0xFFD58A, 0x6B4E10) }
    private val REFUSED = TextAttributes().apply { backgroundColor = JBColor(0xFAD4D4, 0x5A2A2A) }

    /** The marker colour of [line], null when it needs none: asked (yellow), given (orange: a call that went through with a confirmation), refused (red). */
    fun attributesFor(line: AuditLine): TextAttributes? = when {
        line.outcome == "refused" -> REFUSED
        line.given || line.outcome == "confirmed" -> GIVEN
        line.outcome == "confirm-required" -> ASKED
        else -> null
    }

    /** Asks which session when there are several, then [open]. EDT. */
    fun choose(project: Project) {
        val sessions = WorkspaceService.getInstance(project).model.sessions
        if (sessions.size <= 1) { open(project, sessions.firstOrNull()?.key); return }
        val cur = WorkspaceService.getInstance(project).model.currentSession
        val keys = sessions.map { it.key }.sortedBy { if (it == cur) 0 else 1 }
        JBPopupFactory.getInstance().createPopupChooserBuilder(keys).setTitle("Audit Log of Session")
            .setItemChosenCallback { open(project, it) }.createPopup().showCenteredInCurrentWindow(project)
    }

    /** Asks `audit.show` for [session] (default: the current one) off the EDT, opens the file and marks it. */
    fun open(project: Project, session: String?, onOpened: (Editor?) -> Unit = {}) {
        var summary: AuditSummary? = null
        var file: VirtualFile? = null
        Ui.background(project, "Deliberator: audit log", {
            summary = EnvironmentOps.audit(WorkspaceService.getInstance(project), session)
            file = summary?.takeIf { it.exists }?.let { resolve(it) }
        }, {
            val s = summary ?: return@background
            onOpened(show(project, s, file))
        })
    }

    /** The audit file as the VFS knows it: a refresh, a slow operation, so off the EDT. */
    fun resolve(s: AuditSummary): VirtualFile? = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(s.path))

    /** Opens [s]'s file (EDT; [vf] resolved off it); null with a balloon when no trail was written yet. */
    fun show(project: Project, s: AuditSummary, vf: VirtualFile? = if (s.exists) resolve(s) else null): Editor? {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (!s.exists) {
            Ui.notify(project, "Deliberator audit log", "Nothing has been audited yet (${s.scope}${s.session?.let { " $it" } ?: ""}): the trail has a line for every call on a remote environment.")
            return null
        }
        vf ?: return null
        AuditViews.getInstance(project).put(s)
        val editor = Ui.openEditor(project, vf, 0) ?: return null
        mark(editor, s)
        EditorNotifications.getInstance(project).updateNotifications(vf)
        return editor
    }

    /** Replaces the audit markers of [editor] with those of [s]; returns how many lines are marked. */
    fun mark(editor: Editor, s: AuditSummary): Int {
        editor.markupModel.allHighlighters.filter { it.getUserData(MARK) == true }.forEach { editor.markupModel.removeHighlighter(it) }
        var n = 0
        val lines = editor.document.lineCount
        for (l in s.lines) {
            val attrs = attributesFor(l) ?: continue
            if (l.n - 1 !in 0 until lines) continue
            editor.markupModel.addLineHighlighter(l.n - 1, HighlighterLayer.SELECTION - 1, attrs).putUserData(MARK, true)
            n++
        }
        return n
    }

    /** The 0-based lines carrying an audit marker in [editor] (tests). */
    fun markedLines(editor: Editor): List<Int> = editor.markupModel.allHighlighters.filter { it.getUserData(MARK) == true }.map { editor.document.getLineNumber(it.startOffset) }.sorted()
}

/** `deliberator.audit.open`: the audit trail of a session as text. */
class OpenAuditLogAction : WorkspaceAction() {
    override fun perform(project: Project, svc: WorkspaceService) = AuditLogs.choose(project)
}

/** The banner above an opened audit trail: how many attempts, confirmations and refusals. */
class AuditBanner : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val s = AuditViews.getInstance(project).of(file) ?: return null
        return Function { ed ->
            EditorNotificationPanel(ed, EditorNotificationPanel.Status.Info).apply {
                text = s.banner()
                createActionLabel("Reload") { AuditLogs.open(project, s.session) }
            }
        }
    }
}
