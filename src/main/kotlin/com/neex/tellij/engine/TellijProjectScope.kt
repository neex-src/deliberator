package com.neex.tellij.engine

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** Disposable parent for run sessions: closing the project cancels every running workbench. */
@Service(Service.Level.PROJECT)
class TellijProjectScope : Disposable {
    override fun dispose() {}

    companion object {
        fun get(project: Project): TellijProjectScope = project.service()
    }
}
