package com.neex.deliberator.explorer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.neex.deliberator.daemon.DeliberatorSettings
import com.neex.deliberator.testing.TempDaemon
import com.neex.deliberator.workspace.WorkspaceService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The AWS tab's Terraform buttons (review 2026-10-25, S5/R1/R22) against the real daemon on a temp home: Plan, Apply and Drift on a root
 * without Terraform files end in an error the user can read (`tf/no-root`), never the "blocks; call it from a background thread" of a
 * daemon call made on the EDT; Apply fetches the variables off the EDT, asks (the confirmer, a dialog in the product) and does nothing
 * when the answer is no. The test thread is the EDT, as the button's.
 */
class AwsExplorerTest : BasePlatformTestCase() {
    private lateinit var daemon: TempDaemon
    private lateinit var panel: AwsExplorerPanel
    private lateinit var tf: Path

    override fun setUp() {
        super.setUp()
        daemon = TempDaemon.create()
        DeliberatorSettings.getInstance().state.home = daemon.home.toString()
        val svc = WorkspaceService.getInstance(project)
        svc.loadState(WorkspaceService.State())
        daemon.openWorkspace("w5")
        svc.bind("w5")
        tf = daemon.home.resolve("tf")
        Files.createDirectories(tf.resolve("no-root"))
        Files.createDirectories(tf.resolve("ok"))
        Files.writeString(tf.resolve("ok").resolve("main.tf"), "variable \"x\" {\n  default = \"1\"\n}\n")
        panel = AwsExplorerPanel(project)
    }

    override fun tearDown() {
        try {
            Disposer.dispose(panel)
            DeliberatorSettings.getInstance().state.home = ""
            daemon.close()
        } finally {
            super.tearDown()
        }
    }

    private fun wait(what: String, secs: Int = 60, cond: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out: $what", cond, secs)
    private fun <T> bg(f: () -> T): T = ApplicationManager.getApplication().executeOnPooledThread<T> { f() }.get(60, TimeUnit.SECONDS)

    private fun assertReadableError(button: String) {
        wait("$button to end in an error") { panel.lastError != null }
        val e = panel.lastError!!
        assertTrue("$button: the daemon's own message, got: $e", e.contains("no Terraform files"))
        assertFalse("$button must not call the daemon on the EDT: $e", e.contains("background thread"))
        wait("$button's status to say it failed") { panel.statusText().contains("failed") }
    }

    fun testPlanOnARootWithoutTerraformFilesEndsInTheDaemonsError() {
        panel.terraform("plan", tf.resolve("no-root").toString())
        assertReadableError("Plan")
    }

    fun testApplyOnARootWithoutTerraformFilesEndsInTheDaemonsErrorAndNeverAsks() {
        var asked = 0
        panel.applyConfirmer = { _, _, _ -> asked++; true }
        panel.terraform("apply", tf.resolve("no-root").toString())
        assertReadableError("Apply")
        assertEquals("no question for a root that cannot be applied", 0, asked)
    }

    fun testDriftOnARootWithoutTerraformFilesEndsInTheDaemonsErrorAndWritesNoReport() {
        // B18: it used to answer "0/0 declared present" and write a "no drift" report for a folder that holds no Terraform
        panel.runDrift(tf.resolve("no-root").toString())
        assertReadableError("Drift")
        val wsDir = Path.of(bg { WorkspaceService.getInstance(project).call("workspace.show").resultObject().get("path").asString })
        assertFalse("no report for a root that is not one", Files.exists(wsDir.resolve("project").resolve("explore").resolve("drift-local.txt")))
    }

    fun testApplyAsksWithTheDaemonsVariablesOnTheEdtAndNoDoesNothing() {
        var seen: Triple<String, String, Map<String, String>>? = null
        var onEdt = false
        panel.applyConfirmer = { root, env, vars -> onEdt = ApplicationManager.getApplication().isDispatchThread; seen = Triple(root, env, vars); false }
        panel.terraform("apply", tf.resolve("ok").toString())
        wait("the question") { seen != null }
        assertTrue("the dialog is shown on the EDT", onEdt)
        assertEquals(tf.resolve("ok").toString(), seen!!.first)
        wait("the status to be cleared after No") { panel.statusText().isBlank() }
        assertNull(panel.lastError)
        val wsDir = Path.of(bg { WorkspaceService.getInstance(project).call("workspace.show").resultObject().get("path").asString })
        assertFalse("no transcript for an apply that was declined", Files.exists(wsDir.resolve("project").resolve("explore").resolve("terraform-apply-local.txt")))
    }

    fun testADriftRowsAddressOpensTheTfFileAtTheDeclaration() {
        // M6.4: terraform.locate answers file and line; the editor opens there (line 3 = index 2)
        val root = tf.resolve("loc")
        Files.createDirectories(root)
        Files.writeString(root.resolve("main.tf"), "variable \"x\" {\n  default = \"1\"\n}\n\nresource \"aws_sqs_queue\" \"inbox\" {\n  name = \"inbox\"\n}\n")
        panel.openDeclaration("aws_sqs_queue.inbox", root.toString())
        wait("the file opens at the declaration") { com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor?.virtualFile?.name == "main.tf" }
        val ed = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(4, ed.caretModel.logicalPosition.line)
        // an address nobody declares is the daemon's typed answer, shown
        panel.openDeclaration("aws_sqs_queue.nope", root.toString())
        wait("the refusal") { panel.lastError != null }
        assertTrue(panel.lastError!!, panel.lastError!!.contains("no declaration of aws_sqs_queue.nope"))
    }

    fun testAChangedByHandResourceIsMarkedInTheTreeAndItsRowOpensTheDeclaration() {
        // B39 in the explorer: terraform.drift with a refresh names the attributes; the row of that resource says so
        val root = tf.resolve("chg")
        Files.createDirectories(root)
        Files.writeString(root.resolve("main.tf"), "resource \"aws_ssm_parameter\" \"p02\" {\n  name = \"/x/p02\"\n}\n")
        val env = WorkspaceService.getInstance(project).selectedEnv
        panel.drift = Drift.parse(com.google.gson.JsonParser.parseString("""{"env":"$env","source":"$root","declared":1,"matching":1,"missing":[],"undeclared":[],
            "refreshed":{"lines":3},"changed":[{"address":"aws_ssm_parameter.p02","kind":"ssm","aws-name":"/x/p02","attributes":["value","version"]}]}""").asJsonObject)
        panel.render(mapOf("ssm" to listOf(com.neex.deliberator.scn.LiveResource("/x/p02", null), com.neex.deliberator.scn.LiveResource("/x/p03", null))))
        assertEquals(listOf("ssm: /x/p02 [changed: value,version]", "ssm: /x/p03"), panel.lines())
        panel.openDeclaration("aws_ssm_parameter.p02", root.toString())
        wait("the file opens at the declaration") { com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor?.virtualFile?.name == "main.tf" }
        assertEquals(0, com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor!!.caretModel.logicalPosition.line)
    }

    fun testRefreshStateIsOnlyForARemoteEnvironment() {
        // the checkbox is off-limits for the local emulator and a drift asked with it is the plain read (the daemon refuses a refresh of a local root)
        panel.render(emptyMap())
        assertFalse("the temp workspace's environment is local", panel.refreshState.isEnabled)
        panel.refreshState.isSelected = true
        panel.runDrift(tf.resolve("ok").toString())
        wait("the comparison ends") { panel.statusText().contains("declared present") || panel.lastError != null }
        assertNull("no refresh was asked of a local environment: ${panel.lastError}", panel.lastError)
    }
}
