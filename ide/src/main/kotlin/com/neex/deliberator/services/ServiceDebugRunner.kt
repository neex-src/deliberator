package com.neex.deliberator.services

import com.intellij.debugger.impl.GenericDebuggerRunner
import com.intellij.debugger.impl.GenericDebuggerRunnerSettings
import com.intellij.debugger.settings.DebuggerSettings
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.task.ProjectTaskManager
import com.neex.deliberator.daemon.DaemonException

/**
 * Debug for a "Deliberator Service" configuration (loaded with the java plugin only): `service.start` first, off the EDT, because the debugger
 * needs the JDWP host port of the started container; then the platform's own debug start, which takes our state as a `RemoteState`
 * ([ServiceRunState.getRemoteConnection]) and attaches. "Reload Changed Classes" is the platform's, over that same JDWP connection.
 *
 * B19: the platform's Reload Changed Classes redefines every class file written since the session began, in every module. The first build the IDE runs
 * in a project it never built recompiles all of them with its own compiler (the IDE's JDK, not the container's Maven JDK 21): a different javac numbers
 * lambdas differently (`lambda$loop$2` of the container's build is `lambda$loop$0` of the IDE's), and the shared module's classes redefined with those
 * bytes lose the synthetic methods their running threads still call (`NoSuchMethodError`). So the IDE builds once BEFORE the session exists
 * ([buildBaseline]): the class files it writes are older than the session, and a reload afterwards redefines only what the build changed after that,
 * the classes of the sources edited since.
 */
class ServiceDebugRunner : GenericDebuggerRunner() {
    override fun getRunnerId() = "DeliberatorServiceDebug"

    override fun canRun(executorId: String, profile: RunProfile) = executorId == DefaultDebugExecutor.EXECUTOR_ID && profile is ServiceRunConfiguration

    override fun execute(environment: ExecutionEnvironment) {
        val state = environment.state as? ServiceRunState ?: return super.execute(environment)
        val config = environment.runProfile as ServiceRunConfiguration
        state.debug = true
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                state.prestarted = ServiceOps.getInstance(environment.project).start(config.serviceName, state.startArgs())
                val conn = state.getRemoteConnection() // a service without a JDWP port fails here, before a debug session is created
                buildBaseline(environment.project)
                // the platform sets the connection's debugger address from these runner settings (an empty port by default)
                (environment.runnerSettings as? GenericDebuggerRunnerSettings)?.apply {
                    debugPort = conn.debuggerAddress
                    transport = DebuggerSettings.SOCKET_TRANSPORT
                }
                ApplicationManager.getApplication().invokeLater({ super.execute(environment) }, ModalityState.any())
            } catch (e: DaemonException) {
                fail(environment, e.readable())
            } catch (e: ExecutionException) {
                fail(environment, e.message ?: "debug start failed")
            }
        }
    }

    private fun fail(environment: ExecutionEnvironment, message: String) {
        ApplicationManager.getApplication().invokeLater({ ExecutionUtil.handleExecutionError(environment, ExecutionException(message)) }, ModalityState.any())
    }

    companion object {
        private val LOG = Logger.getInstance(ServiceDebugRunner::class.java)

        /** Off switch for the proof of B19 (the walk reproduces the bug with it off) and for tests. */
        @Volatile var baselineBuild = true

        /** How long the baseline build may take before the debugger attaches anyway. */
        const val BASELINE_MS = 600_000

        /**
         * B19: builds every module of the project (the IDE's own compile, incremental, so a second debug session is a no-op) and waits for it. Off the EDT.
         * A build with errors does not stop the debug start: the user may be debugging the very code that does not compile, and what failed is the build's own
         * message. Answers whether the build ran clean; false also when there was nothing to build or the build did not finish in [BASELINE_MS].
         */
        fun buildBaseline(project: Project): Boolean {
            if (!baselineBuild || project.isDisposed || ModuleManager.getInstance(project).modules.isEmpty()) return false
            return try {
                val r = ProjectTaskManager.getInstance(project).buildAllModules().blockingGet(BASELINE_MS)
                (r != null && !r.hasErrors() && !r.isAborted).also { if (!it) LOG.warn("the build before the debug session ended with errors or did not finish: ${r?.let { x -> "errors ${x.hasErrors()}, aborted ${x.isAborted}" } ?: "timeout"}") }
            } catch (e: Exception) {
                LOG.warn("the build before the debug session failed: $e")
                false
            }
        }
    }
}
