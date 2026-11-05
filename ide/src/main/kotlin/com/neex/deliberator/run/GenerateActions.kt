package com.neex.deliberator.run

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.icons.AllIcons
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.scn.ScnFile
import com.neex.deliberator.scn.ScnStep
import com.neex.deliberator.ui.Ui
import com.neex.deliberator.workspace.WorkspaceService

/**
 * Prose becomes steps. The `### ai <name>` block holds the prose; Generate sends the buffer to the daemon
 * (`scenario.generate`), which asks the workspace's model and answers the region of lines (marker, steps, end marker). This
 * file only places it: `scenario.parse` of the buffer as it is NOW tells where the block's region is (or where its prose
 * ends), so an edit made while the model worked does not move it. One undoable write; nothing is saved.
 */
object AiBlocks {
    const val KIND = "ai"

    /** The `ai` step the caret is in, or in the region of generated steps right under it (between `#>` and `#< end`). */
    fun at(file: ScnFile, text: CharSequence, offset: Int): ScnStep? {
        val here = file.stepAt(offset) ?: return null
        if (here.kind == KIND) return here
        val lines = text.toString().split("\n")
        val caretLine = file.lineOf(offset)
        var open = -1
        var i = caretLine
        while (i >= 0) {   // 0-based; the nearest marker above or at the caret
            val l = lines.getOrNull(i) ?: ""
            if (l.startsWith("#<")) return null
            if (l.startsWith("#>")) { open = i; break }
            i--
        }
        if (open < 0) return null
        return file.steps.lastOrNull { it.kind == KIND && it.startLine <= open }
    }

    /** The name of an ai block: the word after `ai` on its head line. */
    fun name(step: ScnStep): String? = step.headText.trim().split(Regex("\\s+")).drop(1).firstOrNull()

    /** Lines (1-based, inclusive) to replace, or the line after which to insert, in [parsed] (`scenario.parse`) for block [name]. */
    class Place(val replaceFrom: Int?, val replaceTo: Int?, val insertAfter: Int?)

    fun place(parsed: JsonObject, name: String): Place? {
        val steps = parsed.getAsJsonArray("steps") ?: return null
        for (e in steps) {
            val s = e as? JsonObject ?: continue
            if (s.get("kind")?.asString != KIND || s.get("name")?.takeIf { it.isJsonPrimitive }?.asString != name) continue
            val ai = s.getAsJsonObject("ai") ?: return null
            val region = ai.getAsJsonObject("region")
            return if (region != null) Place(region.get("from-line").asInt, region.get("to-line").asInt, null)
            else Place(null, null, ai.get("prose-end-line").asInt)
        }
        return null
    }

    /** The text with [region] placed per [place] (the same rule as core's `splice`). */
    fun splice(text: String, place: Place, region: List<String>): String {
        val lines = text.split("\n")
        val out = ArrayList<String>()
        if (place.replaceFrom != null && place.replaceTo != null) {
            out += lines.subList(0, place.replaceFrom - 1)
            out += region
            out += lines.subList(place.replaceTo, lines.size)
        } else {
            val after = place.insertAfter!!
            out += lines.subList(0, minOf(after, lines.size))
            out += ""
            out += region
            val next = lines.getOrNull(after)
            if (next != null && next.isNotBlank()) out += ""
            out += lines.subList(minOf(after, lines.size), lines.size)
        }
        return out.joinToString("\n")
    }
}

/** What the generate call of one block is doing, per file and block name: one at a time. */
class GenerateRun(val project: Project, val file: VirtualFile, val document: com.intellij.openapi.editor.Document, val name: String) {
    private val svc = WorkspaceService.getInstance(project)

