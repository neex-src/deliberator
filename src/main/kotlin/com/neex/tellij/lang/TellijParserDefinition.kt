package com.neex.tellij.lang

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

/**
 * The thinnest PSI that gives the file a real language: one leaf per lexer token under a flat
 * root. Nothing reads this tree — the parser stays [com.neex.tellij.model.WorkbenchParser] over
 * the text — but folding and completion attach per language, and a plain-text PSI is not ours.
 */
class TellijParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = TellijLexer()

    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val mark = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        mark.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType(): IFileElementType = FILE
    override fun getCommentTokens(): TokenSet = COMMENTS
    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY
    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = TellijFile(viewProvider)

    companion object {
        val FILE = IFileElementType(TellijLanguage)
        val COMMENTS: TokenSet = TokenSet.create(TellijTokens.COMMENT)
    }
}

class TellijFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, TellijLanguage) {
    override fun getFileType(): FileType = TellijFileType
    override fun toString(): String = "Tellij file"
}
