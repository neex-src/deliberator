/**
 * The IDE half of attaching a repository, one implementation per kind (`plain`, `gradle`, `maven`), found through the
 * extension point `com.neex.deliberator.repositoryKind`. `plain` is built in; the Gradle and Maven ones are registered from
 * optional plugin config files, so the plugin loads without those plugins and the kind is then simply absent.
 */
package com.neex.deliberator.repo

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import java.nio.file.Path

interface RepositoryKind {
    val kind: String

    /** Makes [dir] part of [project]; returns a note for the user when nothing had to be done (`already ...`), else null. Off the EDT. */
    fun attach(project: Project, dir: Path): String?

    /** The inverse of [attach]; a no-op when this plugin made nothing for [dir]. */
    fun detach(project: Project, dir: Path)

    /** Whether the project already contains [dir] in this kind's way. */
    fun isAttached(project: Project, dir: Path): Boolean

    /** Directories whose IDE state is discoverable as made by this plugin without a persisted list (plain modules). */
    fun owned(project: Project): List<Path> = emptyList()

    companion object {
        val EP: ExtensionPointName<RepositoryKind> = ExtensionPointName.create("com.neex.deliberator.repositoryKind")

        fun forKind(kind: String): RepositoryKind? = EP.extensionList.firstOrNull { it.kind == kind }
    }
}
