package com.neex.deliberator.config

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * Warns where the configuration chain of the file's service is broken, from the cache of [ConfigChains] only: `broken` entries of this file
 * (a placeholder with no task definition entry; a parameter missing in the selected environment's snapshot) are warnings with the daemon's
 * reason, `unresolved` ones (the secret's `valueFrom` is a Terraform expression nobody evaluates) weak warnings, a complete chain gets nothing.
 * Nothing cached yet gives nothing and asks for a load; the analyzer restarts when it arrives. No daemon call happens here.
 *
 * Registered without a language: it works on any PSI of an `application*.properties|yml|yaml` file, including a plain-text mapping when the
 * Properties or YAML plugin is off; files without PSI are not inspected by the platform at all.
 */
class ConfigChainInspection : LocalInspectionTool(), com.intellij.openapi.project.DumbAware {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (!Placeholders.isConfigFileName(file.name)) return null
        val vf = file.originalFile.virtualFile?.takeIf { it.isInLocalFileSystem } ?: return null
        val (_, chain) = ConfigChains.getInstance(file.project).chainOfFile(vf.path) ?: return null
        chain ?: return null
        val doc = file.viewProvider.document ?: return null
        val out = LinkedHashMap<Pair<TextRange, String>, ProblemDescriptor>()
        fun add(ps: List<Problem>, type: ProblemHighlightType, label: String) {
            for (p in ps) {
                val e = p.entry
                val name = e.placeholder ?: continue
                if (e.file == null || e.line == null || !Chain.samePath(e.file, vf.path)) continue
                val line = e.line - 1
                if (line < 0 || line >= doc.lineCount) continue
                val from = doc.getLineStartOffset(line)
                val text = doc.getText(TextRange(from, doc.getLineEndOffset(line)))
                // the line must still hold the placeholder (the document may have moved on since the daemon read the file)
                val hit = Placeholders.scan(text).firstOrNull { it.name == name } ?: continue
                val range = TextRange(from + hit.start - 2, from + hit.end)
                val msg = "$label: ${p.reason ?: p.kind}"
                out.getOrPut(range to msg) { manager.createProblemDescriptor(file, range, msg, type, isOnTheFly) }
            }
        }
        add(chain.broken, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, "Deliberator")
        add(chain.unresolved, ProblemHighlightType.WEAK_WARNING, "Deliberator (cannot tell)")
        return out.values.toTypedArray().takeIf { it.isNotEmpty() }
    }
}