    fun start() {
        val key = file.path + "#" + name
        if (!RUNNING.add(key)) {
            Ui.notify(project, "Generate", "Steps for $name are already being generated.", NotificationType.WARNING)
            return
        }
        val text = document.text
        object : Task.Backgroundable(project, "Deliberator: writing steps for $name", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    ScnRunner.getInstance(project).ensureSession(svc)
                    val args = JsonObject().apply {
                        addProperty("path", file.path)
                        addProperty("name", name)
                        addProperty("text", text)
                        if (svc.envChosen) addProperty("env", svc.selectedEnv)
                    }
                    val call = svc.start("scenario.generate", args, true)
                    val canceller = ApplicationManager.getApplication().executeOnPooledThread {
                        while (!call.terminal.isDone) { if (indicator.isCanceled) { call.cancel(); break }; Thread.sleep(100) }
                    }
                    val t = try { call.await() } finally { canceller.cancel(true) }
                    if (!t.ok) {
                        throw DaemonException(t.errorMessage ?: "scenario.generate failed", t.errorType ?: "daemon/error", t.error?.get("hint")?.takeIf { it.isJsonPrimitive }?.asString, null, t.error)
                    }
                    place(t.result as JsonObject)
                } catch (e: DaemonException) {
                    Ui.notify(project, "Generate $name", e.message + (e.hint?.let { "\n$it" } ?: ""), NotificationType.ERROR)
                } catch (e: Throwable) {
                    if (!indicator.isCanceled) Ui.notify(project, "Generate $name", e.toString(), NotificationType.ERROR)
                } finally {
                    RUNNING.remove(key)
                }
            }
        }.queue()
    }

    /** Off the EDT: where is the block now, then one write on the EDT. */
    private fun place(result: JsonObject) {
        val region = (result.get("region") as? JsonArray)?.map { it.asString } ?: emptyList()
        val parsed = svc.call("scenario.parse", JsonObject().apply {
            addProperty("path", file.path)
            addProperty("text", ApplicationManager.getApplication().let { var s = ""; it.invokeAndWait({ s = document.text }, ModalityState.nonModal()); s })
        }).resultObject()
        val where = AiBlocks.place(parsed, name)
        if (where == null) {
            Ui.notify(project, "Generate $name", "The ai block $name is gone from the file; the steps are in ${result.getAsJsonObject("paths")?.get("generated")?.asString}.", NotificationType.WARNING)
            return
        }
        ApplicationManager.getApplication().invokeAndWait({
            WriteCommandAction.runWriteCommandAction(project, "Generate Steps for $name", null, Runnable {
                document.setText(AiBlocks.splice(document.text, where, region))
            })
        }, ModalityState.nonModal())
        val marker = result.get("marker")?.asString ?: ""
        val paths = result.getAsJsonObject("paths")
        val n = NotificationGroupManager.getInstance().getNotificationGroup("Deliberator")
            .createNotification("Steps written for $name", marker.removePrefix("#> "), NotificationType.INFORMATION)
        paths?.get("universal")?.asString?.let { p -> n.addAction(openAction("Open universal.json", p)) }
        paths?.get("summary")?.asString?.let { p -> n.addAction(openAction("Open generate.edn", p)) }
        n.notify(project)
    }

    private fun openAction(text: String, path: String) = object : NotificationAction(text) {
        override fun actionPerformed(e: AnActionEvent, notification: Notification) { Ui.openFileLater(project, path) }
    }

    companion object {
        private val RUNNING = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fun isRunning(file: VirtualFile, name: String) = (file.path + "#" + name) in RUNNING
    }
}

/** `deliberator.scenario.generate`: the ai block at the caret (or its generated steps) gets its steps written. */
class GenerateStepsAction : ScnEditorAction() {
    override fun enabled(e: AnActionEvent): Boolean {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return false
        val psi = e.getData(CommonDataKeys.PSI_FILE) as? ScnFile ?: return false
        return AiBlocks.at(psi, editor.document.charsSequence, editor.caretModel.offset) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val v = viewOf(e) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psi = e.getData(CommonDataKeys.PSI_FILE) as? ScnFile ?: return
        val step = AiBlocks.at(psi, editor.document.charsSequence, editor.caretModel.offset)
        val name = step?.let { AiBlocks.name(it) }
        if (name == null) {
            Ui.notify(v.project, "Generate", "Name the ai block: ### ai <name> and the prose under it.", NotificationType.WARNING)
            return
        }
        v.generate(name)
    }
}

/** `deliberator.universal.open`: writes the universal JSON of the selected environment (`workspace.universal`, no model) and opens it. */
class OpenUniversalAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val p = e.project
        e.presentation.isEnabledAndVisible = p != null && WorkspaceService.getInstance(p).ws != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        open(project)
    }

    companion object {
        fun open(project: Project) {
            val svc = WorkspaceService.getInstance(project)
            var path: String? = null
            Ui.background(project, "Deliberator: writing universal.json", {
                path = svc.call("workspace.universal", JsonObject().apply { addProperty("env", svc.selectedEnv) })
                    .resultObject().get("path")?.asString
            }, {
                val p = path
                if (p != null) Ui.openFileLater(project, p)
            })
        }
    }
}

/** The gutter icon of an `ai` head: Generate (and the universal JSON) instead of the run actions. */
class AiGutterIcon(private val view: RunView, val step: Int, private val name: String?, private val busy: Boolean) :
    com.intellij.openapi.editor.markup.GutterIconRenderer(), DumbAware {
    override fun getIcon() = if (busy) AllIcons.Actions.Suspend else AllIcons.Actions.IntentionBulb
    override fun getTooltipText() = if (busy) "Writing steps for $name..." else "Generate steps from this prose (a model writes them, Deliberator checks them)"
    override fun getAlignment() = Alignment.LEFT
    override fun isNavigateAction() = true
    override fun equals(other: Any?) = other is AiGutterIcon && other.step == step && other.name == name && other.busy == busy
    override fun hashCode() = step * 31 + (name?.hashCode() ?: 0) + if (busy) 1 else 0

    override fun getClickAction(): AnAction = object : AnAction(), DumbAware {
        override fun actionPerformed(e: AnActionEvent) {
            val n = name
            if (n == null) { Ui.notify(view.project, "Generate", "Name the ai block: ### ai <name>.", NotificationType.WARNING); return }
            if (!busy) view.generate(n)
        }
    }
}
