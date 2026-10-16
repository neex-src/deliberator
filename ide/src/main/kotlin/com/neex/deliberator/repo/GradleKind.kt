package com.neex.deliberator.repo

import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.gradle.settings.DistributionType
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Files
import java.nio.file.Path

/**
 * Gradle repositories: `GradleSettings.linkProject` then a background `ExternalSystemUtil.refreshProject`; the modules arrive
 * when the import finishes. Registered from `deliberator-gradle.xml`, i.e. only when the Gradle plugin is present.
 */
class GradleKind : RepositoryKind {
    override val kind = "gradle"

    private fun settings(project: Project) = GradleSettings.getInstance(project)

    override fun isAttached(project: Project, dir: Path) = settings(project).getLinkedProjectSettings(dir.toString()) != null

    override fun attach(project: Project, dir: Path): String? {
        if (isAttached(project, dir)) return "already linked"
        val ps = GradleProjectSettings(dir.toString()).apply {
            gradleJvm = "#JAVA_INTERNAL" // the IDE's own runtime: a workspace project has no SDK of its own
            distributionType = if (Files.exists(dir.resolve("gradle/wrapper/gradle-wrapper.properties"))) DistributionType.DEFAULT_WRAPPED else DistributionType.BUNDLED
        }
        settings(project).linkProject(ps)
        refresh(project, dir)
        return null
    }

    /** Asks for a (re)import in the background. */
    fun refresh(project: Project, dir: Path) {
        ExternalSystemUtil.refreshProject(dir.toString(), ImportSpecBuilder(project, GradleConstants.SYSTEM_ID).use(ProgressExecutionMode.IN_BACKGROUND_ASYNC))
    }

    override fun detach(project: Project, dir: Path) {
        val path = dir.toString()
        if (settings(project).getLinkedProjectSettings(path) == null) return
        // unlink, then drop the modules the import made for it (the linkage no longer owns them)
        settings(project).unlinkExternalProject(path)
        val modules = ModuleManager.getInstance(project).modules.filter { ExternalSystemApiUtil.getExternalRootProjectPath(it) == path }
        if (modules.isNotEmpty()) WriteAction.runAndWait<RuntimeException> {
            val model = ModuleManager.getInstance(project).getModifiableModel()
            modules.forEach { model.disposeModule(it) }
            model.commit()
        }
    }
}
