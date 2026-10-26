/**
 * The run configuration type "Deliberator Service": Run = `service.start`, then the container log in the console; Stop = `service.stop`.
 * Debug is [ServiceDebugRunner]'s (it needs the java plugin); the state here is also a `RemoteState` so the platform's debugger attaches to the
 * JDWP host port `service.start` reported.
 *
 * Extension points for piece 08b (the secrets checkbox): [ServiceRunEditor.extraRows] is an empty panel under the two settings, and
 * [ServiceRunConfiguration.startArgs] is the one place the args of `service.start` are built (add `secrets-from`/`confirm` there; the choice
 * itself must stay out of [ServiceRunOptions], it is never persisted).
 */
package com.neex.deliberator.services

import com.google.gson.JsonObject
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RemoteConnection
import com.intellij.execution.configurations.RemoteState
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import com.neex.deliberator.daemon.DaemonClient
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.run.ScnRunner
import com.neex.deliberator.workspace.WorkspaceService
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

/** Persisted settings: the service and the environment its log target is read in (blank = the project's selected environment). */
class ServiceRunOptions : RunConfigurationOptions() {
    var serviceName by string("")
    var envName by string("")
}

class ServiceRunConfigurationType : ConfigurationTypeBase(ID, "Deliberator Service", "Start a Deliberator service and follow its log", AllIcons.RunConfigurations.Application) {
    init { addFactory(ServiceRunFactory(this)) }

    companion object {
        const val ID = "DeliberatorServiceRunConfiguration"
    }
}

class ServiceRunFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun getId() = "Deliberator Service"
    override fun createTemplateConfiguration(project: Project): RunConfiguration = ServiceRunConfiguration(project, this, "Deliberator Service")
    override fun getOptionsClass() = ServiceRunOptions::class.java
}

class ServiceRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) : RunConfigurationBase<ServiceRunOptions>(project, factory, name) {
    override fun getOptions() = super.getOptions() as ServiceRunOptions

    var serviceName: String
        get() = options.serviceName ?: ""
        set(v) { options.serviceName = v }

    var envName: String
        get() = options.envName ?: ""
        set(v) { options.envName = v }

    /** The environment the log is read in: the configured one, else the project's selected one. */
    fun effectiveEnv(): String = envName.ifBlank { WorkspaceService.getInstance(project).selectedEnv }

    /**
     * The environment whose REAL secret values the container should get, or null (the default). Deliberately not in [ServiceRunOptions]: never written
     * to the configuration file, gone when the IDE closes; every start with it asks the daemon's two confirmations again.
     */
    @Volatile var secretsFrom: String? = null

    override fun doCopyOptionsFrom(source: RunConfigurationBase<ServiceRunOptions>) {
        super.doCopyOptionsFrom(source)
        (source as? ServiceRunConfiguration)?.let { secretsFrom = it.secretsFrom }
    }

    /** The args of `service.start` beyond the name: `secrets-from` while the checkbox is on (`confirm` is [ServiceOps.start]'s business). */
    fun startArgs(): JsonObject = JsonObject().apply { secretsFrom?.takeIf { it.isNotBlank() }?.let { addProperty("secrets-from", it) } }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = ServiceRunEditor(project)

    override fun checkConfiguration() {
        if (serviceName.isBlank()) throw RuntimeConfigurationError("Choose the service to run")
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = ServiceRunState(environment, this)
}

/** Service name (a combo of the model's services, free text allowed) and environment, then the room [extraRows] for piece 08b. */
class ServiceRunEditor(private val project: Project) : SettingsEditor<ServiceRunConfiguration>() {
    private val service = com.intellij.openapi.ui.ComboBox<String>().apply { isEditable = true }
    private val env = JBTextField(16).apply { emptyText.text = "selected environment" }

    /** Rows under the two settings; piece 08b puts the "take secret values from" checkbox here. Empty on purpose. */
    val extraRows = JPanel(java.awt.BorderLayout())

    private val secrets = com.intellij.ui.components.JBCheckBox("Take secret values from environment:")
    private val secretsEnv = com.intellij.openapi.ui.ComboBox<String>()

