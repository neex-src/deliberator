package com.neex.deliberator.config

import com.google.gson.JsonObject
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.ui.Ui
import com.neex.deliberator.workspace.WorkspaceService
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import javax.swing.JComponent

/** The text of the views: a parameter's cached snapshot entry, and a whole chain. Pure functions of the op result and a clock (principle 3). */
object ConfigText {
    fun age(fetchedAt: String?, now: Instant = Instant.now()): String {
        val t = Chain.parseInstant(fetchedAt) ?: return "age unknown"
        val d = Duration.between(t, now).coerceAtLeast(Duration.ZERO)
        return when {
            d.toMinutes() < 1 -> "${d.seconds} s ago"
            d.toHours() < 1 -> "${d.toMinutes()} min ago"
            d.toDays() < 1 -> "${d.toHours()} h ${d.toMinutes() % 60} min ago"
            else -> "${d.toDays()} d ${d.toHours() % 24} h ago"
        }
    }

    private fun StringBuilder.kv(k: String, v: Any?) { if (v != null && v.toString().isNotEmpty()) append("  ").append((k + ":").padEnd(14)).append(v).append('\n') }

    /** The parameter view. [p] is null when the chain no longer knows it. */
    fun parameter(service: String, env: String, p: Param?, chain: Chain?, now: Instant = Instant.now()): String {
        val sb = StringBuilder()
        if (p == null) return "Parameter not in the chain of $service ($env).\nRefresh the snapshot (Deliberator | Config | Refresh Snapshot) or edit the placeholder.\n"
        sb.append("Parameter ").append(p.name ?: "?").append("  (cached snapshot entry, read-only)\n\n")
        sb.kv("service", service); sb.kv("environment", env)
        sb.kv("kind", p.kind); sb.kv("status", p.status); sb.kv("reason", p.reason); sb.kv("hint", p.hint)
        sb.kv("json key", p.jsonKey)
        when {
            p.secure -> sb.kv("value", "(SecureString: the snapshot holds no value)")
            p.value != null -> sb.kv("value", p.value)
            p.kind == "secret" -> sb.kv("value", "(a secret's value is never fetched)")
        }
        p.metadata?.let { m ->
            sb.append("  metadata:\n")
            m.entrySet().forEach { (k, v) -> sb.append("    ").append((k + ":").padEnd(18)).append(if (v.isJsonPrimitive) v.asString else v.toString()).append('\n') }
        }
        val at = p.fetchedAt ?: chain?.snapshot?.fetchedAt
        sb.append('\n')
        sb.kv("fetched-at", if (at == null) "no snapshot yet (Refresh fetches one)" else "$at  (${age(at, now)})")
        sb.kv("reference", p.ref)
        sb.kv("snapshot file", chain?.snapshot?.path)
        return sb.toString()
    }

