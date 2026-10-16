package com.neex.deliberator.logs

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil

/** Registered by `deliberator-java.xml` (only when the Java plugin is there): the logger is the FQN of the top-level class at the line. */
class JavaLoggerResolver : LoggerResolver {
    override fun loggerAt(project: Project, file: VirtualFile, line0: Int): String? {
        val psi = PsiManager.getInstance(project).findFile(file) as? PsiJavaFile ?: return null
        val doc = PsiDocumentManager.getInstance(project).getDocument(psi) ?: return null
        if (line0 !in 0 until doc.lineCount) return null
        val start = doc.getLineStartOffset(line0)
        val end = doc.getLineEndOffset(line0)
        var at = start
        val text = doc.charsSequence
        while (at < end && text[at].isWhitespace()) at++
        val element = psi.findElementAt(at) ?: return null
        val cls = PsiTreeUtil.getParentOfType(element, com.intellij.psi.PsiClass::class.java, false) ?: return null
        return PsiUtil.getTopLevelClass(cls)?.qualifiedName
    }
}
