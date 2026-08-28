package com.neex.tellij.editor

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.neex.tellij.model.ResultMarkers
import com.neex.tellij.model.WorkbenchParser

/**
 * Every result section is one fold region; collapsed it reads as its header line, so a folded
 * buffer is the list of what ran and how it went. Expanded by default: what you just ran should
 * be visible — collapse with Esc, `zc`, Ctrl+−, or the gutter.
 */
class TellijFoldingBuilder : FoldingBuilderEx(), DumbAware {

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val model = WorkbenchParser.parse(document.immutableCharSequence)
        return model.blocks.mapNotNull { block ->
            val r = block.result ?: return@mapNotNull null
            if (r.length <= 0) return@mapNotNull null
            val header = document.immutableCharSequence.subSequence(r.start, minOf(r.end, document.getLineEndOffset(document.getLineNumber(r.start)))).toString()
            FoldingDescriptor(root.node, TextRange(r.start, r.end), null, placeholder(header))
        }.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String = "…"
    override fun isCollapsedByDefault(node: ASTNode): Boolean = false

    companion object {
        fun placeholder(header: String): String = header.removePrefix(ResultMarkers.START).trim().ifEmpty { "result" } + "  ⋯"

        /** Our regions among the editor's: those starting on a `#> result` line. */
        fun resultRegions(editor: Editor): List<FoldRegion> {
            val text = editor.document.immutableCharSequence
            return editor.foldingModel.allFoldRegions.filter { r ->
                r.isValid && text.length >= r.startOffset + ResultMarkers.START.length &&
                    ResultMarkers.isStart(text.subSequence(r.startOffset, r.startOffset + ResultMarkers.START.length))
            }
        }

        fun regionAt(editor: Editor, offset: Int): FoldRegion? =
            resultRegions(editor).firstOrNull { offset >= it.startOffset && offset <= it.endOffset }

        fun setExpanded(editor: Editor, regions: List<FoldRegion>, expanded: Boolean) {
            if (regions.isEmpty()) return
            editor.foldingModel.runBatchFoldingOperation { regions.forEach { it.isExpanded = expanded } }
        }
    }
}