    /** The whole chain of one service in one environment. */
    fun chain(c: Chain, now: Instant = Instant.now()): String {
        val sb = StringBuilder()
        sb.append("Config chain of service ").append(c.service).append(" in ").append(c.env).append("  (config.chain, read-only)\n\n")
        sb.kv("module", c.module)
        sb.kv("snapshot", if (c.snapshot.present) "${c.snapshot.fetchedAt}  (${age(c.snapshot.fetchedAt, now)})" else "none")
        sb.kv("snapshot file", c.snapshot.path)
        sb.kv("hint", c.hint)
        sb.append("\nPlaceholders\n")
        if (c.entries.isEmpty()) sb.append("  (none)\n")
        for (e in c.entries) {
            sb.append("  ").append("\${").append(e.placeholder).append(if (e.default != null) ":" + e.default else "").append("}  ")
                .append(short(e.file)).append(':').append(e.line ?: 0).append("  ").append(e.property ?: "").append('\n')
            val t = e.taskdef
            when {
                e.internal -> sb.append("      internal: a property of these files\n")
                t == null -> sb.append("      no task definition entry").append(if (e.usesDefault) " (resolved by its default)" else "").append('\n')
                else -> {
                    sb.append("      ").append(t.kind).append(' ').append(t.name).append("  (").append(t.match).append(if (t.adopted) ", adopted" else ", only declared").append(")\n")
                    if (t.file != null) sb.append("      template: ").append(short(t.file)).append(':').append(t.line).append("  via ").append(t.via).append('\n')
                    t.source?.let { sb.append("      declared: ").append(short(it.file)).append(':').append(it.line).append("  ").append(it.expr).append('\n') }
                    (t.value ?: t.valueFrom)?.let { sb.append("      ").append(if (t.kind == "env") "value: " else "valueFrom: ").append(it).append('\n') }
                }
            }
            e.parameter?.let { p ->
                sb.append("      parameter: ").append(p.kind).append(' ').append(p.name).append("  ").append(p.status)
                p.reason?.let { sb.append(" - ").append(it) }
                p.fetchedAt?.let { sb.append("  (fetched ").append(age(it, now)).append(')') }
                sb.append('\n')
            }
        }
        problems(sb, "Broken", c.broken)
        problems(sb, "Unresolved", c.unresolved)
        sb.append("\nExtra (adopted entries no placeholder uses)\n")
        if (c.extra.isEmpty()) sb.append("  (none)\n")
        for (e in c.extra) sb.append("  ").append(e.taskdef?.kind).append(' ').append(e.taskdef?.name).append("  ")
            .append(e.parameter?.let { "${it.name} ${it.status}" } ?: "").append('\n')
        return sb.toString()
    }

    private fun problems(sb: StringBuilder, title: String, ps: List<Problem>) {
        sb.append('\n').append(title).append('\n')
        if (ps.isEmpty()) sb.append("  (none)\n")
        for (p in ps) sb.append("  ").append(p.kind).append(": ").append(p.reason).append("  [")
            .append(p.entry.placeholder ?: p.entry.taskdef?.name).append(if (p.entry.file != null) " at ${short(p.entry.file)}:${p.entry.line}" else "").append("]\n")
    }

    private fun short(path: String?) = path?.substringAfterLast('/') ?: "?"
}

/**
 * The text views of the config chain, opened as read-only in-memory files (the IDE never writes the store; the snapshot's own text is
 * at `snapshot.path`, which every view names). A view opens from the cached chain at once; when a load brings new facts every open
 * view is rendered again in place. Principle 2: the age is on the page, Refresh is one action.
 */
@Service(Service.Level.PROJECT)
class ConfigViews(private val project: Project) : Disposable {
    private class View(val file: LightVirtualFile, val service: String, val env: String, val param: Param?)

    private val views = ConcurrentHashMap<String, View>()

    init {
        ConfigChains.getInstance(project).addListener(this) { rerender() }
    }

    override fun dispose() { views.clear() }

    /** Opens (or focuses) the view of [param]. EDT. */
    fun openParameter(service: String, env: String, param: Param): VirtualFile {
        val key = "p|$service|$env|${param.kind}|${param.name}"
        val v = views.getOrPut(key) {
            val chain = ConfigChains.getInstance(project).cached(service, env)
            View(newFile("${(param.name ?: "parameter").trim('/').replace('/', '_')} ($env snapshot).txt", ConfigText.parameter(service, env, param, chain)), service, env, param)
        }
        render(v)
        Ui.openEditor(project, v.file, 0)
        return v.file
    }

    /** Opens (or focuses) the whole chain of [chain]'s service. EDT. */
    fun openChain(chain: Chain): VirtualFile {
        val v = views.getOrPut("c|${chain.service}|${chain.env}") { View(newFile("config chain ${chain.service} (${chain.env}).txt", ConfigText.chain(chain)), chain.service, chain.env, null) }
        render(v)
        Ui.openEditor(project, v.file, 0)
        return v.file
    }

    fun serviceOf(file: VirtualFile): String? = views.values.firstOrNull { it.file == file }?.service

    /** Forgets every view (tests; the open editors are the caller's to close). */
    fun reset() { views.clear() }

