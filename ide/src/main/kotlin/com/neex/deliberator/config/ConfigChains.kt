package com.neex.deliberator.config

import com.google.gson.JsonObject
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.workspace.WorkspaceService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * What the editor knows about the configuration chain, without ever blocking the EDT or a read action on the daemon.
 *
 *  - [cached] / [serviceOf] / [chainOfFile] answer at once from memory (null when nothing is known yet).
 *  - [ensureChain] / [ensureIndex] ask for a (re)load: debounced ([debounceMs]), one per key, on a pooled thread; when the answer holds
 *    new facts the code analyzer restarts and the listeners hear about it.
 *  - [load] / [loadIndex] are the blocking forms (background threads, actions, tests).
 *  - [invalidate] marks everything stale: the old answer keeps serving until the new one is in (stale-while-revalidate), and every key
 *    asked for before is reloaded. Called by `config.refresh`, by every workspace model publish (the environment selector included),
 *    and when an application file is saved.
 *
 * The cache is per (workspace, service, environment); the environment is [WorkspaceService.selectedEnv].
 */
@Service(Service.Level.PROJECT)
class ConfigChains(private val project: Project) : Disposable {
    private data class Key(val ws: String, val service: String, val env: String)
    private class Slot(val chain: Chain, val stale: Boolean)

    private val slots = ConcurrentHashMap<Key, Slot>()
    private val interest = ConcurrentHashMap.newKeySet<Key>()
    private val pending = ConcurrentHashMap<Any, ScheduledFuture<*>>()
    @Volatile private var index: Pair<String, ServiceIndex>? = null
    @Volatile private var indexStale = true
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile var debounceMs: Long = 300
    /** Counts every load that finished (tests wait on it). */
    @Volatile var loads: Int = 0; private set
    /** Counts the `config.chain` loads alone. */
    @Volatile var chainLoads: Int = 0; private set

    init {
        WorkspaceService.getInstance(project).addListener(this) { invalidate() }
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
            override fun beforeDocumentSaving(document: com.intellij.openapi.editor.Document) {
                val f = FileDocumentManager.getInstance().getFile(document) ?: return
                if (Placeholders.isConfigFileName(f.name)) ApplicationManager.getApplication().executeOnPooledThread { invalidate() }
            }
        })
    }

    override fun dispose() { pending.values.forEach { it.cancel(false) }; pending.clear(); listeners.clear() }

    private fun env() = WorkspaceService.getInstance(project).selectedEnv
    private fun ws() = WorkspaceService.getInstance(project).ws

    fun addListener(parent: Disposable, l: () -> Unit) {
        listeners += l
        Disposer.register(parent) { listeners -= l }
    }

    // reading (never blocks) --------------------------------------------------------------------

    /** The chain of [service] for the selected environment as last loaded, else null. */
    fun cached(service: String, env: String = env()): Chain? = ws()?.let { slots[Key(it, service, env)]?.chain }

    /** The service owning [file] by the cached index, else null (the index is requested when missing). */
    fun serviceOf(file: String): String? {
        val w = ws() ?: return null
        val ix = index?.takeIf { it.first == w }?.second
        if (ix == null || indexStale) ensureIndex()
        return ix?.serviceOf(file)
    }

    /** Service and cached chain of the config file [file]; requests whatever is missing or stale. */
    fun chainOfFile(file: String): Pair<String, Chain?>? {
        val s = serviceOf(file) ?: return null
        ensureChain(s)
        return s to cached(s)
    }

    /** Services of the cached index (empty when not loaded). */
    fun services(): List<String> = index?.second?.services ?: emptyList()

    // loading ------------------------------------------------------------------------------------

    /** Loads the chain now (blocking; a daemon error is thrown). */
    fun load(service: String): Chain {
        val svc = WorkspaceService.getInstance(project)
        val w = svc.ws ?: throw DaemonException("this project is not bound to a workspace", "workspace/unbound")
        val e = svc.selectedEnv
        val r = svc.call("config.chain", JsonObject().apply { addProperty("name", service); addProperty("env", e) }).resultObject()
        val chain = Chain.parse(service, e, r)
        val k = Key(w, service, e)
        val before = slots.put(k, Slot(chain, false))?.chain
        interest += k
        loads++
        chainLoads++
        if (!chain.sameFacts(before)) changed()
        return chain
    }

    fun loadIndex(): ServiceIndex {
        val svc = WorkspaceService.getInstance(project)
        val w = svc.ws ?: throw DaemonException("this project is not bound to a workspace", "workspace/unbound")
        val services = svc.call("service.list").result
        val repos = svc.call("repo.list").result
        val ix = ServiceIndex.parse(services, repos)
        val before = index?.second
        index = w to ix
        indexStale = false
        loads++
        if (before == null || before.modules != ix.modules) changed()
        return ix
    }

    fun ensureChain(service: String) {
        val w = ws() ?: return
        val k = Key(w, service, env())
        interest += k
        if (slots[k]?.stale == false) return
        schedule(k) { try { load(service) } catch (e: DaemonException) { LOG.debug("config.chain $service: ${e.message}") } }
    }

    fun ensureIndex() {
        if (ws() == null) return
        schedule("index") { try { loadIndex() } catch (e: DaemonException) { LOG.debug("service index: ${e.message}") } }
    }

    private fun schedule(key: Any, work: () -> Unit) {
        if (pending.containsKey(key)) return
        val f = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            pending.remove(key)
            try { work() } catch (e: com.intellij.openapi.progress.ProcessCanceledException) { throw e } catch (e: Exception) { LOG.debug("config load: ${e.message}") }
        }, debounceMs, TimeUnit.MILLISECONDS)
        if (pending.putIfAbsent(key, f) != null) f.cancel(false)
    }

    /** Marks everything stale and reloads what was asked for before. */
    fun invalidate() {
        for ((k, s) in slots) slots[k] = Slot(s.chain, true)
        indexStale = true
        if (ws() == null) return
        ensureIndex()
        for (k in interest) if (k.ws == ws() && k.env == env()) ensureChain(k.service)
    }

    /** Forgets everything (tests, and a workspace switch). */
    fun reset() {
        pending.values.forEach { it.cancel(false) }; pending.clear()
        slots.clear(); interest.clear(); index = null; indexStale = true
    }

    /** True when nothing is being waited for (a load is neither scheduled nor running). Tests. */
    fun idle(): Boolean = pending.isEmpty()

    private fun changed() {
        val app = ApplicationManager.getApplication()
        app.invokeLater({
            if (project.isDisposed) return@invokeLater
            listeners.forEach { it() }
            DaemonCodeAnalyzer.getInstance(project).restart("Deliberator config chain")
        }, ModalityState.any())
    }

    companion object {
        private val LOG = logger<ConfigChains>()
        fun getInstance(project: Project): ConfigChains = project.getService(ConfigChains::class.java)
    }
}
