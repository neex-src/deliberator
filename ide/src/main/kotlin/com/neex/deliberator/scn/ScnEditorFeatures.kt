package com.neex.deliberator.scn

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase
import com.intellij.lang.ASTNode
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/** Where the content of [e] ends (its range without trailing blanks and newlines). */
private fun contentEnd(e: PsiElement): Int {
    val t = e.containingFile.text
    var end = e.textRange.endOffset
    while (end > e.textRange.startOffset && t[end - 1].isWhitespace()) end--
    return end
}

/** The end of the first line of [e] (before its end-of-line characters). */
private fun firstLineEnd(e: PsiElement): Int {
    val t = e.containingFile.text
    var p = e.textRange.startOffset
    while (p < t.length && t[p] != '\n' && t[p] != '\r') p++
    return p
}

/**
 * One region per step that has more than its `###` line: from the end of that line to the last character of
 * the step (the header line stays visible); one for a header block of two or more lines.
 */
class ScnFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val file = root as? ScnFile ?: return FoldingDescriptor.EMPTY_ARRAY
        val out = ArrayList<FoldingDescriptor>()
        file.header?.let { h ->
            val from = firstLineEnd(h)
            val to = contentEnd(h)
            if (to > from && document.getLineNumber(to) > document.getLineNumber(from)) out += FoldingDescriptor(h.node, TextRange(from, to))
        }
        for (s in file.steps) {
            val from = firstLineEnd(s)
            val to = contentEnd(s)
            if (to > from) out += FoldingDescriptor(s.node, TextRange(from, to))
        }
        return out.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String {
        val lines = node.text.trimEnd().lines().size
        return if (node.elementType == ScnElements.HEADER) " ..." else " ... ($lines lines)"
    }

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}

/** Steps as `kind verb target name`. */
class ScnStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        if (psiFile !is ScnFile) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel = ScnStructureViewModel(psiFile, editor)
        }
    }
}

class ScnStructureViewModel(file: ScnFile, editor: Editor?) :
    StructureViewModelBase(file, editor, ScnFileElement(file)), StructureViewModel.ElementInfoProvider {
    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false
    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean = element is ScnStepElement
    override fun getSuitableClasses(): Array<Class<*>> = arrayOf(ScnStep::class.java)
}

class ScnFileElement(file: ScnFile) : PsiTreeElementBase<ScnFile>(file) {
    override fun getChildrenBase(): Collection<StructureViewTreeElement> = element?.steps?.map { ScnStepElement(it) } ?: emptyList()
    override fun getPresentableText(): String? = element?.name
}

class ScnStepElement(step: ScnStep) : PsiTreeElementBase<ScnStep>(step) {
    override fun getChildrenBase(): Collection<StructureViewTreeElement> = emptyList()
    override fun getPresentableText(): String? = element?.headText
}
