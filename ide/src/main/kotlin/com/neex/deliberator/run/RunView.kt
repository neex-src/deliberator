package com.neex.deliberator.run

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.JBColor
import com.intellij.util.Alarm
import com.neex.deliberator.scn.ScnFile
import com.neex.deliberator.ui.Ui
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle
import java.security.MessageDigest
import javax.swing.Icon

/** What one inlay shows: a step's section (collapsed to a line or expanded), a live line from events, or the run banner. */
class ResultRenderer(
    private val view: RunView,
    /** The step number, or null for the banner. */
    val step: Int?,
    val section: StepSection?,
    val live: LiveStep?,
    val bannerText: String?,
) : EditorCustomElementRenderer {
    val expanded: Boolean get() = step != null && section != null && view.isExpanded(step)

    /** The lines painted now (what the tests compare with `result.txt`). */
    fun lines(): List<String> = when {
        bannerText != null -> listOf(bannerText)
        section != null && expanded -> {
            val all = section.text.split("\n")
            if (all.size <= MAX_LINES) all else all.take(MAX_LINES) + "... ${all.size - MAX_LINES} more lines (click the banner to open result.txt)"
        }
        section != null -> listOf(section.summary())
        live != null -> listOf(if (live.status == "running") "▶ running..." else "${live.status}" + (live.elapsedMs?.let { " · $it ms" } ?: ""))
        else -> emptyList()
    }

    private fun color(): Color {
        if (view.stale) return MUTED
        val s = section?.status ?: live?.status
        return when {
            bannerText != null -> view.bannerColor()
            s == "ok" -> OK
            s == "failed" || s == "error" -> BAD
            else -> MUTED
        }
    }

    private fun font(inlay: Inlay<*>) = inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN)

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val fm = inlay.editor.contentComponent.getFontMetrics(font(inlay))
        return (lines().maxOfOrNull { fm.stringWidth(it) } ?: 0) + 2 * PAD
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = inlay.editor.lineHeight * maxOf(1, lines().size)

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: com.intellij.openapi.editor.markup.TextAttributes) {
        val ed = inlay.editor
        g.font = font(inlay)
        g.color = color()
        val ascent = ed.contentComponent.getFontMetrics(g.font).ascent
        lines().forEachIndexed { i, l ->
            g.color = if (section != null && expanded && i > 0) lineColor(l) else color()
            g.drawString(l, targetRegion.x + PAD, targetRegion.y + i * ed.lineHeight + ascent)
        }
    }

    private fun lineColor(l: String): Color = when {
        view.stale -> MUTED
        l.startsWith("✓") -> OK
        l.startsWith("✗") || l.startsWith("ERROR:") -> BAD
        l == "cancelled" || l.startsWith("skipped:") -> MUTED
        else -> JBColor.foreground()
    }

    companion object {
        const val MAX_LINES = 200
        const val PAD = 10
        val OK: Color = JBColor(Color(0x1E7B34), Color(0x62B543))
        val BAD: Color = JBColor(Color(0xC62828), Color(0xE57373))
        val MUTED: Color = JBColor.GRAY
    }
}

/** The gutter icon of one step head: its state, and the three run actions behind a click. */
class StepGutterIcon(private val view: RunView, val step: Int, private val icon: Icon, private val tip: String, private val cancels: Boolean) : GutterIconRenderer(), DumbAware {
    override fun getIcon(): Icon = icon
    override fun getTooltipText(): String = tip
    override fun getAlignment(): Alignment = Alignment.LEFT
    override fun isNavigateAction(): Boolean = true
    override fun equals(other: Any?): Boolean = other is StepGutterIcon && other.step == step && other.icon === icon && other.tip == tip
    override fun hashCode(): Int = step * 31 + tip.hashCode()

    override fun getClickAction(): AnAction = object : AnAction(), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            if (cancels) { view.cancel(); return }
            val group = DefaultActionGroup(view.runActionsFor(step))
            JBPopupFactory.getInstance().createActionGroupPopup("Step $step", group, e.dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
                .showInBestPositionFor(view.editor)
        }
    }

    override fun getPopupMenuActions(): com.intellij.openapi.actionSystem.ActionGroup = DefaultActionGroup(view.runActionsFor(step))
}

/**
 * The run view of one `.scn` editor: under each step of the latest run a block inlay with that step's section of
 * `result.txt` (collapsed to one line, click to expand), a banner above line 1, gutter icons on the step heads.
 * It paints; it never writes into the document. A section stays at the step it was attached to when the run was
 * read; once the buffer differs from what ran, everything is marked stale and nothing is re-attached by index.
 */
