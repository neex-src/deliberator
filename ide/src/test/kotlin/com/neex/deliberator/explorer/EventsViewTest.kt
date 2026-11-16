package com.neex.deliberator.explorer

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.neex.deliberator.daemon.DeliberatorSettings
import com.neex.deliberator.scn.LiveResource
import com.neex.deliberator.testing.TempDaemon
import com.neex.deliberator.workspace.WorkspaceService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Piece 05 stage 2 against the real daemon on a temp home: the EventBridge node of the AWS tab (bus -> rule -> target from the cached `events.map`, the search over bus, rule,
 * pattern and target, reconciled in place so expansion and selection survive), a target leading to its own row, `events.who` for a PutEvents step, `events.sample` into the open
 * scenario, and Open in AWS Console (the plain link, the sign-in refused by a profile that does not exist, the browser reached by the hook, the link never in a balloon). The
 * sandbox IDE against Floci and the real account is `.scratch/p05-evidence/`.
 */
class EventsViewTest : BasePlatformTestCase() {
    private lateinit var daemon: TempDaemon
    private lateinit var svc: WorkspaceService
    private var panel: AwsExplorerPanel? = null

    override fun setUp() {
        super.setUp()
        daemon = TempDaemon.create()
        DeliberatorSettings.getInstance().state.home = daemon.home.toString()
        svc = WorkspaceService.getInstance(project)
        svc.loadState(WorkspaceService.State())
        daemon.openWorkspace("p05t")
        svc.bind("p05t")
        // a remote record whose profile does not exist: a refresh fails readably and never reaches a Floci that happens to run on this machine
        val record = JsonParser.parseString("""{"kind":"remote","read-only":true,"aws":{"profile":"p05t-no-such-profile","region":"eu-central-1","account":"123456789012"},"targets":{}}""").asJsonObject
        bg { svc.call("env.save", JsonObject().apply { addProperty("name", "dev"); add("record", record); addProperty("check", false) }) }
        svc.selectEnv("dev")
        seed("dev")
    }

    override fun tearDown() {
        try {
            panel?.let { Disposer.dispose(it) }
            ConsoleBrowser.opener = null
            DeliberatorSettings.getInstance().state.home = ""
            daemon.close()
        } finally {
            super.tearDown()
        }
    }

