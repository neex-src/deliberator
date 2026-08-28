package com.neex.tellij.lang

import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object TellijLanguage : Language("Tellij")

/**
 * `.tellij` files. Deliberately no ParserDefinition: the PSI is a plain-text file and every feature
 * works on the document text through [com.neex.tellij.model.WorkbenchParser]. Find, replace,
 * search-in-files and VCS all operate on text anyway — the one thing we give up is PSI-based
 * structure view / folding, which nobody asked for.
 */
object TellijFileType : LanguageFileType(TellijLanguage) {
    override fun getName(): String = "Tellij"
    override fun getDescription(): String = "Tellij workbench"
    override fun getDefaultExtension(): String = "tellij"
    override fun getIcon(): Icon = AllIcons.FileTypes.Text
}
