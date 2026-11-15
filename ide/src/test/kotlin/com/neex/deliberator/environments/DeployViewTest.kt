package com.neex.deliberator.environments

import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
 * Piece 04 stage 2 against the real daemon on a temp home: the deployments of a service row from the cache with their age (nothing asks AWS), the table reconciled
 * by deployment id (selection survives), a Lambda's versions in the same table, `deploy.where` for the CI tab, the deploy text from disk, and the follow console's
 * failure path. The live rollout is checked in the sandbox IDE (`.scratch/p04-ide-evidence/`).
 */
class DeployViewTest : BasePlatformTestCase() {
    private lateinit var daemon: TempDaemon
    private lateinit var svc: WorkspaceService
    private var panel: EnvironmentsPanel? = null

    override fun setUp() {
        super.setUp()
        daemon = TempDaemon.create()
        DeliberatorSettings.getInstance().state.home = daemon.home.toString()
        svc = WorkspaceService.getInstance(project)
        svc.loadState(WorkspaceService.State())
        daemon.openWorkspace("p04t")
        svc.bind("p04t")
        save("dev")
        seed("dev")
    }

    override fun tearDown() {
        try {
            panel?.let { Disposer.dispose(it) }
            DeliberatorSettings.getInstance().state.home = ""
            daemon.close()
        } finally {
            super.tearDown()
        }
    }

