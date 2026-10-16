package com.neex.deliberator.repo

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.nio.file.Path

/**
 * Maven repositories: the `pom.xml` becomes a managed file (`addManagedFiles`), the import follows in the background.
 * Registered from `deliberator-maven.xml`, i.e. only when the Maven plugin is present.
 */
class MavenKind : RepositoryKind {
    override val kind = "maven"

    private fun pom(dir: Path) = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir.resolve("pom.xml"))

    override fun isAttached(project: Project, dir: Path): Boolean {
        val pom = pom(dir) ?: return false
        return MavenProjectsManager.getInstance(project).isManagedFile(pom)
    }

    override fun attach(project: Project, dir: Path): String? {
        val pom = pom(dir) ?: throw IllegalStateException("no pom.xml in $dir")
        val mgr = MavenProjectsManager.getInstance(project)
        if (mgr.isManagedFile(pom)) return "already a managed Maven project"
        mgr.addManagedFiles(listOf(pom))
        return null
    }

    override fun detach(project: Project, dir: Path) {
        val pom = pom(dir) ?: return
        val mgr = MavenProjectsManager.getInstance(project)
        if (!mgr.isManagedFile(pom)) return
        // the modules the import made below this directory; removing the managed file leaves them to the next import
        val modules = ModuleManager.getInstance(project).modules.filter { m ->
            mgr.isMavenizedModule(m) && ModuleRootManager.getInstance(m).contentRoots.any { it.toNioPath().startsWith(dir) }
        }
        mgr.removeManagedFiles(listOf(pom))
        val alive = modules.filter { !it.isDisposed }
        if (alive.isNotEmpty()) WriteAction.runAndWait<RuntimeException> {
            val model = ModuleManager.getInstance(project).getModifiableModel()
            alive.filter { !it.isDisposed }.forEach { model.disposeModule(it) }
            model.commit()
        }
    }
}
