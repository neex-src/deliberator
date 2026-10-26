package com.neex.deliberator.logs

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.daemon.DeliberatorSettings
import com.neex.deliberator.environments.EnvironmentOps
import com.neex.deliberator.environments.LogGroupIndex
import com.neex.deliberator.testing.TempDaemon
import com.neex.deliberator.workspace.WorkspaceService
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * M4.4: an exception in the editor becomes a Logs Insights query. The query text is pure and escaped; the dialog holds the window (1 h by
 * default) and the groups of the service; the run asks the daemon (`logs.insights`) and an answer opens as text with the bytes scanned in a
 * banner. The daemon is real on a temp home; the Insights success path against an emulator is walked in the sandbox IDE.
 */
class InsightsTest : BasePlatformTestCase() {
    private lateinit var daemon: TempDaemon
    private lateinit var svc: WorkspaceService

    override fun setUp() {
        super.setUp()
        daemon = TempDaemon.create()
        DeliberatorSettings.getInstance().state.home = daemon.home.toString()
        svc = WorkspaceService.getInstance(project)
        svc.loadState(WorkspaceService.State())
        daemon.openWorkspace("m4l")
        svc.bind("m4l")
    }

    override fun tearDown() {
        try {
            DeliberatorSettings.getInstance().state.home = ""
            daemon.close()
        } finally {
            super.tearDown()
        }
    }

    private fun <T> bg(f: () -> T): T = ApplicationManager.getApplication().executeOnPooledThread<T> { f() }.get(120, TimeUnit.SECONDS)

    fun testTheNeedleIsTheFirstLineOfTheSelectionAndRegexCharactersAreEscaped() {
        assertEquals("java.lang.IllegalStateException: no \$id", ExceptionQuery.needle("\n  java.lang.IllegalStateException: no \$id  \n\tat com.x.Y(Y.java:3)"))
        assertEquals(200, ExceptionQuery.needle("x".repeat(500)).length)
        assertEquals("", ExceptionQuery.needle("  \n "))
        assertEquals("""timeout \(30s\) on http:\/\/x\/y\?a=1\+2""", ExceptionQuery.escape("timeout (30s) on http://x/y?a=1+2"))
        val q = ExceptionQuery.query("java.lang.NullPointerException")
        assertEquals("fields @timestamp, @logStream, @message | filter @message like /java\\.lang\\.NullPointerException/ | sort @timestamp desc | limit 200", q)
    }

    fun testTheDialogDefaultsToOneHourAndTakesTheGroupsOfTheService() {
        val index = LogGroupIndex(mapOf("/aws/lambda/orders-fn" to "orders", "/ecs/billing" to "service-billing"), listOf("orders-fn", "billing-fn"))
        assertEquals(listOf("/aws/lambda/orders-fn", "/ecs/billing"), index.forService(null))
        assertEquals(listOf("/aws/lambda/orders-fn"), index.forService("orders"))
        assertEquals(listOf("/ecs/billing", "/aws/lambda/billing-fn"), index.forService("billing"))
        val dlg = InsightsDialog(project, "dev", "java.lang.IllegalStateException", listOf("orders", "billing"), "orders") { index.forService(it) }
        try {
            val r = dlg.request()
            assertEquals("1h", r.since)
            assertNull(r.until)
            assertEquals(listOf("/aws/lambda/orders-fn"), r.groups)
            assertEquals("dev", r.env)
            assertTrue(r.query, r.query.contains("java\\.lang\\.IllegalStateException"))
            assertNull(dlg.validation())
            dlg.set(groupsText = "", sinceText = "90m")
            assertNotNull("no group, nothing to search", dlg.validation())
            dlg.set(groupsText = "/aws/lambda/a, /aws/lambda/b ")
            assertEquals(listOf("/aws/lambda/a", "/aws/lambda/b"), dlg.request().groups)
            assertEquals("90m", dlg.request().since)
        } finally {
            Disposer.dispose(dlg.disposable)
        }
    }

    fun testTheRequestIsWhatLogsInsightsTakes() {
        val a = InsightsRun.args(InsightsRequest(listOf("g1", "g2"), "fields @message", "6h", "30m", "exc", "dev"))
        assertEquals("""["g1","g2"]""", a.get("groups").toString())
        assertEquals("fields @message", a.get("query").asString)
        assertEquals("dev", a.get("env").asString)
        assertEquals("6h", a.get("since").asString)
        assertEquals("30m", a.get("until").asString)
        assertEquals("exc", a.get("name").asString)
        assertFalse(InsightsRun.args(InsightsRequest(listOf("g"), "q", "1h", null, null, "dev")).has("until"))
    }

    fun testALogGroupIsFoundInTheEnvironmentsTargetsAndTheCachedLambdas() {
        bg {
            svc.call("env.save", JsonObject().apply {
                addProperty("name", "dev"); addProperty("check", false)
                add("record", JsonParser.parseString("""{"kind":"remote","read-only":true,"aws":{"profile":"p","region":"eu-central-1","account":"123456789012"},
                    "targets":{"log/orders":{"cloudwatch":"/aws/lambda/orders-api"},"log/other":{"container":"c"}}}""").asJsonObject)
            })
        }
        val idx = bg { EnvironmentOps.logGroupIndex(svc, "dev") }
        assertEquals(listOf("/aws/lambda/orders-api"), idx.forService(null))
        assertEquals(listOf("/aws/lambda/orders-api"), idx.forService("orders"))
        assertEquals(emptyList<String>(), idx.forService("billing"))
        assertEquals("the service picker offers what the environment's targets are called", listOf("orders"), idx.serviceNames())
    }

    fun testARunThatTheDaemonRefusesIsTheDaemonsError() {
        bg { svc.call("env.save", JsonObject().apply {
            addProperty("name", "dev"); addProperty("check", false)
            add("record", JsonParser.parseString("""{"kind":"remote","read-only":true,"aws":{"profile":"m4-no-such-profile","region":"eu-central-1","account":"123456789012"},"targets":{}}""").asJsonObject)
        }) }
        val e = bg<Any?> {
            try { InsightsRun.runNow(project, InsightsRequest(listOf("/aws/lambda/x"), "fields @message", "1h", null, null, "dev")) } catch (x: DaemonException) { x }
        }
        assertTrue("a DaemonException with the credentials words, got: $e", e is DaemonException && (e.message ?: "").contains("m4-no-such-profile"))
    }

    fun testTheResultOpensAsTextWithTheBytesScannedInABanner() {
        val f = daemon.home.resolve("insights-result.txt")
        Files.writeString(f, "# bytes scanned: 1536\n@timestamp\t@message\n2026-10-26T10:00:00Z\tboom\n")
        val res = InsightsResult.parse(
            JsonParser.parseString("""{"path":"$f","status":"Complete","rows":1,"bytes-scanned":1536,"query-file":null}""").asJsonObject,
            InsightsRequest(listOf("/aws/lambda/x"), "q", "1h", null, null, "dev"),
        )
        val vf = InsightsRun.show(project, res, InsightsRun.resolve(res))!!
        val fe = FileEditorManager.getInstance(project).getEditors(vf).first()
        val banner = bg { InsightsBanner().collectNotificationData(project, vf) }!!.apply(fe) as com.intellij.ui.EditorNotificationPanel
        assertTrue(banner.text, banner.text.contains("1 row(s)") && banner.text.contains("1.5 KB (1536 bytes) scanned") && banner.text.contains("Complete") && banner.text.contains("/aws/lambda/x"))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }
}
