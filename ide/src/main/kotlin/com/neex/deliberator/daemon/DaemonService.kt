package com.neex.deliberator.daemon

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import java.nio.file.Path

/**
 * The one daemon connection of the IDE process (application level). Every other service asks it for
 * the client: `DaemonService.getInstance().client()` from a background thread (never the EDT), then
 * `client.start(op, args, ...)` for streaming calls or `client.call(op, args)` for plain ones.
 *
 * [ensure] finds or starts the daemon on the IDE's own `java.home`, checks the handshake and
 * replaces an older daemon once (rules of [DaemonLauncher]); it validates with a `hello` on every
 * call (a local socket, about a millisecond), so a daemon that died or was restarted by `dlb` is
 * noticed. The home is the setting, else `$DELIBERATOR_HOME`, else the XDG-style default. The IDE
 * never kills the daemon except through `daemon.stop` in that replacement; it outlives the IDE.
 */
@Service(Service.Level.APP)
class DaemonService {
    @Volatile private var last: DaemonConnection? = null

    fun home(): Path = DaemonHome.resolve(DeliberatorSettings.getInstance().state.home, System.getenv(), System.getProperty("user.home"))

    fun jar(): Path? = DaemonHome.resolveJar(
        System.getProperty("deliberator.jar"), System.getenv(),
        PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.pluginPath?.resolve("lib"),
    )

    fun launcher(): DaemonLauncher = DaemonLauncher(System.getProperty("java.home"), jar())

    /** Finds or starts the daemon and returns the checked connection. Blocking: call off the EDT. */
    @Synchronized
    fun ensure(): DaemonConnection {
        val app = ApplicationManager.getApplication()
        check(app == null || !app.isDispatchThread) { "DaemonService.ensure() blocks; call it from a background thread" }
        val home = home()
        val l = launcher()
        val prev = last
        if (prev != null && prev.home == home) {
            // still the same daemon? one hello, no restart logic needed when it answers compatibly
            val h = try { prev.client.hello(l.jarVersion()) } catch (_: DaemonException) { null }
            if (h != null && h.compatible != false) return prev.also { last = DaemonConnection(it.client, h, home, false, false) }
        }
        return l.connectCompatible(home).also {
            last = it
            LOG.info("daemon ${it.hello.version} pid ${it.hello.pid} at ${it.hello.endpoint} started=${it.started} restarted=${it.restarted}")
        }
    }

    fun client(): DaemonClient = ensure().client

    companion object {
        const val PLUGIN_ID = "com.neex.deliberator"
        private val LOG = logger<DaemonService>()
        fun getInstance(): DaemonService = ApplicationManager.getApplication().getService(DaemonService::class.java)
    }
}