    init {
        val model = WorkspaceService.getInstance(project).model
        model.services.forEach { service.addItem(it.name) }
        model.envs.forEach { secretsEnv.addItem(it.name) }
        secretsEnv.isEnabled = false
        secrets.addActionListener { secretsEnv.isEnabled = secrets.isSelected }
        extraRows.add(javax.swing.JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
            add(secrets); add(javax.swing.Box.createHorizontalStrut(6)); add(secretsEnv)
        }, java.awt.BorderLayout.NORTH)
        extraRows.add(com.intellij.ui.components.JBLabel("<html><small>Real values, fetched when the service starts: two confirmations every time. Never saved.</small></html>"), java.awt.BorderLayout.SOUTH)
    }

    override fun resetEditorFrom(s: ServiceRunConfiguration) {
        service.selectedItem = s.serviceName
        env.text = s.envName
        secrets.isSelected = s.secretsFrom != null
        secretsEnv.isEnabled = secrets.isSelected
        s.secretsFrom?.let { secretsEnv.selectedItem = it }
    }

    override fun applyEditorTo(s: ServiceRunConfiguration) {
        s.serviceName = (service.editor.item ?: service.selectedItem)?.toString()?.trim() ?: ""
        s.envName = env.text.trim()
        s.secretsFrom = if (secrets.isSelected) secretsEnv.selectedItem?.toString()?.trim()?.takeIf { it.isNotEmpty() } else null
    }

    override fun createEditor(): JComponent = panel {
        row("Service:") { cell(service) }
        row("Environment:") { cell(env) }
        row { cell(extraRows) }
    }

    internal fun setService(n: String) { service.selectedItem = n }

    /** Tests: tick the sandbox and pick the environment as a user would. */
    internal fun setSecrets(envName: String?) {
        secrets.isSelected = envName != null
        secretsEnv.isEnabled = envName != null
        if (envName != null) { secretsEnv.isEditable = true; secretsEnv.selectedItem = envName }
    }

    internal fun secretsEnabled() = secretsEnv.isEnabled
}

/** The started service's console of each running configuration, so the Reload action can write into it. */
@Service(Service.Level.PROJECT)
class ServiceConsoles {
    private val handlers = java.util.concurrent.ConcurrentHashMap<String, ServiceProcessHandler>()
    fun register(name: String, h: ServiceProcessHandler) { handlers[name] = h }
    fun unregister(name: String, h: ServiceProcessHandler) { handlers.remove(name, h) }

    /** The live handler of service [name], or null. */
    fun of(name: String): ServiceProcessHandler? = handlers[name]?.takeIf { !it.isProcessTerminated }

    companion object {
        fun getInstance(project: Project): ServiceConsoles = project.getService(ServiceConsoles::class.java)
    }
}

/**
 * Run: starts the service (`service.start`, events in the console), follows its log (one `logs.fetch {follow true}`, each line printed as the daemon streams it), and on Stop calls `service.stop`. Debug: the runner has already started it ([prestarted]); the handler only follows the log, and
 * Stop detaches (the service keeps running).
 */