    private fun <T> bg(f: () -> T): T = ApplicationManager.getApplication().executeOnPooledThread<T> { f() }.get(120, TimeUnit.SECONDS)
    private fun wait(what: String, secs: Int = 90, cond: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out: $what", cond, secs)
    private fun wsDir(): Path = Path.of(bg { svc.call("workspace.show").resultObject().get("path").asString })

    /** What `events.map` leaves behind, written by hand: the daemon's cache of the environment. */
    private fun seed(env: String, disabled: Boolean = false) {
        val d = wsDir().resolve("cache").resolve(env)
        Files.createDirectories(d)
        Files.writeString(d.resolve("eventbridge.edn"), """{:env "$env" :fetched-at #inst "2026-11-16T08:00:00Z" :raw []
            :buses [{:name "default" :arn "a:default" :rules []}
                    {:name "ops-bus" :arn "a:ops" :rules [
                      {:name "maintenance-requested" :bus "ops-bus" :state "ENABLED" :pattern-line "detail-type: MaintenanceRequested · source: ops.sys"
                       :pattern "{\"detail-type\":[\"MaintenanceRequested\"],\"source\":[\"ops.sys\"]}"
                       :targets [{:id "down" :arn "arn:aws:lambda:eu-central-1:1:function:maintenance-down" :kind "lambda" :name "maintenance-down"}]}
                      {:name "maintenance-lifted" :bus "ops-bus" :state "${if (disabled) "DISABLED" else "ENABLED"}" :pattern-line "detail-type: MaintenanceLifted · source: ops.sys"
                       :pattern "{\"detail-type\":[\"MaintenanceLifted\"],\"source\":[\"ops.sys\"]}"
                       :targets [{:id "up" :arn "arn:aws:lambda:eu-central-1:1:function:maintenance-up" :kind "lambda" :name "maintenance-up"}]}
                      {:name "billing" :bus "ops-bus" :state "ENABLED" :pattern-line "detail-type: BillingActivated" :pattern "{\"detail-type\":[\"BillingActivated\"]}"
                       :targets [{:id "q" :arn "arn:aws:sqs:eu-central-1:1:billing-inbox" :kind "sqs" :name "billing-inbox" :input :transformer}]}]}]}""")
    }

    private fun newPanel(): AwsExplorerPanel = AwsExplorerPanel(project).also { panel = it }

    private fun fresh() = EventsMap.parse("dev", bg { svc.call("events.map", JsonObject().apply { addProperty("env", "dev"); addProperty("cached", true) }).resultObject() })

    fun testTheTreeShowsBusRuleAndTargetFromTheCachedMapWithItsAge() {
        val p = newPanel()
        wait("the cached map in the tree") { p.events.lines().any { it.contains("maintenance-requested") } }
        val lines = p.events.lines()
        assertTrue(lines.toString(), lines.first().startsWith("EventBridge (3 rule(s))"))
        assertTrue(lines.contains("  ops-bus"))
        assertTrue(lines.contains("    maintenance-requested ENABLED detail-type: MaintenanceRequested · source: ops.sys"))
        assertTrue(lines.contains("      -> lambda maintenance-down"))
        assertTrue(lines.contains("      -> sqs billing-inbox"))
        assertTrue("an empty bus is a bus", lines.contains("  default"))
        assertTrue(p.events.map!!.status().contains("map fetched"))
    }

    fun testTheSearchFindsMaintenanceByRuleByPatternAndByTarget() {
        val p = newPanel()
        wait("the map") { p.events.map != null }
        p.searchFor("maint")
        assertEquals(listOf("EventBridge (3 rule(s), showing 2)", "  ops-bus", "    maintenance-requested ENABLED detail-type: MaintenanceRequested · source: ops.sys", "      -> lambda maintenance-down",
            "    maintenance-lifted ENABLED detail-type: MaintenanceLifted · source: ops.sys", "      -> lambda maintenance-up"), p.events.lines())
        p.searchFor("billingactivated")
        assertEquals(listOf("billing"), p.events.lines().filter { it.trim().startsWith("billing") }.map { it.trim().substringBefore(' ') })
        p.searchFor("billing-inbox")
        assertTrue(p.events.lines().any { it.contains("-> sqs billing-inbox") })
        p.searchFor("nothing-like-this")
        assertTrue(p.events.lines().last().contains("nothing matches \"nothing-like-this\""))
        p.searchFor("")
        assertTrue(p.events.lines().any { it.contains("maintenance-lifted") && it.contains("billing") || it.contains("-> sqs billing-inbox") })
    }

    fun testANewMapIsReconciledInPlaceAndExpansionAndSelectionSurvive() {
        val p = newPanel()
        wait("the map in the tree") { p.events.map != null && p.events.node.parent != null }
        val tree = p.events.node
        fun rule(name: String) = (0 until tree.childCount).map { tree.getChildAt(it) as javax.swing.tree.DefaultMutableTreeNode }.first { (it.userObject as? EvBus)?.bus?.name == "ops-bus" }
            .let { b -> (0 until b.childCount).map { b.getChildAt(it) as javax.swing.tree.DefaultMutableTreeNode }.first { (it.userObject as EvRule).rule.name == name } }
        assertTrue(p.events.lines().toString(), p.events.lines().any { it.contains("maintenance-lifted") })
        val before = rule("maintenance-lifted")
        assertTrue(p.selectKey("eventbridge/b:ops-bus/r:ops-bus/maintenance-lifted"))
        // the rule is disabled outside: the same map again with that rule's state changed
        seed("dev", disabled = true)
        p.events.show(fresh())
        val after = rule("maintenance-lifted")
        assertSame("the node is the same node: nothing was rebuilt", before, after)
        assertEquals("DISABLED", (after.userObject as EvRule).rule.state)
        assertTrue("it is still the selected row", p.selectedKeyForTests().endsWith("r:ops-bus/maintenance-lifted"))
        assertTrue(p.events.lines().any { it.contains("maintenance-lifted DISABLED") })
    }

    fun testATargetLeadsToItsOwnRowAndALambdaTargetOffersItsLogs() {
        val p = newPanel()
        wait("the map") { p.events.map != null && p.events.node.parent != null }
        p.render(mapOf("lambda" to listOf(LiveResource("maintenance-down", "arn:aws:lambda:eu-central-1:1:function:maintenance-down", emptyList()), LiveResource("other", null, emptyList()))))
        p.openTarget(TargetRow("down", "lambda", "maintenance-down", "arn", null, null, null))
        assertEquals("k:lambda/r:lambda:maintenance-down", p.selectedKeyForTests())
        p.openTarget(TargetRow("x", "sqs", "not-in-this-view", "arn:aws:sqs:eu-central-1:1:not-in-this-view", null, null, null))
        assertTrue(p.statusText(), p.statusText().contains("no row of its own"))
        assertTrue(OpenTargetLogsAction.logsStep("maintenance-down").startsWith("### aws logs.FilterLogEvents maintenance-down-logs"))
        assertTrue(OpenTargetLogsAction.logsStep("maintenance-down").contains("\"/aws/lambda/maintenance-down\""))
    }

    fun testWhoReceivesNamesTheRulesAndSaysWhenOneIsNotListening() {
        seed("dev", disabled = true)
        val hits = bg { EventsOps.who(svc, "dev", SentEvent("ops-bus", "ops.sys", "MaintenanceLifted", "{}")) }
        assertEquals(1, hits.size)
        assertEquals("ops-bus/maintenance-lifted  NOT LISTENING (disabled)  ->  lambda maintenance-up", hits[0].text())
        val ok = bg { EventsOps.who(svc, "dev", SentEvent("ops-bus", "ops.sys", "MaintenanceRequested", "{}")) }
        assertEquals("ops-bus/maintenance-requested  listening  ->  lambda maintenance-down", ok.single().text())
        assertTrue(bg { EventsOps.who(svc, "dev", SentEvent("ops-bus", "nobody", "Nothing", "{}")) }.isEmpty())
    }

    fun testTheEventASentStepCarriesIsReadFromBothStepKinds() {
        val e = WhoReceivesAction.sentEvent("eventbridge", "put", "ops-bus", mapOf("source" to "ops.sys", "detail-type" to "MaintenanceRequested"), "{\"reason\":\"x\"}")!!
        assertEquals(SentEvent("ops-bus", "ops.sys", "MaintenanceRequested", "{\"reason\":\"x\"}"), e)
        assertEquals(SentEvent("b", "deliberator", "scenario", null), WhoReceivesAction.sentEvent("eventbridge", "put", "b", emptyMap(), null))
        val aws = WhoReceivesAction.sentEvent("aws", "eventbridge.PutEvents", null, emptyMap(),
            """{"Entries":[{"EventBusName":"ops-bus","Source":"ops.sys","DetailType":"MaintenanceLifted","Detail":"{}"}]}""")!!
        assertEquals("MaintenanceLifted", aws.detailType)
        assertNull(WhoReceivesAction.sentEvent("sqs", "send", "q", emptyMap(), "{}"))
    }

    fun testNewEventForThisRuleInsertsTheStepAtTheOpenScenario() {
        myFixture.configureByText("t.scn", "### wait first\n# @for 100ms\n")
        val step = bg { EventsOps.sample(svc, "dev", "ops-bus", "maintenance-requested") }
        assertTrue(step, step.startsWith("### eventbridge put ops-bus\n# @source ops.sys\n# @detail-type MaintenanceRequested\n"))
        NewEventForRuleAction.insertStep(project, "dev", step)
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("### wait first") && text.contains("### eventbridge put ops-bus") && text.contains("# @detail-type MaintenanceRequested"))
    }

