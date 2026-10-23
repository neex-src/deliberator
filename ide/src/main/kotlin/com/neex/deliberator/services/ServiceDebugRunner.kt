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
import com.neex.deliberator.daemon.DaemonException

/**
 * Debug for a "Deliberator Service" configuration (loaded with the java plugin only): `service.start` first, off the EDT, because the debugger
 * needs the JDWP host port of the started container; then the platform's own debug start, which takes our state as a `RemoteState`
 * ([ServiceRunState.getRemoteConnection]) and attaches. "Reload Changed Classes" is the platform's, over that same JDWP connection.
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
}
