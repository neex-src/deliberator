package com.neex.deliberator.logs

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager

/** Registered by `deliberator-java.xml`: the declaration of a (nested) class in a Java file, for an exception class of a log. */
class JavaClassLocator : ClassLocator {
    override fun declarationOffset(project: Project, file: VirtualFile, fqn: String): Int? {
        val psi = PsiManager.getInstance(project).findFile(file) as? PsiJavaFile ?: return null
        val parts = fqn.substringAfterLast('.').split('$').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var cls: PsiClass = psi.classes.firstOrNull { it.name == parts[0] } ?: return null
        for (p in parts.drop(1)) cls = cls.innerClasses.firstOrNull { it.name == p } ?: break
        return cls.nameIdentifier?.textOffset ?: cls.textOffset
    }
}
