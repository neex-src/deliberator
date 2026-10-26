package com.neex.deliberator.daemon

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Path

/**
 * The one daemon connection of the IDE process (application level). Every other service asks it for
 * the client: `DaemonService.getInstance().client()` from a background thread (never the EDT), then
 * `client.start(op, args, ...)` for streaming calls or `client.call(op, args)` for plain ones.
 *
 * [ensure] finds or starts the daemon on the IDE's own `java.home`, checks the handshake and
 * replaces an older daemon once (rules of [DaemonLauncher]); it validates with a `hello` at most
 * once per [HELLO_EVERY_MS] (a local socket, about a millisecond, but `ensure` is synchronized and
 * the tool window, the explorer and the editor all call it), so a daemon that died or was restarted
 * by `dlb` is noticed within seconds. The home is the setting, else `$DELIBERATOR_HOME`, else the
 * XDG-style default. The IDE never kills the daemon except through `daemon.stop` in that
 * replacement; it outlives the IDE.
 */
@Service(Service.Level.APP)
class DaemonService {
    @Volatile private var last: DaemonConnection? = null
    @Volatile private var lastHelloAt: Long = 0

    fun home(): Path = DaemonHome.guardUnderTest(
        DaemonHome.resolve(DeliberatorSettings.getInstance().state.home, System.getenv(), System.getProperty("user.home")),
        System.getProperty("deliberator.test.homes"))

    fun jar(): Path? = DaemonHome.resolveJar(
        System.getProperty("deliberator.jar"), System.getenv(),
        com.intellij.openapi.application.PathManager.getJarPathForClass(DaemonService::class.java)?.let { Path.of(it).parent },
    )

    /** `-Ddeliberator.daemon.args` (words) is appended to the daemon's command line; the test task sets `--idle-minutes 1`. */
    fun launcher(): DaemonLauncher = DaemonLauncher(
        System.getProperty("java.home"), jar(),
        System.getProperty("deliberator.daemon.args")?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList(),
    )

    /** Finds or starts the daemon and returns the checked connection. Blocking: call off the EDT. */
    @Synchronized
    fun ensure(): DaemonConnection {
        val app = ApplicationManager.getApplication()
        check(app == null || !app.isDispatchThread) { "DaemonService.ensure() blocks; call it from a background thread" }
        val home = home()
        return ClosedHomes.whileOpen(home) { connect(home) }
    }

    private fun connect(home: Path): DaemonConnection {
        val l = launcher()
        val prev = last
        if (prev != null && prev.home == home) {
            val now = System.currentTimeMillis()
            // the connection checked a moment ago is good enough, unless a request failed to reach the daemon since then
            if (now - lastHelloAt < HELLO_EVERY_MS && prev.client.unreachableAt < lastHelloAt) return prev
            // still the same daemon? one hello, no restart logic needed when it answers compatibly
            val h = try { prev.client.hello(l.jarVersion()) } catch (_: DaemonException) { null }
            if (h != null && h.compatible != false) return DaemonConnection(prev.client, h, home, false, false).also { last = it; lastHelloAt = now }
        }
        return l.connectCompatible(home).also {
            last = it
            lastHelloAt = System.currentTimeMillis()
            LOG.info("daemon ${it.hello.version} pid ${it.hello.pid} at ${it.hello.endpoint} started=${it.started} restarted=${it.restarted}")
        }
    }

    /** Forget the checked connection: the next [ensure] says hello again (tests, "daemon replaced"). */
    fun forget() { last = null; lastHelloAt = 0 }

    fun client(): DaemonClient = ensure().client

    companion object {
        const val PLUGIN_ID = "com.neex.deliberator"
        /** How long a checked connection is trusted without another `hello`. */
        const val HELLO_EVERY_MS = 5_000L
        private val LOG = logger<DaemonService>()
        fun getInstance(): DaemonService = ApplicationManager.getApplication().getService(DaemonService::class.java)
    }
}
