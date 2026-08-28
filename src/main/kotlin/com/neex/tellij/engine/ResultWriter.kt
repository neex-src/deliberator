package com.neex.tellij.engine

import com.intellij.openapi.editor.Document
import com.neex.tellij.model.Block
import com.neex.tellij.model.ResultMarkers
import com.neex.tellij.model.TextRange
import com.neex.tellij.model.WorkbenchParser

/**
 * Places result sections into the document. A section sits right after the block's content,
 * separated by one blank line; re-running replaces the whole old section. Every function here
 * must be called inside a write action on the EDT.
 */
object ResultWriter {

    fun initialSection(block: Block, vars: Map<String, String>, at: String): String =
        ResultMarkers.header(block.kind, block.name, at, null, "running") + "\n" +
            ResultMarkers.varsLine(vars) + "\n" +
            ResultMarkers.END

    /** Inserts or replaces the section and returns the range of the section text itself. */
    fun write(document: Document, block: Block, section: String): TextRange {
        val existing = block.result
        if (existing != null) {
            document.replaceString(existing.start, existing.end, section)
            return TextRange(existing.start, existing.start + section.length)
        }
        val anchor = anchorOf(document, block)
        val insert = "\n\n$section"
        document.insertString(anchor, insert)
        return TextRange(anchor + 2, anchor + insert.length)
    }

    /** End of the block's last content line: body, directive, or the separator itself. */
    fun anchorOf(document: Document, block: Block): Int {
        var anchor = block.separator.end
        block.body?.let { anchor = maxOf(anchor, it.end) }
        for (d in block.directives) {
            if (d.line < document.lineCount) anchor = maxOf(anchor, document.getLineEndOffset(d.line))
        }
        return anchor
    }

    /** Removes every result section together with the blank lines that separate it from its block. */
    fun removeAll(document: Document): Int {
        val text = document.immutableCharSequence
        val model = WorkbenchParser.parse(text)
        var n = 0
        for (block in model.blocks.asReversed()) {
            val r = block.result ?: continue
            var from = r.start
            while (from > block.separator.end && text[from - 1].isWhitespace()) from--
            document.deleteString(from, r.end)
            n++
        }
        return n
    }
}
