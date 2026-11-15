package com.neex.deliberator.logs

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.Alarm
import com.neex.deliberator.run.ScnRunListener
import com.neex.deliberator.ui.Ui
import com.neex.deliberator.workspace.WorkspaceService
import java.awt.Cursor
import java.awt.Point
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JLabel

/** A source file a log place can open: the file the daemon resolved, the attached repository it is in, its path there. */
data class LogTarget(val file: String, val repo: String, val rel: String)

/**
 * A clickable span of a log, as `logs.refs` answers it: 1-based line [n], columns [[from], [to)) holding [text]; [kind] is `frame`,
 * `logger`, `exception` or `node`; [line] and [col] (1-based) are in the source; [targets] are the files it resolves to (several
 * when the same class is in two repositories and the log does not say which service wrote it).
 */
data class LogRef(
    val n: Int, val from: Int, val to: Int, val text: String, val kind: String,
    val cls: String?, val method: String?, val line: Int?, val col: Int?, val targets: List<LogTarget>,
) {
    companion object {
        fun parse(e: JsonElement?): List<LogRef> = (e as? JsonArray)?.mapNotNull { x ->
            val o = x as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString
            fun i(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull()
            val ts = (o.get("targets") as? JsonArray)?.mapNotNull { t ->
                val to = t as? JsonObject ?: return@mapNotNull null
                LogTarget(to.get("file")?.asString ?: return@mapNotNull null, to.get("repo")?.asString ?: "", to.get("rel")?.asString ?: "")
            } ?: emptyList()
            LogRef(i("n") ?: return@mapNotNull null, i("from") ?: return@mapNotNull null, i("to") ?: return@mapNotNull null,
                s("text") ?: return@mapNotNull null, s("kind") ?: "", s("class") ?: s("logger"), s("method"), i("line"), i("col"), ts)
        } ?: emptyList()
    }
}

/** Where the declaration of a class is in a source file; one per language support, registered by the optional plugin configs. */
interface ClassLocator {
    /** The offset of the declaration of the class [fqn] (nested classes with `$`) in [file], or null. Runs in a read action. */
    fun declarationOffset(project: Project, file: VirtualFile, fqn: String): Int?

    companion object {
        val EP: ExtensionPointName<ClassLocator> = ExtensionPointName.create("com.neex.deliberator.classLocator")
    }
}

/**
 * Links from a log into the code. The daemon reads the log and says which spans name code of an attached
 * repository (`logs.refs`); nothing is parsed here. Each such span gets an underline (the editor's hyperlink colour), a hand cursor
 * and a tooltip naming the file and line it opens; Ctrl+click (Cmd+click on a Mac) opens it, a plain click only places the caret
 * (a log is an editor people select and copy from). Several targets (the same class in two repositories) open a list to choose from.
 * The links are redrawn when the editor opens, the document changes (debounced: a followed log grows), a scenario run finishes,
 * a log editor is selected again after 20 s, and on demand (`deliberator.logs.links`). A stale answer is dropped by sequence number;
 * a span whose text is no longer where the daemon saw it is not drawn.
 */
@Service(Service.Level.PROJECT)
class LogLinks(private val project: Project) : Disposable {
    private class EditorLinks(val disposable: Disposable) {
        val seq = AtomicInteger()
        val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
        val tip = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
        val highlighters = ArrayList<RangeHighlighter>()
        @Volatile var at = 0L
        var hover: RangeHighlighter? = null
        var balloon: Balloon? = null
    }

    init {
        val bus = project.messageBus.connect(this)
        bus.subscribe(ScnRunListener.TOPIC, object : ScnRunListener {
            override fun runFinished(path: String, snapshot: com.neex.deliberator.run.RunSnapshot) { logEditors().forEach { annotate(it) } }
        })
        bus.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                val f = event.newFile ?: return
                for (ed in logEditors()) {
                    if (FileDocumentManager.getInstance().getFile(ed.document) != f) continue
                    if (System.currentTimeMillis() - (ed.getUserData(KEY)?.at ?: 0L) > REFRESH_MS) annotate(ed)
                }
            }
        })
    }

    override fun dispose() {}

    /** The editors this service links now: logs, and the output of a run's steps. */
    fun logEditors(): List<Editor> = com.intellij.openapi.editor.EditorFactory.getInstance().allEditors.filter { it.project == project && !it.isDisposed && it.getUserData(KEY) != null }

    // lifecycle ---------------------------------------------------------------------------------------

    /** Starts linking [editor] (a log): mouse, hover, document changes; first answer asked at once. EDT. */
    fun attach(editor: Editor) {
        if (editor.getUserData(KEY) != null) return
        val d = Disposer.newDisposable("deliberator.logLinks")
        Disposer.register(this, d)
        val st = EditorLinks(d)
        editor.putUserData(KEY, st)
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseClicked(event: EditorMouseEvent) { clicked(editor, event) }
            override fun mouseExited(event: EditorMouseEvent) { hover(editor, null, null) }
        }, d)
        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(event: EditorMouseEvent) {
                val hit = if (event.isOverText) linkAt(editor, event.offset) else null
                hover(editor, hit, event.mouseEvent.point)
            }
        }, d)
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                st.alarm.cancelAllRequests()
                st.alarm.addRequest({ annotate(editor) }, DEBOUNCE_MS)
            }
        }, d)
        annotate(editor)
    }

    /** Stops linking [editor] (it is being released). */
    fun forget(editor: Editor) {
        val st = editor.getUserData(KEY) ?: return
        editor.putUserData(KEY, null)
        st.seq.incrementAndGet()
        st.balloon?.hide()
        (editor as? EditorEx)?.setCustomCursor(this, null)
        Disposer.dispose(st.disposable)
    }

    // the links -----------------------------------------------------------------------------------------

    /** `logs.refs` of this editor's file, then the underlines. Completes on the EDT after they are applied (or dropped). */
    fun annotate(editor: Editor): CompletableFuture<Unit> {
        val done = CompletableFuture<Unit>()
        val st = editor.getUserData(KEY)
        val vf = FileDocumentManager.getInstance().getFile(editor.document)
        val svc = WorkspaceService.getInstance(project)
        if (st == null || vf == null || svc.ws == null) { done.complete(Unit); return done }
        val seq = st.seq.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            val refs = try {
                LogRef.parse(svc.call("logs.refs", JsonObject().apply { addProperty("path", vf.path) }).resultObject().get("refs"))
            } catch (e: Exception) {
                LOG.info("logs.refs ${vf.path}: ${e.message}")
                null
            }
            ApplicationManager.getApplication().invokeLater({
                try {
                    if (refs != null && !editor.isDisposed && !project.isDisposed && st.seq.get() == seq) { apply(editor, st, refs); st.at = System.currentTimeMillis() }
                } finally { done.complete(Unit) }
            }, ModalityState.nonModal())
        }
        return done
    }

    private fun apply(editor: Editor, st: EditorLinks, refs: List<LogRef>) {
        st.highlighters.forEach { editor.markupModel.removeHighlighter(it) }
        st.highlighters.clear()
        hover(editor, null, null)
        val doc = editor.document
        for (r in refs) {
            val l0 = r.n - 1
            if (l0 < 0 || l0 >= doc.lineCount) continue // beyond what a truncated editor loaded
            val start = doc.getLineStartOffset(l0)
            val end = doc.getLineEndOffset(l0)
            if (start + r.to > end || r.from >= r.to) continue
            if (doc.getText(TextRange(start + r.from, start + r.to)) != r.text) continue // the log changed under the answer: no link at a wrong place
            val h = editor.markupModel.addRangeHighlighter(CodeInsightColors.HYPERLINK_ATTRIBUTES, start + r.from, start + r.to,
                HighlighterLayer.WARNING, HighlighterTargetArea.EXACT_RANGE)
            h.putUserData(REF, r)
            st.highlighters += h
        }
    }

    /** The links drawn in [editor] now, in text order. */
    fun links(editor: Editor): List<LogRef> =
        editor.getUserData(KEY)?.highlighters?.filter { it.isValid }?.sortedBy { it.startOffset }?.mapNotNull { it.getUserData(REF) } ?: emptyList()

    /** The link under [offset]: its highlighter and what the daemon resolved. */
    fun linkAt(editor: Editor, offset: Int): Pair<RangeHighlighter, LogRef>? {
        var found: Pair<RangeHighlighter, LogRef>? = null
        (editor.markupModel as MarkupModelEx).processRangeHighlightersOverlappingWith(offset, offset) { h ->
            val r = h.getUserData(REF)
            if (r != null && h.isValid && offset >= h.startOffset && offset <= h.endOffset) { found = h to r; false } else true
        }
        return found
    }

    /** The first link on 0-based [line0]. */
    fun firstOnLine(editor: Editor, line0: Int): Pair<RangeHighlighter, LogRef>? {
        val doc = editor.document
        if (line0 < 0 || line0 >= doc.lineCount) return null
        var best: Pair<RangeHighlighter, LogRef>? = null
        (editor.markupModel as MarkupModelEx).processRangeHighlightersOverlappingWith(doc.getLineStartOffset(line0), doc.getLineEndOffset(line0)) { h ->
            val r = h.getUserData(REF)
            if (r != null && h.isValid && (best == null || h.startOffset < best!!.first.startOffset)) best = h to r
            true
        }
        return best
    }

    // gestures ------------------------------------------------------------------------------------------

    private fun clicked(editor: Editor, e: EditorMouseEvent) {
        val me = e.mouseEvent
        if (me.button != MouseEvent.BUTTON1 || me.clickCount != 1 || me.isPopupTrigger || me.isShiftDown || me.isAltDown) return
        if (!(if (SystemInfo.isMac) me.isMetaDown else me.isControlDown)) return // a plain click places the caret
        if (!e.isOverText) return
        val hit = linkAt(editor, e.offset) ?: return
        me.consume()
        navigate(editor, hit.second)
    }

    private fun hover(editor: Editor, hit: Pair<RangeHighlighter, LogRef>?, at: Point?) {
        val st = editor.getUserData(KEY) ?: return
        if (st.hover === hit?.first) return
        st.hover = hit?.first
        (editor as? EditorEx)?.setCustomCursor(this, if (hit != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null)
        st.balloon?.hide()
        st.balloon = null
        st.tip.cancelAllRequests()
        if (hit != null && at != null && !ApplicationManager.getApplication().isHeadlessEnvironment) {
            st.tip.addRequest({ if (!editor.isDisposed && st.hover === hit.first) showTip(editor, st, hit.second, at) }, TIP_MS)
        }
    }

    private fun showTip(editor: Editor, st: EditorLinks, ref: LogRef, at: Point) {
        val b = JBPopupFactory.getInstance().createBalloonBuilder(JLabel(tooltip(ref)))
            .setFadeoutTime(0).setAnimationCycle(0).setHideOnClickOutside(true).setHideOnKeyOutside(true).createBalloon()
        st.balloon = b
        b.show(RelativePoint(editor.contentComponent, Point(at.x, at.y - 2)), Balloon.Position.above)
    }

    /** What hovering [ref] says: the file and line it opens, or the choice there is; and how to open it. */
    fun tooltip(ref: LogRef): String {
        val key = if (SystemInfo.isMac) "Cmd" else "Ctrl"
        val where = (ref.line?.let { ":$it" } ?: "") + (ref.col?.let { ":$it" } ?: "")
        val what = when (ref.targets.size) {
            0 -> "no source"
            1 -> ref.targets[0].let { "${it.repo}: ${it.rel}$where" }
            else -> "${ref.targets.size} files (${ref.targets.joinToString(", ") { it.repo }})"
        }
        return "$what  -  $key+click to open"
    }

    // navigation ----------------------------------------------------------------------------------------

    /** Opens the source of [ref]; with several targets a list asks which. EDT. */
    fun navigate(editor: Editor, ref: LogRef) {
        when (ref.targets.size) {
            0 -> {}
            1 -> open(ref, ref.targets[0])
            else -> JBPopupFactory.getInstance().createPopupChooserBuilder(ref.targets)
                .setTitle("${ref.text} is in ${ref.targets.size} files")
                .setRenderer(textListCellRenderer { "${it.repo}: ${it.rel}" })
                .setItemChosenCallback { open(ref, it) }
                .createPopup().showInBestPositionFor(editor)
        }
    }

    /**
     * Opens [t] at the line of [ref] (Node: and column); an exception class without a line at its declaration. EDT. A file the virtual
     * file system has not seen yet is refreshed on a pooled thread first (a refresh on the EDT is a slow operation) and opened when
     * that is done: the editor is then answered later, null now.
     */
    fun open(ref: LogRef, t: LogTarget): Editor? {
        val fs = LocalFileSystem.getInstance()
        val known = fs.findFileByNioFile(Path.of(t.file))
        if (known == null) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val vf = fs.refreshAndFindFileByNioFile(Path.of(t.file))
                ApplicationManager.getApplication().invokeLater({
                    if (project.isDisposed) return@invokeLater
                    if (vf == null) Ui.notify(project, "Log link", "${t.file} is gone", com.intellij.notification.NotificationType.WARNING)
                    else openFile(ref, vf)
                }, ModalityState.nonModal())
            }
            return null
        }
        return openFile(ref, known)
    }

    private fun openFile(ref: LogRef, vf: VirtualFile): Editor? {
        val offset = if (ref.line == null && ref.cls != null) declarationOffset(vf, ref.cls) else null
        val d = if (offset != null) OpenFileDescriptor(project, vf, offset)
        else OpenFileDescriptor(project, vf, maxOf(0, (ref.line ?: 1) - 1), maxOf(0, (ref.col ?: 1) - 1))
        d.navigate(true)
        return FileEditorManager.getInstance(project).selectedTextEditor
    }

    private fun declarationOffset(vf: VirtualFile, fqn: String): Int? =
        ApplicationManager.getApplication().runReadAction(Computable<Int?> {
            ClassLocator.EP.extensionList.firstNotNullOfOrNull { l -> runCatching { l.declarationOffset(project, vf, fqn) }.getOrNull() }
        })

    companion object {
        private val LOG = logger<LogLinks>()
        private val KEY = Key.create<EditorLinks>("deliberator.logLinks")
        private val REF = Key.create<LogRef>("deliberator.logRef")
        private const val DEBOUNCE_MS = 700
        private const val TIP_MS = 350
        private const val REFRESH_MS = 20_000L
        fun getInstance(project: Project): LogLinks = project.getService(LogLinks::class.java)

        /**
         * Files whose places can name code: a log (see [LogFiles], which holds a CI job's and a CI step's `.log`), a CI diff (`.diff` under a `ci/` directory: the
         * log's lines behind a `+`, `-` or space) and what a scenario run wrote (`runs/<id>/result.txt`, `runs/<id>/steps/...`).
         */
        fun wants(file: VirtualFile): Boolean {
            if (LogFiles.isLog(file)) return true
            val p = file.path
            if (file.extension.equals("diff", ignoreCase = true) && p.contains("/ci/")) return true
            return p.contains("/sessions/") && p.contains("/runs/") && (file.name == "result.txt" || p.contains("/steps/"))
        }
    }
}

/** `deliberator.logs.gotoSource`: the link under the caret on this log line (else the first one on the line) opens its source. */
class GoToSourceFromLogAction : LogEditorAction() {
    override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR) ?: return
        val links = LogLinks.getInstance(project)
        val line0 = editor.caretModel.logicalPosition.line
        val hit = links.linkAt(editor, editor.caretModel.offset) ?: links.firstOnLine(editor, line0)
        if (hit == null) Ui.notify(project, "Go to source", "No link on line ${line0 + 1}: the daemon found no place on it that names code of an attached repository")
        else links.navigate(editor, hit.second)
    }
}

/** `deliberator.logs.links`: asks the daemon again which places of this log name code (after attaching a repository). */
class UpdateLogLinksAction : LogEditorAction() {
    override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR) ?: return
        LogLinks.getInstance(project).annotate(editor)
    }
}