    fun testOpenInConsoleOpensThePlainLinkWhenTheSignInCannotBeMadeAndNeverShowsTheLink() {
        val record = JsonParser.parseString("""{"kind":"remote","read-only":true,"aws":{"profile":"p05t-no-such-profile","region":"eu-central-1","account":"123456789012"},"targets":{},"console":{"signin":true}}""").asJsonObject
        bg { svc.call("env.save", JsonObject().apply { addProperty("name", "dev"); add("record", record); addProperty("check", false) }) }
        val link = bg { EventsOps.consoleLink(svc, "dev", "rule", "ops-bus/maintenance-requested") }
        assertFalse("a profile that does not exist signs nobody in", link.signedIn)
        assertEquals("https://eu-central-1.console.aws.amazon.com/events/home?region=eu-central-1#/eventbus/ops-bus/rules/maintenance-requested", link.url)
        assertNotNull(link.note)
        assertFalse("the note never carries a link", link.note!!.contains("SigninToken"))
        var opened: String? = null
        ConsoleBrowser.opener = { opened = it }
        ConsoleBrowser.open(link.url)
        assertEquals(link.url, opened)
        assertEquals("test", ConsoleBrowser.lastOpenedWith)
        assertEquals("the browser is named by DELIBERATOR_BROWSER or the platform's", null, ConsoleBrowser.command())
    }
}
