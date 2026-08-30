package com.neex.tellij.java.summary

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayModel
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.neex.tellij.java.endpoint.Endpoint
import com.neex.tellij.java.endpoint.EndpointResolver
import com.neex.tellij.java.endpoint.EndpointText
import javax.swing.JPanel

object JavaKeys {
    val SUMMARIES: Key<SummaryState> = Key.create("tellij.java.summaries")
}

/** The open summaries of one editor; disposed with it. */
class SummaryState(val editor: Editor) : Disposable {
    val entries = ArrayList<SummaryEntry>()
    override fun dispose() {
        entries.clear()
        editor.putUserData(JavaKeys.SUMMARIES, null)
    }
}

class SummaryEntry(val state: SummaryState, val methodRange: RangeMarker) : Disposable {
    lateinit var inlay: Inlay<*>
    lateinit var panel: JPanel
    var disposed = false
        private set

    override fun dispose() {
        disposed = true
        state.entries.remove(this)
        methodRange.dispose()
        if (::inlay.isInitialized && inlay.isValid) Disposer.dispose(inlay)
    }
}

/**
 * The human-readable endpoint, inline under the impl method's signature. Toggle with the action,
 * close with Esc or a click on the header; edits that remove the method remove the panel.
 */
object EndpointSummaryInlay {
    private val LOG = Logger.getInstance(EndpointSummaryInlay::class.java)

    fun javaFileOf(editor: Editor, project: Project): PsiJavaFile? =
        PsiDocumentManager.getInstance(project).getPsiFile(editor.document) as? PsiJavaFile

    fun toggle(editor: Editor, project: Project) {
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        val file = javaFileOf(editor, project) ?: return
        val offset = editor.caretModel.offset
        val el = file.findElementAt(offset) ?: (if (offset > 0) file.findElementAt(offset - 1) else null)
        val method = PsiTreeUtil.getParentOfType(el, PsiMethod::class.java, false)
        if (method == null) { hint(editor, "Put the caret inside a controller method"); return }

        val state = editor.getUserData(JavaKeys.SUMMARIES)
        state?.entries?.firstOrNull { it.methodRange.isValid && it.methodRange.startOffset == method.textRange.startOffset }?.let { collapse(it); return }

        val endpoint = try {
            EndpointResolver.resolve(method)
        } catch (e: IndexNotReadyException) {
            hint(editor, "Indexing in progress — try again in a moment"); return
        }
        if (endpoint == null) { hint(editor, "Not a Spring MVC handler: no mapping on ${method.name} or the interface it implements"); return }
        LOG.info("tellij endpoint: ${EndpointText.headline(endpoint)} (summary: ${endpoint.summary ?: "-"})")
        expand(editor as EditorEx, method, endpoint)
    }

    private fun expand(editor: EditorEx, method: PsiMethod, endpoint: Endpoint) {
        val state = editor.getUserData(JavaKeys.SUMMARIES) ?: SummaryState(editor).also {
            editor.putUserData(JavaKeys.SUMMARIES, it)
            EditorUtil.disposeWithEditor(editor, it)
        }
        val doc = editor.document
        val entry = SummaryEntry(state, doc.createRangeMarker(method.textRange))
        val header = InlayLook.header("▾ ${EndpointText.headline(endpoint)}   (${endpoint.impl.display})") { collapse(entry) }
        entry.panel = InlayLook.wrapper(header, InlayLook.body(EndpointText.render(endpoint)), indentPx(editor, method.textRange.startOffset))
        val anchor = method.body?.lBrace?.textOffset ?: method.nameIdentifier?.textOffset ?: method.textOffset
        val inlay = InlayLook.embed(editor, doc.getLineEndOffset(doc.getLineNumber(anchor)), entry.panel)
        if (inlay == null) { entry.methodRange.dispose(); hint(editor, "Could not create the inline panel here"); return }
        entry.inlay = inlay
        state.entries += entry
        Disposer.register(state, entry)
        editor.inlayModel.addListener(object : InlayModel.SimpleAdapter() {
            override fun onRemoved(removed: Inlay<*>) {
                if (removed === inlay && !entry.disposed) ApplicationManager.getApplication().invokeLater { if (!entry.disposed) collapse(entry) }
            }
        }, entry)
        doc.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!entry.disposed && (!entry.methodRange.isValid || entry.methodRange.startOffset == entry.methodRange.endOffset)) {
                    ApplicationManager.getApplication().invokeLater { if (!entry.disposed) collapse(entry) }
                }
            }
        }, entry)
        relayout(entry)
    }

    fun collapse(entry: SummaryEntry) {
        if (entry.disposed) return
        val editor = entry.state.editor
        Disposer.dispose(entry)
        if (!editor.isDisposed) editor.contentComponent.revalidate()
    }

    /** Esc: close the most recent open summary of this editor; false when there is none. */
    fun collapseLatest(editor: Editor): Boolean {
        val entry = editor.getUserData(JavaKeys.SUMMARIES)?.entries?.lastOrNull() ?: return false
        collapse(entry)
        return true
    }

    fun openCount(editor: Editor): Int = editor.getUserData(JavaKeys.SUMMARIES)?.entries?.size ?: 0

    private fun relayout(entry: SummaryEntry) {
        entry.panel.revalidate()
        entry.panel.repaint()
        if (entry.inlay.isValid) entry.inlay.update()
        entry.state.editor.contentComponent.revalidate()
    }

    private fun indentPx(editor: EditorEx, offset: Int): Int {
        val doc = editor.document
        val line = doc.getLineNumber(offset)
        var i = doc.getLineStartOffset(line)
        val end = doc.getLineEndOffset(line)
        val text = doc.charsSequence
        while (i < end && text[i].isWhitespace()) i++
        return editor.offsetToXY(i).x
    }

    private fun hint(editor: Editor, message: String) {
        if (editor.component.isShowing) HintManager.getInstance().showInformationHint(editor, message)
    }
}
