package ijdev

import com.intellij.ide.plugins.PluginMainDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import java.net.URL
import java.util.Collections
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap

/**
 * Parent first ([own] = this plugin: Clojure, nREPL, the platform core), then every enabled plugin and content module, the
 * [preferred] plugin ids first. Hits and misses are cached by class name until a plugin is loaded or unloaded ([forget]).
 * The same walk as the IDE Scripting Console's AllPluginsLoader, kept here so the REPL does not depend on its internals.
 */
class UnionLoader(private val own: ClassLoader) : ClassLoader("ijdev-union", own) {
    @Volatile var preferred: List<String> = (System.getProperty("ijdev.plugins") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private val hits = ConcurrentHashMap<String, ClassLoader>()
    private val misses: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var cached: List<ClassLoader>? = null

    fun forget() {
        hits.clear(); misses.clear(); cached = null
    }

    /** The class loaders searched after [own], in order (plugin and content-module loaders, deduplicated). */
    fun loaders(): List<ClassLoader> = cached ?: compute().also { cached = it }

    private fun compute(): List<ClassLoader> {
        val plugins = PluginManagerCore.loadedPlugins
        val first = preferred.mapNotNull { id -> plugins.firstOrNull { it.pluginId.idString == id } }
        val ordered = first + (plugins - first.toSet())
        val out = LinkedHashSet<ClassLoader>()
        for (p in ordered) {
            p.pluginClassLoader?.let { out.add(it) }
            if (p is PluginMainDescriptor) for (m in p.contentModules) m.pluginClassLoader?.let { out.add(it) }
        }
        out.remove(own)
        return out.toList()
    }

    override fun findClass(name: String): Class<*> {
        hits[name]?.let { return it.loadClass(name) }
        if (name in misses) throw ClassNotFoundException(name)
        for (l in loaders()) {
            val c = try { l.loadClass(name) } catch (_: ClassNotFoundException) { null } catch (_: LinkageError) { null }
            if (c != null) {
                hits[name] = l
                return c
            }
        }
        misses.add(name)
        throw ClassNotFoundException(name)
    }

    override fun findResource(name: String): URL? {
        for (l in loaders()) l.getResource(name)?.let { return it }
        return null
    }

    override fun findResources(name: String): Enumeration<URL> {
        val all = ArrayList<URL>()
        for (l in loaders()) all.addAll(Collections.list(l.getResources(name)))
        return Collections.enumeration(all)
    }
}
