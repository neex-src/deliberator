package com.neex.tellij.lang

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType

object TellijColors {
    val SEPARATOR: TextAttributesKey = TextAttributesKey.createTextAttributesKey("TELLIJ_SEPARATOR", DefaultLanguageHighlighterColors.KEYWORD)
    val DIRECTIVE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("TELLIJ_DIRECTIVE", DefaultLanguageHighlighterColors.METADATA)
    val COMMENT: TextAttributesKey = TextAttributesKey.createTextAttributesKey("TELLIJ_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
    val VAR: TextAttributesKey = TextAttributesKey.createTextAttributesKey("TELLIJ_VAR", DefaultLanguageHighlighterColors.CONSTANT)
    val RESULT_MARKER: TextAttributesKey = TextAttributesKey.createTextAttributesKey("TELLIJ_RESULT_MARKER", DefaultLanguageHighlighterColors.DOC_COMMENT)
    val RESULT_BODY: TextAttributesKey = TextAttributesKey.createTextAttributesKey("TELLIJ_RESULT_BODY", DefaultLanguageHighlighterColors.LINE_COMMENT)
}

class TellijHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = TellijLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = when (tokenType) {
        TellijTokens.SEPARATOR -> pack(TellijColors.SEPARATOR)
        TellijTokens.DIRECTIVE -> pack(TellijColors.DIRECTIVE)
        TellijTokens.COMMENT -> pack(TellijColors.COMMENT)
        TellijTokens.VAR -> pack(TellijColors.VAR)
        TellijTokens.RESULT_MARKER -> pack(TellijColors.RESULT_MARKER)
        TellijTokens.RESULT_BODY -> pack(TellijColors.RESULT_BODY)
        else -> TextAttributesKey.EMPTY_ARRAY
    }
}

class TellijHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter = TellijHighlighter()
}
