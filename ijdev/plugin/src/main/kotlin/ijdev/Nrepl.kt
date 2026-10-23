package ijdev

import clojure.java.api.Clojure
import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.diagnostic.logger

/**
 * Boots Clojure on this plugin's class loader and hands over to `ijdev.boot/start!`, which runs the nREPL server.
 * Every REPL thread gets [UnionLoader] as its context class loader, so evaluated code sees the classes of all loaded plugins.
 */
object Nrepl {
    private val LOG = logger<Nrepl>()
    const val PLUGIN_ID = "ijdev.repl"

    val loader: UnionLoader by lazy { UnionLoader(Nrepl::class.java.classLoader) }

    @Volatile private var started = false

    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        Thread({ boot() }, "ijdev-nrepl-boot").apply { isDaemon = true }.start()
    }

    fun stop() {
        if (!started) return
        runCatching { withLoader { Clojure.`var`("ijdev.boot", "stop!").invoke() } }.onFailure { LOG.warn("ijdev: stop failed", it) }
        started = false
    }

    private fun boot() {
        try {
            withLoader {
                Clojure.`var`("clojure.core", "require").invoke(Clojure.read("ijdev.boot"))
                Clojure.`var`("ijdev.boot", "start!").invoke(loader)
            }
        }
        catch (e: Throwable) {
            LOG.error("ijdev: the nREPL did not start", e)
        }
    }

    private fun <T> withLoader(f: () -> T): T {
        val t = Thread.currentThread()
        val before = t.contextClassLoader
        t.contextClassLoader = loader
        try { return f() } finally { t.contextClassLoader = before }
    }
}

internal class StartOnAppStart : AppLifecycleListener {
    override fun appStarted() = Nrepl.start()
}

internal class PluginEvents : DynamicPluginListener {
    override fun pluginLoaded(pluginDescriptor: IdeaPluginDescriptor) {
        if (pluginDescriptor.pluginId.idString == Nrepl.PLUGIN_ID) Nrepl.start()
        Nrepl.loader.forget()
    }

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (pluginDescriptor.pluginId.idString == Nrepl.PLUGIN_ID) Nrepl.stop()
    }

    override fun pluginUnloaded(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) = Nrepl.loader.forget()
}
