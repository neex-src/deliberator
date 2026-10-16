package com.neex.deliberator.config

import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.FakePsiElement
import com.neex.deliberator.ui.Ui
import java.nio.file.Path
import javax.swing.Icon

/**
 * Where a `${X}` of an application file leads: not PSI, because `.tftpl` and `.tf` have none in a plain IDE. A target is a file and a line
 * ([FileLine]) or a parameter view ([ParameterView]); both navigate themselves, so Ctrl+click, the chooser and tests all end in `navigate`.
 */
abstract class ConfigTarget(private val host: PsiElement, val label: String, val where: String) : FakePsiElement(), Navigatable {
    override fun getParent(): PsiElement = host
    override fun getName(): String = label
    override fun getNavigationElement(): PsiElement = this
    override fun canNavigate() = true
    override fun canNavigateToSource() = true
    override fun isPhysical() = false
    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText() = label
        override fun getLocationString() = where
        override fun getIcon(unused: Boolean): Icon? = null
    }
}

/** A line of a file the daemon named (1-based [line]). */
class FileLine(host: PsiElement, val path: String, val line: Int, label: String) : ConfigTarget(host, label, "${Path.of(path).fileName}:$line") {
    override fun navigate(requestFocus: Boolean) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(path)) ?: return
        Ui.openEditor(parent.project, vf, line - 1)
    }

    override fun equals(other: Any?) = other is FileLine && other.path == path && other.line == line && other.label == label
    override fun hashCode() = path.hashCode() * 31 + line
}

/** The parameter view of a secret entry's parameter, opened from the cached chain. */
class ParameterView(host: PsiElement, val service: String, val env: String, val param: Param) :
    ConfigTarget(host, "Parameter ${param.name} (${param.status ?: "?"}) - cached snapshot view", "$env snapshot") {
    override fun navigate(requestFocus: Boolean) { ConfigViews.getInstance(parent.project).openParameter(service, env, param) }
    override fun equals(other: Any?) = other is ParameterView && other.service == service && other.env == env && other.param.name == param.name
    override fun hashCode() = param.name.hashCode()
}

/** The entry of the cached chain that a placeholder written at [host]+[range] stands for, with the service it belongs to. */
internal fun lookup(host: PsiElement, range: TextRange, name: String): Triple<String, Chain, Entry>? {
    val file = host.containingFile?.originalFile ?: return null
    val path = file.virtualFile?.takeIf { it.isInLocalFileSystem }?.path ?: return null
    if (!Placeholders.isConfigFileName(file.name)) return null
    val (service, chain) = ConfigChains.getInstance(file.project).chainOfFile(path) ?: return null
    chain ?: return null
    val doc = PsiDocumentManager.getInstance(file.project).getCachedDocument(file) ?: FileDocumentManager.getInstance().getCachedDocument(file.virtualFile)
    val line = doc?.let { d -> (host.textRange.startOffset + range.startOffset).takeIf { it <= d.textLength }?.let { d.getLineNumber(it) + 1 } } ?: 0
    val entry = chain.entryAt(path, line, name) ?: return null
    return Triple(service, chain, entry)
}

/** `${X}` -> the task definition entry: the rendering line and, when the template renders from a Terraform map (`via loop`), the declaration. */
class TemplateEntryReference(host: PsiElement, range: TextRange, private val name: String) : PsiPolyVariantReferenceBase<PsiElement>(host, range, true) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        targets().map { PsiElementResolveResult(it) }.toTypedArray()

    override fun resolve(): PsiElement? = targets().firstOrNull()

    private fun targets(): List<PsiElement> {
        val (_, _, entry) = lookup(element, rangeInElement, name) ?: return emptyList()
        val td = entry.taskdef ?: return emptyList()
        val out = ArrayList<PsiElement>()
        if (td.file != null && td.line != null) {
            val how = if (td.via == "loop") "rendered here from a Terraform map, ${td.kind} ${td.name}" else "${td.kind} ${td.name}"
            out += FileLine(element, td.file, td.line, "$how (task definition template)")
        }
        val src = td.source
        if (td.via != "literal" && src?.file != null && src.line != null)
            out += FileLine(element, src.file, src.line, "${td.name} = ${src.expr ?: "?"} (declared in Terraform)")
        return out
    }
}

/** `${X}` of a secret entry -> its parameter, as a text view of the cached snapshot. */
class ParameterReference(host: PsiElement, range: TextRange, private val name: String) : PsiPolyVariantReferenceBase<PsiElement>(host, range, true) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> = resolve()?.let { arrayOf<ResolveResult>(PsiElementResolveResult(it)) } ?: emptyArray()

    override fun resolve(): PsiElement? {
        val (service, chain, entry) = lookup(element, rangeInElement, name) ?: return null
        val p = entry.parameter ?: return null
        if (entry.taskdef?.kind != "secret") return null
        return ParameterView(element, service, chain.env, p)
    }
}

/** The references of the placeholders in [host]'s text (properties value, yaml scalar). Always created (soft); they resolve from the cache. */
internal fun placeholderReferences(host: PsiElement): Array<PsiReference> {
    val file = host.containingFile ?: return PsiReference.EMPTY_ARRAY
    if (!Placeholders.isConfigFileName(file.name)) return PsiReference.EMPTY_ARRAY
    val hits = Placeholders.scan(host.text)
    if (hits.isEmpty()) return PsiReference.EMPTY_ARRAY
    val out = ArrayList<PsiReference>()
    for (h in hits) {
        val r = TextRange(h.start, h.end)
        out += TemplateEntryReference(host, r, h.name)
        out += ParameterReference(host, r, h.name)
    }
    return out.toTypedArray()
}