    fun isView(file: VirtualFile): Boolean = views.values.any { it.file == file }

    /** The text a view shows now (tests). */
    fun textOf(file: VirtualFile): String? = views.values.firstOrNull { it.file == file }?.let { text(it) }

    fun rerender() { views.values.forEach { render(it) } }

    private fun text(v: View): String {
        val chain = ConfigChains.getInstance(project).cached(v.service, v.env)
        return if (v.param != null) {
            val p = chain?.parameters()?.firstOrNull { it.kind == v.param.kind && it.name == v.param.name }
            ConfigText.parameter(v.service, v.env, p ?: if (chain == null) v.param else null, chain)
        } else chain?.let { ConfigText.chain(it) } ?: "No chain loaded for ${v.service} (${v.env}).\n"
    }

    private fun render(v: View) {
        val t = text(v)
        val doc = FileDocumentManager.getInstance().getCachedDocument(v.file)
        if (doc == null) {
            v.file.isWritable = true
            v.file.setContent(this, t, false)
            v.file.isWritable = false
        } else if (doc.text != t) {
            ApplicationManager.getApplication().runWriteAction {
                v.file.isWritable = true
                doc.setReadOnly(false)
                doc.setText(t)
                v.file.isWritable = false
            }
        }
        EditorNotifications.getInstance(project).updateNotifications(v.file)
    }

    private fun newFile(name: String, text: String): LightVirtualFile =
        LightVirtualFile(name, PlainTextFileType.INSTANCE, text).also { it.isWritable = false }

    /** The parameter this view file shows, when it is a parameter view. */
    fun paramOf(file: VirtualFile): Pair<String, Param>? = views.values.firstOrNull { it.file == file }?.let { v -> v.param?.let { v.env to it } }

    companion object {
        fun getInstance(project: Project): ConfigViews = project.getService(ConfigViews::class.java)
    }
}

/** The banner above a view: how old the snapshot is, and Refresh. */
class ConfigViewBanner : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val views = ConfigViews.getInstance(project)
        if (!views.isView(file)) return null
        val (env, p) = views.paramOf(file) ?: return null
        val chain = ConfigChains.getInstance(project).cached(views.serviceOf(file) ?: return null, env)
        val at = p.fetchedAt ?: chain?.snapshot?.fetchedAt
        val text = if (at == null) "No snapshot of $env yet." else "Cached snapshot of $env, fetched ${ConfigText.age(at)}."
        return Function { ed ->
            EditorNotificationPanel(ed, EditorNotificationPanel.Status.Info).apply {
                this.text = text
                createActionLabel("Refresh") { ConfigRefresh.run(project) }
            }
        }
    }
}

/** `config.refresh` for the selected environment, and what follows it. */
object ConfigRefresh {
    /** Blocking, background threads only: asks the daemon, then marks every cached chain stale. Returns the op result. */
    fun refreshNow(project: Project): JsonObject {
        val svc = WorkspaceService.getInstance(project)
        try {
            return svc.call("config.refresh", JsonObject().apply { addProperty("env", svc.selectedEnv) }).resultObject()
        } finally {
            ConfigChains.getInstance(project).invalidate()
        }
    }

    fun run(project: Project) {
        var result: JsonObject? = null
        Ui.background(project, "Deliberator: config refresh (${WorkspaceService.getInstance(project).selectedEnv})", { result = refreshNow(project) }, {
            result?.let { r ->
                val errors = r.get("errors")?.takeIf { it.isJsonArray && it.asJsonArray.size() > 0 || it.isJsonObject && it.asJsonObject.size() > 0 }
                Ui.notify(project, "Deliberator config refresh",
                    "${r.get("parameters")?.asString ?: "?"} parameters, ${r.get("secrets")?.asString ?: "?"} secrets fetched into ${r.get("path")?.asString ?: "the snapshot"}" +
                        (errors?.let { "\nsome sections failed: $it" } ?: ""), if (errors != null) NotificationType.WARNING else NotificationType.INFORMATION)
            }
        })
    }
}
