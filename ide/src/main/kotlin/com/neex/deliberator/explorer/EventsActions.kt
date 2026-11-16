package com.neex.deliberator.explorer

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.neex.deliberator.run.AwsStepEdits
import com.neex.deliberator.run.ScnEditorAction
import com.neex.deliberator.scn.ScnFile
import com.neex.deliberator.ui.Ui
import com.neex.deliberator.workspace.WorkspaceService

/**
 * Where a console link opens. The IDE hands it to [BrowserUtil] (the machine's default browser, or the one set in Settings | Tools | Web Browsers) UNLESS the command is
 * named: `DELIBERATOR_BROWSER` in the IDE's environment (or `-Ddeliberator.browser`) is a command line the URL is appended to, e.g.
 * `chromium --no-sandbox --in-process-gpu --user-data-dir=/some/dir`. the sandbox has no default browser: a clip sets it. The link may be a sign-in link (a secret): it is only ever
 * held in memory and passed to the browser, never logged, shown in a balloon or written down.
 */
object ConsoleBrowser {
    fun command(): List<String>? = (System.getenv("DELIBERATOR_BROWSER") ?: System.getProperty("deliberator.browser"))?.trim()?.takeIf { it.isNotEmpty() }?.split(Regex("\\s+"))

    /** The last way a link was opened, for tests: `browser command` or `default browser` (never the link). */
    @Volatile var lastOpenedWith: String? = null

    /** A hook for tests (receives the URL); null in the product. */
    @Volatile var opener: ((String) -> Unit)? = null

    fun open(url: String) {
        opener?.let { it(url); lastOpenedWith = "test"; return }
        val c = command()
        if (c != null) {
            ProcessBuilder(c + url).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            lastOpenedWith = c.first()
        } else { BrowserUtil.browse(url); lastOpenedWith = "default browser" }
    }
}

private fun Project.env() = WorkspaceService.getInstance(this).selectedEnv
private fun Project.remote() = WorkspaceService.getInstance(this).model.envs.firstOrNull { it.name == env() }?.kind == "remote"

/** `deliberator.explorer.console`: Open in AWS Console, on any explorer row of a remote environment. */
class OpenInConsoleAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val p = e.project
        e.presentation.isEnabledAndVisible = p != null && e.getData(EventsTree.CONSOLE) != null && p.remote()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val p = e.project ?: return
        val t = e.getData(EventsTree.CONSOLE) ?: return
        val env = p.env()
        Ui.background(p, "Deliberator: ${t.kind} ${t.name} in the AWS console", {
            val link = EventsOps.consoleLink(WorkspaceService.getInstance(p), env, t.kind, t.name)
            ConsoleBrowser.open(link.url)
            Ui.notify(p, "AWS console", "${t.kind} ${t.name}: opened " + (if (link.signedIn) "signed in (a 15 minute read-only session)" else "without a sign-in (the page asks for one)") + (link.note?.let { "\n$it" } ?: ""))
        })
    }
}

/** `deliberator.explorer.newEvent`: New Event for This Rule: an event that matches the rule's pattern as an `eventbridge put` step, at the caret of the scenario that is open (else in the explorer's file). */
class NewEventForRuleAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null && e.getData(EventsTree.RULE)?.pattern != null }

    override fun actionPerformed(e: AnActionEvent) {
        val p = e.project ?: return
        val rule = e.getData(EventsTree.RULE) ?: return
        val env = p.env()
        Ui.background(p, "Deliberator: a new event for ${rule.name}", {
            val step = EventsOps.sample(WorkspaceService.getInstance(p), env, rule.bus, rule.name)
            ApplicationManager.getApplication().invokeLater({ insertStep(p, env, step) }, ModalityState.nonModal())
        })
    }

    companion object {
        /** [step] at the caret of the open scenario; none open: appended to `explore-<env>.scn` (opened). EDT. */
        fun insertStep(p: Project, env: String, step: String) {
            val ed = FileEditorManager.getInstance(p).selectedTextEditor
            if (ed != null && com.intellij.psi.PsiDocumentManager.getInstance(p).getPsiFile(ed.document) is ScnFile) { AwsStepEdits.appendStep(ed, step); return }
            ApplicationManager.getApplication().executeOnPooledThread {
                val path = try {
                    WorkspaceService.getInstance(p).call("explore.write", JsonObject().apply { addProperty("name", ExplorerSteps.fileName(env)); addProperty("text", ExplorerSteps.header(env)); addProperty("if-missing", true) })
                        .resultObject().get("path")?.asString
                } catch (x: Exception) { null }
                ApplicationManager.getApplication().invokeLater({
                    val vf = path?.let { com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(java.nio.file.Path.of(it)) } ?: return@invokeLater
                    Ui.openEditor(p, vf)?.let { AwsStepEdits.appendStep(it, step) }
                }, ModalityState.nonModal())
            }
        }
    }
}

