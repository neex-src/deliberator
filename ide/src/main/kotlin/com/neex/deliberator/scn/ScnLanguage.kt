/**
 * The `.scn` scenario language in the editor (piece 05 of m2): file type, lexer, flat PSI, highlighting,
 * folding, structure view, completion from the daemon's kind registry, parse errors as annotations.
 *
 * For later pieces: language id `Deliberator.Scn` ([ScnLanguage]), file type name `Deliberator Scenario`
 * ([ScnFileType]). A step is a [ScnStep] PSI element: `ScnFile.steps`, `startLine`/`endLine` (1-based, the
 * same numbers as `line`/`end-line` of `scenario.parse`), `index`, `kind`/`verb`/`target`/`name`, `directives`,
 * `body`. The cached `scenario.kinds` is `ScnRegistry.getInstance().kinds()` (never blocks).
 */
package com.neex.deliberator.scn

import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object ScnLanguage : Language("Deliberator.Scn") {
    private fun readResolve(): Any = ScnLanguage
    override fun getDisplayName(): String = "Deliberator Scenario"
    override fun isCaseSensitive(): Boolean = true
}

object ScnFileType : LanguageFileType(ScnLanguage) {
    override fun getName(): String = "Deliberator Scenario"
    override fun getDescription(): String = "Deliberator scenario (steps against an environment)"
    override fun getDefaultExtension(): String = "scn"
    override fun getIcon(): Icon = AllIcons.Actions.Play_forward
}