class ServiceProcessHandler(
    private val project: Project,
    val service: String,
    private val env: String,
    private val startArgs: JsonObject,
    private val prestarted: Started?,
    private val debugSession: Boolean,
) : ProcessHandler() {
    private val stopped = AtomicBoolean(false)
    /** The follow in flight (cancelled by Stop). */
    @Volatile private var inFlight: DaemonClient.Call? = null
    /** The `service.start` in flight: the daemon does not abort a start, so a Stop waits for its answer and then stops what it started. */
    @Volatile private var starting: DaemonClient.Call? = null
    @Volatile var started: Started? = prestarted; private set
    private val pending = StringBuilder()
    private val record = StringBuilder()

    /** Everything printed so far (the last 200 KB), for tests and for a late reader. */
    @Synchronized fun transcript(): String = record.toString()

    /** Text for the console (before [startNotify] it is kept and flushed by [begin]). */
    @Synchronized fun print(line: String) {
        val text = if (line.endsWith("\n")) line else line + "\n"
        record.append(text)
        if (record.length > RECORD_MAX) record.delete(0, record.length - RECORD_MAX)
        if (startedNotify) notifyTextAvailable(text, ProcessOutputTypes.STDOUT) else pending.append(text)
    }

    @Volatile private var startedNotify = false

    @Synchronized private fun flush() { startedNotify = true; if (pending.isNotEmpty()) { notifyTextAvailable(pending.toString(), ProcessOutputTypes.STDOUT); pending.setLength(0) } }

    /** Starts the work on a pooled thread. */
    fun begin() {
        startNotify()
        flush()
        ServiceConsoles.getInstance(project).register(service, this)
        addProcessListener(object : com.intellij.execution.process.ProcessListener {
            override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) { ServiceConsoles.getInstance(project).unregister(service, this@ServiceProcessHandler) }
        })
        ApplicationManager.getApplication().executeOnPooledThread { work() }
    }

    private fun work() {
        val ops = ServiceOps.getInstance(project)
        val svc = WorkspaceService.getInstance(project)
        // the session the follow is kept in: asked for once, here, before anything starts (never from the follow loop)
        try {
            ScnRunner.getInstance(project).ensureSession(svc)
        } catch (e: DaemonException) {
            print("no session - " + e.readable())
            finish(1)
            return
        }
        if (stopped.get()) { finish(0); return }   // Stop came before anything started: nothing to start
        try {
            if (prestarted == null) {
                print("> service.start $service")
                val s = ops.start(service, startArgs, { ev -> print("  ${ev.name}${summary(ev.json)}") }) { starting = it }
                starting = null
                started = s
                print("service $service ${s.status}" + (s.container?.let { ", container $it" } ?: "") + (s.port?.let { ", port $it" } ?: "") + (s.jdwp?.let { ", JDWP $it" } ?: ""))
                startArgs.get("secrets-from")?.takeIf { it.isJsonPrimitive }?.asString?.let { printSecretsNote(it) }
            } else {
                print("service $service started by the debug session (JDWP ${prestarted.jdwp ?: "-"})")
                startArgs.get("secrets-from")?.takeIf { it.isJsonPrimitive }?.asString?.let { printSecretsNote(it) }
            }
        } catch (e: DaemonException) {
            starting = null
            print("service.start failed - " + e.readable())
            finish(1)
            return
        } catch (e: Exception) {
            starting = null
            print("service.start failed - ${e.message}")
            finish(1)
            return
        }
        if (stopped.get()) {
            // Stop came while the start was in flight (before the call was handed out, or the wait ran out): what the start made is stopped here
            print("stopped while starting: stopping the service again")
            print("> service.stop $service")
            try { ops.stop(service); print("service $service stopped") } catch (e: Exception) { print("service.stop failed - ${e.message}") }
            finish(0)
            return
        }
        tail()
    }

    private fun printSecretsNote(env: String) = print("!! the container got the REAL secret values of environment $env (both confirmations given); " +
        "the next plain start recreates it with the local ones")

    private fun summary(j: JsonObject): String {
        val parts = j.entrySet().filter { it.key != "id" && it.key != "event" && it.value.isJsonPrimitive }.joinToString(" ") { "${it.key}=${it.value.asString}" }
        return if (parts.isEmpty()) "" else " $parts"
    }

    /** Arrival time of the last log line shown (`logs.line.at`): where a re-follow after a failure starts. */
    @Volatile private var lastAt: String? = null

    /**
     * One `logs.fetch {follow true}` for the whole Run session: the daemon streams each line as a `logs.line` event, printed
     * the moment it arrives, and keeps ONE file. After a failure the follow starts again from the last line's time.
     */
    private fun tail() {
        val svc = WorkspaceService.getInstance(project)
        var failures = 0
        while (!stopped.get()) {
            try {
                val a = JsonObject().apply {
                    addProperty("target", ServiceOps.logTarget(service))
                    addProperty("env", env)
                    addProperty("since", lastAt ?: "5m")
                    addProperty("follow", true)
                }
                val call = svc.start("logs.fetch", a, true) { ev ->
                    if (ev.name == "logs.line") {
                        ev.json.get("text")?.takeIf { it.isJsonPrimitive }?.asString?.let { print(it) }
                        ev.json.get("at")?.takeIf { it.isJsonPrimitive }?.asString?.let { lastAt = it }
                    }
                }
                inFlight = call
                val t = call.await()
                if (!t.ok) throw DaemonException(t.errorMessage ?: "logs.fetch failed", t.errorType ?: "daemon/error", t.error?.get("hint")?.takeIf { it.isJsonPrimitive }?.asString)
                if (!stopped.get()) Thread.sleep(retryMs)   // a follow only ends when cancelled; if it ended anyway, follow again
            } catch (e: java.util.concurrent.CancellationException) {
                break
            } catch (e: DaemonException) {
                if (stopped.get()) break
                failures++
                print("log tail: ${e.readable()}")
                if (failures >= 5) { print("log tail stopped after $failures failures; the service keeps running"); return }
                Thread.sleep(retryMs)
            } catch (e: Exception) {
                if (stopped.get()) break
                print("log tail: ${e.message}")
                return
            } finally {
                inFlight = null
            }
        }
    }

    private fun finish(code: Int) { if (!isProcessTerminated && !isProcessTerminating) notifyProcessTerminated(code) }

    /**
     * Stop: the follow is cancelled; a `service.start` still in flight is waited for (the daemon does not abort a start: a stop sent
     * meanwhile would end before the container appears and leave it running), then `service.stop`. Whatever the stop answers, the
     * process ends (never "terminating" for ever).
     */
    override fun destroyProcessImpl() {
        stopped.set(true)
        inFlight?.cancel()
        val start = starting
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (start != null) {
                    print("stop requested while the service starts: waiting for service.start to answer")
                    runCatching { start.terminal.get(START_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
                }
                print("> service.stop $service")
                val r = ServiceOps.getInstance(project).stop(service)
                print("service $service ${r.get("status")?.asString ?: "stopped"}")
                notifyProcessTerminated(0)
            } catch (e: DaemonException) {
                print("service.stop failed - " + e.readable())
                notifyProcessTerminated(1)
            } catch (e: Exception) {
                print("service.stop failed - $e")
                notifyProcessTerminated(1)
            }
        }
    }

    override fun detachProcessImpl() {
        stopped.set(true)
        inFlight?.cancel()
        print("debugger detached; the service keeps running (stop it from the Services tab)")
        notifyProcessDetached()
    }

    /** A debug session's Stop detaches; a Run session's Stop stops the service. */
    override fun detachIsDefault() = debugSession

    override fun getProcessInput(): OutputStream? = null

    companion object {
        @Volatile internal var retryMs = 5000L
        private const val RECORD_MAX = 200_000
        /** How long a Stop waits for a `service.start` in flight (build + create + health) before stopping anyway. */
        private const val START_WAIT_MS = 180_000L
    }
}

