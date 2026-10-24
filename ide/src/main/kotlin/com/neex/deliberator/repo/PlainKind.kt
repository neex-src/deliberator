package com.neex.deliberator.repo

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.platform.workspace.jps.entities.ContentRootEntity
import com.intellij.platform.workspace.jps.entities.InheritedSdkDependency
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.ModuleSourceDependency
import com.intellij.platform.workspace.jps.entities.ModuleTypeId
import com.intellij.workspaceModel.ide.NonPersistentEntitySource
import java.nio.file.Path

/**
 * An attached repository that is neither Gradle nor Maven: a module with one content root, made through the Workspace
 * Model under [NonPersistentEntitySource] (never persisted: no `.iml`, nothing in the repository or in `.idea/modules`; the daemon's
 * record is what brings it back on open). The module is named after the repository, with a numeric suffix when the name is taken.
 */
class PlainKind : RepositoryKind {
    override val kind = "plain"

    /** The name of a module whose content root is [dir] or lies above it, other than ours, or null. */
    private fun coveringModule(project: Project, dir: Path): String? {
        val owned = owned(project).map { it.normalize() }
        for (m in ModuleManager.getInstance(project).modules) {
            for (root in ModuleRootManager.getInstance(m).contentRoots) {
                val r = root.toNioPath().normalize()
                if (dir.startsWith(r) && r !in owned) return m.name
            }
        }
        return null
    }

    override fun isAttached(project: Project, dir: Path) = dir.normalize() in owned(project).map { it.normalize() } || coveringModule(project, dir) != null

    override fun attach(project: Project, dir: Path): String? {
        val d = dir.normalize()
        if (d in owned(project).map { it.normalize() }) return "already attached"
        coveringModule(project, d)?.let { return "already part of module $it" }
        val wm = WorkspaceModel.getInstance(project)
        val taken = ModuleManager.getInstance(project).modules.map { it.name }.toSet()
        val base = d.fileName?.toString() ?: "repository"
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { it !in taken }
        val url = wm.getVirtualFileUrlManager().getOrCreateFromUrl(VfsUtilCore.pathToUrl(d.toString()))
        runBlockingMaybeCancellable { wm.update("Attach repository $name") { storage ->
            storage.addEntity(ModuleEntity(name, listOf(InheritedSdkDependency, ModuleSourceDependency), NonPersistentEntitySource) {
                type = ModuleTypeId(MODULE_TYPE)
                contentRoots = listOf(ContentRootEntity(url, emptyList(), NonPersistentEntitySource))
            })
        } }
        return null
    }

    override fun detach(project: Project, dir: Path) {
        val d = dir.normalize().toString()
        val wm = WorkspaceModel.getInstance(project)
        runBlockingMaybeCancellable {
            wm.update("Detach repository ${dir.fileName}") { storage ->
                storage.entities(ModuleEntity::class.java).filter { rootOf(it)?.normalize()?.toString() == d }.toList()
                    .forEach { storage.removeEntity(it) }
            }
        }
    }

    override fun owned(project: Project): List<Path> =
        WorkspaceModel.getInstance(project).currentSnapshot.entities(ModuleEntity::class.java).mapNotNull { rootOf(it) }.toList()

    /** The directory of a module this plugin made (module type [MODULE_TYPE], its one content root), or null for any other module. */
    private fun rootOf(m: ModuleEntity): Path? =
        if (m.type?.name == MODULE_TYPE) m.contentRoots.firstOrNull()?.let { Path.of(it.url.presentableUrl) } else null

    private companion object {
        /** Marks our modules: [NonPersistentEntitySource] is the platform's source for what is never written, but it carries no path. */
        const val MODULE_TYPE = "deliberator-repo"
    }
}