/** `deliberator.explorer.targetLogs`: Open Logs of a Lambda target: its log group's newest events as a read step of the explorer's file. */
class OpenTargetLogsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null && e.getData(EventsTree.TARGET)?.kind == "lambda" && e.getData(AwsExplorerPanel.EXPLORER) != null }
    override fun actionPerformed(e: AnActionEvent) {
        val p = e.project ?: return
        val t = e.getData(EventsTree.TARGET) ?: return
        val panel = e.getData(AwsExplorerPanel.EXPLORER) ?: return
        ApplicationManager.getApplication().invokeLater({ panel.openStep(p.env(), logsStep(t.name)) }, ModalityState.nonModal())
    }

    companion object {
        fun logsStep(function: String) = "### aws logs.FilterLogEvents ${function}-logs\n{\n  \"logGroupName\": \"/aws/lambda/$function\",\n  \"limit\": 50\n}\n"
    }
}

/** `deliberator.scenario.whoReceives`: Who Receives This, on a PutEvents step: the rules of the cached map the step's event matches, and what they deliver to. */
class WhoReceivesAction : ScnEditorAction() {
    override fun enabled(e: AnActionEvent): Boolean = viewOf(e) != null && sentEventAtCaret(e) != null

    private fun sentEventAtCaret(e: AnActionEvent): SentEvent? {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val psi = e.getData(CommonDataKeys.PSI_FILE) as? ScnFile ?: return null
        val step = psi.stepAt(editor.caretModel.offset) ?: return null
        return sentEvent(step.kind, step.verb, step.head?.words?.firstOrNull { it.target }?.text, step.directives.associate { it.key to it.value }, step.body?.text)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val v = viewOf(e) ?: return
        val ev = sentEventAtCaret(e) ?: run { Ui.notify(v.project, "Who receives this", "The caret is not in a PutEvents step (### eventbridge put <bus> or ### aws eventbridge.PutEvents).", NotificationType.INFORMATION); return }
        val p = v.project
        val env = p.env()
        Ui.background(p, "Deliberator: who receives this event", {
            val hits = EventsOps.who(WorkspaceService.getInstance(p), env, ev)
            val lines = hits.map { it.text() }.ifEmpty { listOf("no rule of the cached map matches this event") }
            WhoResults.last = lines
            ApplicationManager.getApplication().invokeLater({
                JBPopupFactory.getInstance().createPopupChooserBuilder(lines).setTitle("Who receives ${ev.detailType ?: "this event"} from ${ev.source ?: "deliberator"} in $env")
                    .createPopup().showCenteredInCurrentWindow(p)
            }, ModalityState.nonModal())
        })
    }

    companion object {
        /** The event a step sends (`eventbridge put <bus>` with `@source` / `@detail-type` and the detail as its body, or an `aws eventbridge.PutEvents` body); null for any other step. */
        fun sentEvent(kind: String?, verb: String?, target: String?, directives: Map<String, String>, body: String?): SentEvent? {
            val d = directives.mapKeys { it.key.removePrefix("@") }
            if (kind == "eventbridge") return SentEvent(target, d["source"] ?: "deliberator", d["detail-type"] ?: "scenario", body)
            if (kind == "aws" && verb?.endsWith("PutEvents") == true) {
                val e = runCatching { JsonParser.parseString(body ?: "").asJsonObject.getAsJsonArray("Entries")[0].asJsonObject }.getOrNull() ?: return null
                fun s(k: String) = e.get(k)?.takeIf { it.isJsonPrimitive }?.asString
                return SentEvent(s("EventBusName"), s("Source"), s("DetailType"), s("Detail"))
            }
            return null
        }
    }
}

/** What the last Who Receives This showed (tests). */
object WhoResults { @Volatile var last: List<String> = emptyList() }
