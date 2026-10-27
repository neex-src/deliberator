package com.neex.deliberator.environments

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
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
 * M4.1, M4.2 and the audit view of M4.6 against the real daemon on a temp home: the New Environment form (the daemon's refusal stays
 * in the dialog, nothing is written), the environment list and its Remove with a confirmation, the deployed view from the cache with its
 * age (and a failed Refresh leaving it alone), and the audit trail opening with the confirmations marked. The test thread is the EDT.
 */
class EnvironmentsTest : BasePlatformTestCase() {
    private lateinit var daemon: TempDaemon
    private lateinit var svc: WorkspaceService
    private var panel: EnvironmentsPanel? = null

    override fun setUp() {
        super.setUp()
        daemon = TempDaemon.create()
        DeliberatorSettings.getInstance().state.home = daemon.home.toString()
        svc = WorkspaceService.getInstance(project)
        svc.loadState(WorkspaceService.State())
        daemon.openWorkspace("m4")
        svc.bind("m4")
    }

    override fun tearDown() {
        try {
            panel?.let { Disposer.dispose(it) }
            AuditViews.getInstance(project).forget()
            DeliberatorSettings.getInstance().state.home = ""
            daemon.close()
        } finally {
            super.tearDown()
        }
    }

    private fun <T> bg(f: () -> T): T = ApplicationManager.getApplication().executeOnPooledThread<T> { f() }.get(120, TimeUnit.SECONDS)
    private fun wait(what: String, secs: Int = 90, cond: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out: $what", cond, secs)

    /** A remote record written offline (`check false`): the account check is the form's business, these tests need records to list. */
    private fun save(name: String, readOnly: Boolean, account: String = "123456789012") = bg {
        val record = JsonParser.parseString(
            """{"kind":"remote","read-only":$readOnly,"aws":{"profile":"m4-no-such-profile","region":"eu-central-1","account":"$account"},
               "targets":{"log/orders":{"cloudwatch":"/aws/lambda/orders-fn"}}}""",
        ).asJsonObject
        svc.call("env.save", JsonObject().apply { addProperty("name", name); add("record", record); addProperty("check", false) })
    }

    private fun newPanel() = EnvironmentsPanel(project).also { panel = it }

    private fun wsDir(): Path = Path.of(bg { svc.call("workspace.show").resultObject().get("path").asString })

    // --- M4.1 -----------------------------------------------------------------------------------------------

    fun testTheFormChecksTheFieldsBeforeTheDaemonIsAsked() {
        fun f(n: String = "dev", p: String = "p", a: String = "123456789012", r: String = "eu-central-1") = EnvForm(n, p, a, r, true)
        assertNull(f().problem)
        assertNotNull("no name", f(n = "").problem)
        assertNotNull("a space in the name", f(n = "my env").problem)
        assertTrue("local is the emulator's", f(n = "local").problem!!.contains("local"))
        assertNotNull("no profile", f(p = " ").problem)
        assertNotNull("11 digits", f(a = "12345678901").problem)
        assertNotNull("letters", f(a = "12345678901x").problem)
        assertNotNull("no region", f(r = "").problem)
    }

    fun testARefusedCreateStaysInTheDialogWithTheDaemonsWordsAndWritesNothing() {
        val dlg = NewEnvironmentDialog(project)
        try {
            dlg.type("dev", "m4-no-such-profile", "123456789012")
            assertNull(dlg.validation())
            dlg.submit()
            wait("the refusal") { !dlg.creating && dlg.outcomeText.startsWith("Not created") }
            assertTrue(dlg.outcomeText, dlg.outcomeText.contains("dev"))
            assertTrue("the daemon's hint is shown: ${dlg.outcomeText}", dlg.outcomeText.contains("m4-no-such-profile"))
            assertNull("the dialog did not close with a created environment", dlg.created)
            assertFalse(bg { EnvironmentOps.list(svc) }.any { it.name == "dev" })
        } finally {
            Disposer.dispose(dlg.disposable)
        }
    }

    fun testANameThatExistsIsRefusedAndTheOldRecordIsKept() {
        save("dup", readOnly = true)
        val dlg = NewEnvironmentDialog(project)
        try {
            dlg.type("dup", "m4-no-such-profile", "999999999999")
            dlg.submit()
            wait("the refusal") { !dlg.creating && dlg.outcomeText.startsWith("Not created") }
            val dup = bg { EnvironmentOps.list(svc) }.first { it.name == "dup" }
            assertEquals("the record of the same name is untouched", "123456789012", dup.account)
        } finally {
            Disposer.dispose(dlg.disposable)
        }
    }

    fun testTheCreatedAnswerReadsAsTheAccountCheckResult() {
        val c = EnvCreated.parse(JsonParser.parseString("""{"env":"dev","kind":"remote","account":"000000000000","checked":true,"read-only":true,"path":"/x"}""").asJsonObject)
        assertEquals("Created dev: profile p is in account 000000000000 (checked with sts:GetCallerIdentity), region eu-central-1, read-only: every mutation is refused", c.text("p", "eu-central-1"))
        val off = EnvCreated.parse(JsonParser.parseString("""{"env":"dev","account":"1","checked":false,"read-only":false}""").asJsonObject)
        assertTrue(off.text("p", "r").contains("NOT checked"))
        assertTrue(off.text("p", "r").contains("asks for a confirmation"))
    }

    fun testTheListShowsKindAccountRegionAndWhatAMutationDoes() {
        save("ro1", readOnly = true)
        save("rw1", readOnly = false, account = "210987654321")
        val p = newPanel()
        wait("the list") { p.envNames().any { it.startsWith("ro1") } && p.envNames().any { it.startsWith("rw1") } }
        val names = p.envNames().map { it.substringBefore("  ") }
        val ro = p.envRow(names.indexOf("ro1"))
        assertEquals(listOf("remote", "123456789012", "eu-central-1"), ro.subList(1, 4))
        assertTrue(ro[4], ro[4].contains("read-only"))
        assertTrue(p.envRow(names.indexOf("rw1"))[4].contains("confirmation"))
        assertTrue(p.envRow(names.indexOf("local"))[4].contains("local"))
    }

    fun testEditRecordOpensTheEnvironmentsRecordInTheEditorSoTargetsCanBeWritten() {
        // found walking the IDE against the real account: an environment made in the dialog has no targets and nothing in the IDE could add them
        save("dev", readOnly = false)
        val p = newPanel()
        wait("the list") { p.envNames().contains("dev") }
        p.selectByName("dev")
        p.editRecord()
        val fem = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        wait("the record opens") { fem.selectedFiles.firstOrNull()?.name == "dev.edn" }
        val file = fem.selectedFiles.first()
        assertEquals(wsDir().resolve("environments").resolve("dev.edn").toString(), file.path)
        val text = String(file.contentsToByteArray())
        assertTrue(text, text.contains(":read-only false") && text.contains("log/orders"))
    }

    fun testRemoveAsksFirstNamesWhatStaysAndNeverRemovesLocal() {
        save("gone", readOnly = true)
        val p = newPanel()
        wait("the list") { p.envNames().any { it.startsWith("gone") } }
        var asked: String? = null
        p.removeConfirmer = { asked = it; false }
        p.selectByName("gone")
        p.removeSelected()
        assertNotNull(asked)
        assertTrue(asked!!, asked!!.contains("gone") && asked!!.contains("stays"))
        assertTrue("declined: still there", bg { EnvironmentOps.list(svc) }.any { it.name == "gone" })
        p.removeConfirmer = { true }
        p.removeSelected()
        wait("the record to be removed") { bg { EnvironmentOps.list(svc) }.none { it.name == "gone" } }
        wait("the list to follow") { p.envNames().none { it.startsWith("gone") } }
        // `local` is not removable: no question, nothing happens
        var localAsked = false
        p.removeConfirmer = { localAsked = true; true }
        p.selectByName("local")
        p.removeSelected()
        assertFalse(localAsked)
        assertTrue(bg { EnvironmentOps.list(svc) }.any { it.name == "local" })
    }

    // --- M4.2 -----------------------------------------------------------------------------------------------

    private fun seedDeployed(env: String) {
        val d = wsDir().resolve("cache").resolve(env)
        Files.createDirectories(d)
        Files.writeString(
            d.resolve("deployed.edn"),
            """{:env "$env" :fetched-at #inst "2026-10-26T08:00:00Z" :endpoint "x" :clusters ["estate"] :raw [] :deployments []
               :services [{:kind :ecs :cluster "estate" :name "orders" :arn "a" :status "ACTIVE" :revision 12 :desired 2 :running 2
                           :containers [{:name "app" :image "repo/orders:1.4.2" :tag "1.4.2"}] :deployments [{:updated-at #inst "2026-10-26T07:00:00Z"}]}
                          {:kind :lambda :name "orders-fn" :arn "b" :version "7" :code-sha256 "abc=" :last-modified "2026-10-25T10:00:00.000+0000"}]}
            """.trimIndent(),
        )
    }

    fun testTheDeployedViewIsTheCachedOneWithItsAgeAndAFailedRefreshLeavesItAlone() {
        save("ro1", readOnly = true)
        seedDeployed("ro1")
        val p = newPanel()
        wait("the list") { p.envNames().any { it.startsWith("ro1") } }
        p.selectByName("ro1")
        wait("the cached rows") { p.deployedRows().size == 2 }
        val rows = p.deployedRows()
        assertEquals(listOf("ecs", "estate", "orders", "rev 12", "app:1.4.2", "2/2"), rows[0].subList(0, 6))
        assertEquals(listOf("lambda", "", "orders-fn", "7"), rows[1].subList(0, 4))
        wait("the status with the age") { p.statusText().contains("fetched 2026-10-26T08:00:00Z") && p.statusText().contains("ago") }
        // Refresh asks the daemon for AWS; the profile does not exist, so it fails readably and the cached rows stay
        p.refreshDeployed()
        wait("the refresh to fail") { p.lastError != null }
        assertTrue(p.lastError!!, p.lastError!!.contains("m4-no-such-profile") || p.lastError!!.contains("ro1"))
        assertEquals(2, p.deployedRows().size)
    }

    fun testAnEnvironmentNeverFetchedSaysSoAndOffersTheRefresh() {
        save("fresh", readOnly = true)
        val p = newPanel()
        wait("the list") { p.envNames().any { it.startsWith("fresh") } }
        p.selectByName("fresh")
        wait("the empty view") { p.statusText().contains("nothing fetched yet") }
        assertEquals(0, p.deployedRows().size)
    }

    fun testRefreshFetchesAndShowsTheLocalEnvironmentWhenAnEmulatorIsGiven() {
        val url = System.getenv("DLB_TEST_FLOCI_URL")
        if (url.isNullOrBlank()) { println("SKIPPED testRefreshFetchesAndShowsTheLocalEnvironment: DLB_TEST_FLOCI_URL is not set"); return }
        val p = newPanel()
        wait("the list") { p.envNames().any { it.startsWith("local") } }
        p.selectByName("local")
        wait("the empty view") { p.statusText().contains("nothing fetched yet") }
        p.refreshDeployed()
        wait("the refresh") { p.lastError != null || p.statusText().contains("fetched 20") }
        assertNull(p.lastError, p.lastError)
    }

    // --- M4.6: the audit trail -----------------------------------------------------------------------------

    fun testTheAuditTrailOpensAsTextWithTheConfirmationsMarkedAndCounted() {
        val sess = bg { svc.call("session.start", JsonObject().apply { addProperty("key", "A-1") }).resultObject() }
        val dir = Path.of(sess.get("path").asString)
        Files.writeString(
            dir.resolve("audit.log"),
            listOf(
                """{:at #inst "2026-10-26T10:00:00Z" :env "dev" :service :ssm :op "GetParameter" :outcome :ok}""",
                """{:at #inst "2026-10-26T10:00:01Z" :env "dev" :service :ssm :op "PutParameter" :outcome :confirm-required}""",
                """{:at #inst "2026-10-26T10:00:02Z" :env "dev" :service :ssm :op "PutParameter" :outcome :ok :confirmation :used}""",
                """{:at #inst "2026-10-26T10:00:03Z" :env "dev" :service :sqs :op "DeleteQueue" :outcome :refused :reason :read-only}""",
            ).joinToString("\n", postfix = "\n"),
        )
        var editor: Editor? = null
        var done = false
        AuditLogs.open(project, "A-1") { editor = it; done = true }
        wait("the audit view") { done }
        val ed = editor!!
        assertEquals("the file the daemon named is what is open", "audit.log", com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(ed.document)!!.name)
        assertEquals("lines 2, 3 and 4 (0-based 1..3) are the confirmations and the refusal; the plain ok call is not", listOf(1, 2, 3), AuditLogs.markedLines(ed))
        val vf = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(ed.document)!!
        val fe = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getEditors(vf).first()
        val banner = bg { AuditBanner().collectNotificationData(project, vf) }!!.apply(fe) as com.intellij.ui.EditorNotificationPanel
        assertTrue(banner.text, banner.text.contains("session A-1") && banner.text.contains("4 attempt(s), 1 confirmation(s) asked, 1 given, 1 refused"))
    }

    fun testNoAuditTrailYetIsSaidNotOpened() {
        bg { svc.call("session.start", JsonObject().apply { addProperty("key", "A-2") }) }
        val s = bg { EnvironmentOps.audit(svc, "A-2") }
        assertFalse(s.exists)
        assertEquals(0, s.count)
        assertNull("nothing to open", AuditLogs.show(project, s))
    }
}