    private fun <T> bg(f: () -> T): T = ApplicationManager.getApplication().executeOnPooledThread<T> { f() }.get(120, TimeUnit.SECONDS)
    private fun wait(what: String, secs: Int = 90, cond: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out: $what", cond, secs)
    private fun wsDir(): Path = Path.of(bg { svc.call("workspace.show").resultObject().get("path").asString })

    private fun save(name: String) = bg {
        val record = JsonParser.parseString("""{"kind":"remote","read-only":true,"aws":{"profile":"p04-no-such-profile","region":"eu-central-1","account":"123456789012"},"targets":{}}""").asJsonObject
        svc.call("env.save", JsonObject().apply { addProperty("name", name); add("record", record); addProperty("check", false) })
    }

    /** What `deployed.refresh` and `deploy.list` leave behind, written by hand: the files of the daemon's caches. */
    private fun seed(env: String) {
        val c = wsDir().resolve("cache").resolve(env)
        Files.createDirectories(c.resolve("deploy/estate/orders"))
        Files.createDirectories(c.resolve("deploy/lambda/orders-fn"))
        Files.writeString(c.resolve("deployed.edn"), """{:env "$env" :fetched-at #inst "2026-11-15T08:00:00Z" :clusters ["estate"] :raw [] :deployments []
            :services [{:kind :ecs :cluster "estate" :name "orders" :status "ACTIVE" :revision 12 :desired 1 :running 1 :git-sha "0123456789abcdef" :run-id "77"
                        :containers [{:name "app" :image "repo/orders:0123456789ab" :tag "0123456789ab"}] :deployments [{:updated-at #inst "2026-11-15T07:00:00Z"}]}
                       {:kind :lambda :name "orders-fn" :version "7" :code-sha256 "abc=" :last-modified "2026-11-15T07:00:00.000+0000"}]}""")
        Files.writeString(c.resolve("deploy/estate/orders/list.edn"), """{:env "$env" :cluster "estate" :service "orders" :kind :ecs :source :service-deployments :fetched-at #inst "2026-11-15T09:00:00Z"
            :rows [{:env "$env" :cluster "estate" :service "orders" :id "ecs-svc/12" :source :service-deployments :status "SUCCESSFUL" :rollout-state "COMPLETED"
                    :started #inst "2026-11-15T07:00:00Z" :finished #inst "2026-11-15T07:02:00Z" :revision-before 11 :revision-after 12
                    :image-before "app:aaaaaaaaaaaa" :image-after "app:0123456789ab" :git-sha "0123456789abcdef" :run-id "77"}
                   {:env "$env" :cluster "estate" :service "orders" :id "ecs-svc/11" :source :service-deployments :status "SUCCESSFUL" :rollout-state "COMPLETED"
                    :started #inst "2026-11-14T07:00:00Z" :revision-before 10 :revision-after 11 :image-before "app:bbbbbbbbbbbb" :image-after "app:aaaaaaaaaaaa"}]}""")
        Files.writeString(c.resolve("deploy/estate/orders/ecs-svc-12.txt"), "deploy       estate/orders   ecs-svc/12\n\n== log (1 lines)\n2026-11-15T07:00:05Z [app/abcdef01] started\n")
        Files.writeString(c.resolve("deploy/lambda/orders-fn/list.edn"), """{:env "$env" :function "orders-fn" :kind :lambda :source :lambda :fetched-at #inst "2026-11-15T09:00:00Z"
            :rows [{:id "7" :source :lambda :version "7" :status "Active" :rollout-state "COMPLETED" :started #inst "2026-11-15T07:00:00Z" :code-sha256 "abcdefabcdefabcdef="}
                   {:id "6" :source :lambda :version "6" :status "Active" :rollout-state "FAILED" :rollout-reason "The function failed to update" :started #inst "2026-11-14T07:00:00Z" :code-sha256 "123456123456="}]
            :latest {:state "Active" :update-status "Successful" :code-sha256 "abcdefabcdefabcdef=" :modified "2026-11-15T07:00:00.000+0000"}}""")
    }

    private fun newPanel(): EnvironmentsPanel {
        val p = EnvironmentsPanel(project).also { panel = it }
        wait("the list") { p.envNames().any { it.startsWith("dev") } }
        p.selectByName("dev")
        wait("the deployed rows") { p.deployedRows().size == 2 }
        return p
    }

    fun testAServiceRowShowsItsNewestDeploymentsFromTheCacheWithTheirAge() {
        val p = newPanel()
        p.selectService("orders")
        wait("the deployments") { p.deploymentRows().size == 2 }
        val rows = p.deploymentRows()
        assertEquals(listOf("SUCCESSFUL", "COMPLETED", "2026-11-15 07:00:00 Z", "11 -> 12", "app:aaaaaaaaaaaa -> app:0123456789ab", "0123456789ab", "77", "ecs-svc/12"), rows[0])
        assertEquals("a deployment without a commit shows none", listOf("", ""), rows[1].subList(5, 7))
        val st = p.deploymentsStatus()
        assertTrue(st, st.contains("2 deployment(s)") && st.contains("cached") && st.contains("ago"))
        assertTrue("it says how to ask AWS: $st", st.contains("Refresh Deployments"))
        val t = p.target()!!
        assertEquals(DeployTarget("dev", "estate/orders", "ecs", null), t)
        p.selectDeployment("ecs-svc/12")
        assertEquals("ecs-svc/12", p.target()!!.row!!.id)
    }

    fun testALambdaRowFillsTheSameTableWithItsVersionsAndLastUpdate() {
        val p = newPanel()
        p.selectService("orders-fn")
        wait("the versions") { p.deploymentRows().size == 2 }
        val rows = p.deploymentRows()
        assertEquals(listOf("Active", "COMPLETED", "v7", "abcdefabcdef"), listOf(rows[0][0], rows[0][1], rows[0][3], rows[0][4]))
        assertEquals("the reason of a failed update is in the row", "FAILED: The function failed to update", rows[1][1])
        wait("the latest in the status") { p.deploymentsStatus().contains("latest: Active, update Successful") }
        assertEquals("orders-fn", p.target()!!.spec)
        assertEquals("lambda", p.target()!!.kind)
    }

    fun testANewListIsReconciledByIdAndTheSelectionSurvives() {
        val p = newPanel()
        p.selectService("orders")
        wait("the deployments") { p.deploymentRows().size == 2 }
        p.selectDeployment("ecs-svc/11")
        fun list(vararg rows: String) = DeploymentList.parse("dev", JsonParser.parseString("""{"service":"orders","cluster":"estate","kind":"ecs","rows":[${rows.joinToString(",")}]}""").asJsonObject)
        fun row(id: String, status: String) = """{"id":"$id","status":"$status","rollout-state":"IN_PROGRESS","revision-before":1,"revision-after":2}"""
        // the same ids: only the cells that differ change
        p.renderDeployments(list(row("ecs-svc/12", "SUCCESSFUL"), row("ecs-svc/11", "STOPPED")), "")
        assertEquals("ecs-svc/11", p.selectedDeployment()?.id)
        assertEquals("STOPPED", p.deploymentRows()[1][0])
        // a new deployment on top: the table is refilled, the same deployment is still the selected one
        p.renderDeployments(list(row("ecs-svc/13", "IN_PROGRESS"), row("ecs-svc/12", "SUCCESSFUL"), row("ecs-svc/11", "STOPPED")), "")
        assertEquals(listOf("ecs-svc/13", "ecs-svc/12", "ecs-svc/11"), p.deploymentRows().map { it[7] })
        assertEquals("ecs-svc/11", p.selectedDeployment()?.id)
        assertEquals("an empty answer says why", "none yet", p.also { it.renderDeployments(null, "none yet") }.deploymentsStatus())
    }

    fun testWhereFindsTheDeploymentsOfACommitAndARunWithNoAwsCall() {
        val hits = bg { DeployOps.where(svc, "0123456789ab", null) }
        assertEquals(listOf("ecs-svc/12"), hits.mapNotNull { it.deployment }.take(1))
        assertEquals("dev", hits[0].env)
        assertEquals("estate/orders", hits[0].spec)
        assertEquals(hits.map { it.spec }.distinct(), listOf("estate/orders"))
        assertTrue("a run id finds the same", bg { DeployOps.where(svc, null, "77") }.any { it.deployment == "ecs-svc/12" })
        assertTrue("a short sha finds nothing", bg { DeployOps.where(svc, "012345", null) }.isEmpty())
    }

    fun testTheDeployTextOfAFinishedDeploymentComesFromDiskAndOpensAtItsLogSection() {
        val path = bg { DeployOps.showPath(svc, "dev", "estate/orders", "ecs-svc/12", true) }
        assertTrue(path, path.endsWith("ecs-svc-12.txt"))
        val line = Files.readAllLines(Path.of(path)).indexOfFirst { it.startsWith("== log") }
        assertEquals(2, line)
        val e = runCatching { bg { DeployOps.showPath(svc, "dev", "estate/orders", "ecs-svc/99", false) } }.exceptionOrNull()
        assertNotNull("a deployment that was never shown is fetched, and the profile does not exist", e)
    }

    fun testTheFollowConsoleSaysWhyWhenTheDaemonCannotAskAws() {
        val h = DeployFollowHandler(project, "dev", "estate/orders", null, "Deploy: orders")
        h.begin()
        wait("the follow to end") { h.exit != null }
        assertEquals(2, h.exit)
        val t = h.transcript()
        assertTrue(t, t.startsWith("> deploy.follow service=estate/orders env=dev"))
        assertTrue(t, t.contains("!! "))
        assertNotNull(h.failure)
    }
}