class RunView(val editor: Editor, val project: Project, val file: VirtualFile) : Disposable {
    private val runner = ScnRunner.getInstance(project)
    private val path = file.path
    private val inlays = ArrayList<Inlay<ResultRenderer>>()
    private val highlighters = ArrayList<RangeHighlighter>()
    private val expandedSteps = HashSet<Int>()
    private var allExpanded = false
    private var shown: RunSnapshot? = null
    private var loading = false
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    /** The buffer differs from the run that is shown (edited, or the file changed since). */
    @Volatile var stale = false; private set

    var bannerText: String? = null; private set

    fun isExpanded(step: Int) = allExpanded || step in expandedSteps

    fun bannerColor(): Color = when {
        stale -> ResultRenderer.MUTED
        runner.isRunning(path) -> ResultRenderer.MUTED
        shown?.status == "ok" -> ResultRenderer.OK
        shown != null -> ResultRenderer.BAD
        else -> ResultRenderer.MUTED
    }

    init {
        val conn = project.messageBus.connect(this)
        conn.subscribe(ScnRunListener.TOPIC, object : ScnRunListener {
            override fun stateChanged(path: String) { if (path == this@RunView.path) refresh() }
        })
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (shown != null && !runner.isRunning(path)) stale = true
                scheduleGutter()
            }
        }, this)
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseClicked(e: EditorMouseEvent) {
                val inlay = e.inlay ?: return
                val r = inlay.renderer as? ResultRenderer ?: return
                if (r.step == null) { shown?.resultPath?.let { Ui.openFile(project, it) }; return }
                if (!expandedSteps.add(r.step)) expandedSteps.remove(r.step)
                inlay.update()
            }
        }, this)
        refresh()
        loadLatest()
    }

    // loading the run of an earlier session of the IDE -----------------------------------------------

    private fun loadLatest() {
        val st = runner.state(path)
        if (st.snapshot != null || st.running || loading) return
        if (com.neex.deliberator.workspace.WorkspaceService.getInstance(project).ws == null) return
        loading = true
        ApplicationManager.getApplication().executeOnPooledThread {
            val snap = try { runner.latest(path) } catch (_: Exception) { null }
            ApplicationManager.getApplication().invokeLater({
                loading = false
                if (snap != null && !Disposer.isDisposed(this) && runner.state(path).snapshot == null && !runner.isRunning(path)) runner.show(path, snap)
            }, com.intellij.openapi.application.ModalityState.nonModal())
        }
    }

    // painting ------------------------------------------------------------------------------------------

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun differsFromRun(snap: RunSnapshot): Boolean {
        if (FileDocumentManager.getInstance().isDocumentUnsaved(editor.document)) return true
        val sha = snap.scenarioSha ?: return false
        return runCatching { sha256(file.contentsToByteArray()) != sha }.getOrDefault(false)
    }

    /** Rebuilds inlays, banner and gutter from the runner's state. EDT. */
    fun refresh() {
        if (Disposer.isDisposed(this) || editor.isDisposed) return
        val st = runner.state(path)
        val snap = st.snapshot
        val running = st.running
        if (snap !== shown) {
            shown = snap
            stale = snap != null && !running && differsFromRun(snap)
            expandedSteps.clear()
            allExpanded = false
        }
        if (running) stale = false
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        val steps = (PsiDocumentManager.getInstance(project).getPsiFile(editor.document) as? ScnFile)?.steps.orEmpty()
        val keepInlays = stale && inlays.isNotEmpty() && !running
        if (!keepInlays) {
            inlays.forEach { Disposer.dispose(it) }
            inlays.clear()
            bannerText = banner(st, snap)
            val doc = editor.document
            bannerText?.let { add(0, true, ResultRenderer(this, null, null, null, it)) }
            for (s in steps) {
                val n = s.index
                val section = if (running) null else snap?.sections?.get(n)
                val live = if (running) st.live[n] else null
                if (section == null && live == null) continue
                val line = doc.getLineNumber(s.textRange.startOffset)
                add(doc.getLineEndOffset(line), false, ResultRenderer(this, n, section, live, null))
            }
        } else {
            inlays.forEach { it.update() }
        }
        rebuildGutter(steps, st, snap, running)
    }

    private fun add(offset: Int, above: Boolean, r: ResultRenderer) {
        editor.inlayModel.addBlockElement(offset, !above, above, 0, r)?.let { inlays += it }
    }

    private fun banner(st: RunState, snap: RunSnapshot?): String? {
        if (st.running) return "▶ running" + (st.runId?.let { " $it" } ?: "") + "  (Esc cancels)"
        if (snap == null) return null
        if (snap.problem != null) return "RUN ${snap.status} · ${snap.problem}"
        val t = snap.trailer.joinToString(" · ")
        return t + "  ·  ${snap.runId}" + (if (stale) "  ·  edited since this run" else "")
    }

    private fun rebuildGutter(steps: List<com.neex.deliberator.scn.ScnStep>, st: RunState, snap: RunSnapshot?, running: Boolean) {
        highlighters.forEach { editor.markupModel.removeHighlighter(it) }
        highlighters.clear()
        val doc = editor.document
        for (s in steps) {
            val n = s.index
            val (icon, tip, cancels) = gutterState(n, st, snap, running)
            val line = doc.getLineNumber(s.textRange.startOffset)
            val h = editor.markupModel.addLineHighlighter(null, line, HighlighterLayer.ADDITIONAL_SYNTAX)
            h.gutterIconRenderer = StepGutterIcon(this, n, icon, tip, cancels)
            highlighters += h
        }
    }

    private fun gutterState(n: Int, st: RunState, snap: RunSnapshot?, running: Boolean): Triple<Icon, String, Boolean> {
        val base = "Step $n"
        if (running) {
            val live = st.live[n]
            return when {
                live != null && live.status == "running" -> Triple(AllIcons.Actions.Suspend, "$base running: click or Esc to cancel the run", true)
                live != null -> Triple(statusIcon(live.status), "$base ${live.status}", false)
                st.asked.isEmpty() || n in st.asked -> Triple(AllIcons.RunConfigurations.TestNotRan, "$base waits for its turn", false)
                else -> Triple(AllIcons.RunConfigurations.TestState.Run, "Run $base (Ctrl+Enter), run from here, run all", false)
            }
        }
        val section = if (stale) null else snap?.sections?.get(n)
        return if (section != null) Triple(statusIcon(section.status), "$base ${section.status}. Click: run this step, run from here, run all", false)
        else Triple(AllIcons.RunConfigurations.TestState.Run, "Run $base (Ctrl+Enter), run from here, run all", false)
    }

    private fun statusIcon(status: String): Icon = when (status) {
        "ok" -> AllIcons.RunConfigurations.TestPassed
        "failed", "error" -> AllIcons.RunConfigurations.TestFailed
        "cancelled", "skipped" -> AllIcons.RunConfigurations.TestIgnored
        else -> AllIcons.RunConfigurations.TestState.Run
    }

    private fun scheduleGutter() {
        alarm.cancelAllRequests()
        alarm.addRequest({ if (!Disposer.isDisposed(this) && !editor.isDisposed) refresh() }, 300)
    }

    // actions --------------------------------------------------------------------------------------------

    /** Run this step / run from here / run all, as the gutter popup offers them. */
    fun runActionsFor(step: Int): List<AnAction> {
        fun act(text: String, f: () -> Unit) = object : AnAction(text), DumbAware {
            override fun actionPerformed(e: AnActionEvent) = f()
        }
        return listOf(
            act("Run This Step") { runSteps(listOf(step)) },
            act("Run From Here") { runFrom(step) },
            act("Run All") { runSteps(null) },
        )
    }

    private fun from(step: Int): List<Int> {
        val n = (PsiDocumentManager.getInstance(project).getPsiFile(editor.document) as? ScnFile)?.steps?.size ?: step
        return (step..n).toList()
    }

    fun runSteps(steps: List<Int>?) = runner.run(file, steps)
    fun runFrom(step: Int) = runSteps(from(step))
    fun cancel() = runner.cancel(path)

    fun toggleAll() {
        allExpanded = !allExpanded
        expandedSteps.clear()
        inlays.forEach { it.update() }
    }

    // inspection (tests) ---------------------------------------------------------------------------------

    class InlayInfo(val step: Int?, val lines: List<String>, val collapsed: List<String>, val section: StepSection?, val live: LiveStep?)

    fun inlayInfos(): List<InlayInfo> = inlays.map { i ->
        val r = i.renderer
        InlayInfo(r.step, r.lines(), if (r.section != null) listOf(r.section.summary()) else r.lines(), r.section, r.live)
    }

    fun gutterIcons(): Map<Int, StepGutterIcon> = highlighters.mapNotNull { it.gutterIconRenderer as? StepGutterIcon }.associateBy { it.step }

    override fun dispose() {
        inlays.forEach { Disposer.dispose(it) }
        inlays.clear()
        if (!editor.isDisposed) highlighters.forEach { editor.markupModel.removeHighlighter(it) }
        highlighters.clear()
    }

    companion object {
        private val KEY = com.intellij.openapi.util.Key.create<RunView>("deliberator.runView")
        fun of(editor: Editor): RunView? = editor.getUserData(KEY)
        internal fun attach(editor: Editor, project: Project, file: VirtualFile): RunView {
            of(editor)?.let { return it }
            return RunView(editor, project, file).also { editor.putUserData(KEY, it) }
        }
        internal fun detach(editor: Editor) {
            of(editor)?.let { Disposer.dispose(it); editor.putUserData(KEY, null) }
        }
    }
}