/** The state of one run: console + handler; [getRemoteConnection] answers once the service is started (the debug runner starts it first). */
class ServiceRunState(private val env: ExecutionEnvironment, private val config: ServiceRunConfiguration) : RemoteState {
    /**
     * Set by the debug runner after its own `service.start`. Kept on the environment, not in this object: the platform's debug start asks
     * `environment.state` again and gets a new [ServiceRunState].
     */
    var prestarted: Started?
        get() = env.getUserData(PRESTARTED)
        set(v) = env.putUserData(PRESTARTED, v)
    var debug: Boolean
        get() = env.getUserData(DEBUG) == true
        set(v) = env.putUserData(DEBUG, v)
    @Volatile var handler: ServiceProcessHandler? = null; private set

    fun startArgs(): JsonObject = config.startArgs()

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val project = env.project
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply { addFilter(CompileLinkFilter(project)) }.console
        val h = ServiceProcessHandler(project, config.serviceName, config.effectiveEnv(), startArgs(), prestarted, debug)
        handler = h
        console.attachToProcess(h)
        h.begin()
        return DefaultExecutionResult(console, h)
    }

    /** `localhost:<host JDWP port from service.start>`; a service started without a JDWP port is an error naming the description key. */
    override fun getRemoteConnection(): RemoteConnection {
        val s = prestarted ?: throw ExecutionException("the service ${config.serviceName} is not started yet")
        val port = s.jdwp ?: throw ExecutionException("service ${config.serviceName} has no JDWP port: add :jdwp to the description of ${config.serviceName} and restart it")
        return RemoteConnection(true, "localhost", port, false)
    }

    private companion object {
        val PRESTARTED = com.intellij.openapi.util.Key.create<Started>("deliberator.service.prestarted")
        val DEBUG = com.intellij.openapi.util.Key.create<Boolean>("deliberator.service.debug")
    }
}

