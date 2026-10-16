package com.neex.deliberator.services

import com.google.gson.JsonObject
import com.intellij.execution.RunManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.daemon.DeliberatorSettings
import com.neex.deliberator.testing.TempDaemon
import com.neex.deliberator.workspace.WorkspaceService
import org.jdom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Piece 08b: the "take secret values from <environment>" checkbox of the Deliberator Service run configuration and the two confirmations of
 * `service.start` against the real daemon on a temp home. The service `q` of the config-chain fixture carries secret references, so the daemon
 * answers token 1 and token 2 without any Floci; the confirmer is replaced as `ScnRunner.confirmer` is in its tests. A start that goes on after
 * token 2 needs the estate's second environment and a Floci: here it ends in whatever the missing Floci says, and `runtime.edn` holds no containers so
 * nothing can be created.
 */
class SecretsCheckboxTest : BasePlatformTestCase() {
    private lateinit var daemon: TempDaemon
    private lateinit var svc: WorkspaceService
    private lateinit var ops: ServiceOps

    override fun setUp() {
        super.setUp()
        daemon = TempDaemon.create()
        DeliberatorSettings.getInstance().state.home = daemon.home.toString()
        svc = WorkspaceService.getInstance(project)
        svc.loadState(WorkspaceService.State())
        daemon.openWorkspace("w8")
        svc.bind("w8")
        ops = ServiceOps.getInstance(project)
        val root = daemon.home.resolve("cfg")
        copyTree(Path.of(System.getProperty("deliberator.testdata"), "config"), root)
        val wsDir = Path.of(bg { svc.call("workspace.show").resultObject().get("path").asString })
        Files.writeString(wsDir.resolve("runtime.edn"), """{:network "deliberator" :containers {}}""")
        bg { svc.call("repo.attach", args("path" to root.resolve("repo").toString())) }
        for (s in listOf("q", "ok"))
            bg { svc.call("service.adopt", args("path" to root.resolve("repo/$s").toString(), "taskdef" to root.resolve("rendered-$s.json").toString())) }
    }

    override fun tearDown() {
        try {
            ops.confirmer = { false }
            val rm = RunManager.getInstance(project)
            rm.allSettings.filter { it.type.id == ServiceRunConfigurationType.ID }.forEach { rm.removeConfiguration(it) }
            DeliberatorSettings.getInstance().state.home = ""
            daemon.close()
        } finally {
            super.tearDown()
        }
    }

    private fun <T> bg(f: () -> T): T = ApplicationManager.getApplication().executeOnPooledThread<T> { f() }.get(120, TimeUnit.SECONDS)
    private fun args(vararg kv: Pair<String, String>) = JsonObject().apply { kv.forEach { addProperty(it.first, it.second) } }
    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { s -> s.forEach { p ->
            val t = to.resolve(from.relativize(p).toString())
            if (Files.isDirectory(p)) Files.createDirectories(t) else { Files.createDirectories(t.parent); Files.copy(p, t) }
        } }
    }

    private fun startWith(name: String, env: String?): Result<Started> = try {
        Result.success(bg { ops.start(name, JsonObject().apply { env?.let { addProperty("secrets-from", it) } }) })
    } catch (e: Throwable) {
        Result.failure(generateSequence(e) { it.cause }.filterIsInstance<DaemonException>().firstOrNull() ?: e)
    }

    private fun type(r: Result<Started>) = (r.exceptionOrNull() as? DaemonException)?.type ?: r.exceptionOrNull()?.toString()

    private fun statusOf(name: String) = bg { svc.refreshNow() }.services.single { it.name == name }.status

    fun testBothConfirmationsAreAskedInOrderWithTheDaemonsTextAndTheThirdCallCarriesTokenTwo() {
        val seen = mutableListOf<ServiceOps.SecretsConfirmation>()
        ops.confirmer = { seen += it; true }
        val r = startWith("q", "local")
        assertEquals(2, seen.size)
        assertEquals(listOf(1, 2), seen.map { it.step })
        assertEquals(listOf(2, 2), seen.map { it.of })
        assertEquals("local", seen[0].env)
        assertTrue(seen[0].summary, seen[0].summary.contains("REAL secret values") && seen[0].summary.contains("local"))
        assertTrue(seen[1].summary, seen[1].summary.contains("podman inspect"))
        assertNotSame(seen[0].token, seen[1].token)
        // both given: the daemon went on (token 2 was accepted: not refused, not asked a third time) and failed at the missing Floci / environment
        assertTrue("after token 2 the start is not asked again or refused: ${type(r)}", r.isFailure && type(r) != "confirm-required" && type(r) != "service/confirmation-refused" && type(r) != "service/cancelled")
        assertEquals("nothing runs", "absent", statusOf("q"))
    }

    fun testCancellingAtEitherConfirmationStartsNothing() {
        for (cancelAt in 1..2) {
            val asked = mutableListOf<Int>()
            ops.confirmer = { asked += it.step; it.step != cancelAt }
            val r = startWith("q", "local")
            assertEquals("service/cancelled", type(r))
            assertTrue(r.exceptionOrNull()!!.message!!.contains("nothing was started"))
            assertEquals((1..cancelAt).toList(), asked)
            assertEquals("absent", statusOf("q"))
        }
    }

    fun testABogusConfirmTokenIsRefusedByTheDaemonAndRendersReadably() {
        ops.confirmer = { error("no dialog expected: the daemon refuses before asking") }
        val r = try {
            bg { ops.start("q", JsonObject().apply { addProperty("secrets-from", "local"); addProperty("confirm", "bogus") }) }
            null
        } catch (e: Throwable) { generateSequence(e) { it.cause }.filterIsInstance<DaemonException>().first() }
        assertEquals("service/confirmation-refused", r!!.type)
        assertTrue(r.readable(), r.readable().startsWith("service/confirmation-refused: ") && r.readable().contains("hint:"))
    }

    fun testAServiceWithoutSecretReferencesIsRefusedBeforeAnyDialog() {
        val dir = daemon.home.resolve("plain")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("deliberator-service.edn"), """{:name "plain" :kind :spring-boot :build {:cmd ["true"] :dir "." :artifact "x.jar" :java 21}
            :image "docker.io/library/eclipse-temurin:21-jre" :port 9002 :health "/actuator/health"}""")
        bg { ops.adopt(dir.toString()) }
        ops.confirmer = { error("no dialog expected") }
        assertEquals("service/no-secrets", type(startWith("plain", "local")))
    }

    fun testWithoutTheCheckboxNoConfirmationIsEverAsked() {
        var asked = 0
        ops.confirmer = { asked++; true }
        val r = startWith("ok", null)
        assertEquals("nothing to confirm", 0, asked)
        // ok has no jar: whatever the plain start says, it is not a confirmation matter
        assertTrue(type(r) != "confirm-required" && type(r) != "service/cancelled")
    }

    fun testTheCheckboxIsNeverPersistedAndSurvivesCloning() {
        val s = ServiceActions.configurationFor(project, "q")
        val cfg = s.configuration as ServiceRunConfiguration
        assertNull("off by default", cfg.secretsFrom)
        assertTrue(cfg.startArgs().entrySet().isEmpty())
        cfg.secretsFrom = "local2"
        assertEquals("local2", cfg.startArgs().get("secrets-from").asString)
        val xml = Element("configuration")
        cfg.writeExternal(xml)
        val text = org.jdom.output.XMLOutputter().outputString(xml)
        assertFalse(text, text.contains("local2") || text.lowercase().contains("secret"))
        val back = cfg.factory!!.createTemplateConfiguration(project) as ServiceRunConfiguration
        back.readExternal(xml)
        assertEquals("q", back.serviceName)
        assertNull("a reloaded configuration asks for nothing", back.secretsFrom)
        assertTrue(back.startArgs().entrySet().isEmpty())
        assertEquals("a platform clone keeps the session's choice", "local2", (cfg.clone() as ServiceRunConfiguration).secretsFrom)
    }

    fun testTheEditorRoundTripsTheCheckboxAndItsEnvironment() {
        bg { svc.refreshNow() }
        val cfg = ServiceActions.configurationFor(project, "q").configuration as ServiceRunConfiguration
        val editor = ServiceRunEditor(project)
        try {
            assertNotNull(editor.component)
            editor.resetFrom(cfg)
            assertFalse("combo disabled while the sandbox is off", editor.secretsEnabled())
            editor.setSecrets("local2")
            assertTrue(editor.secretsEnabled())
            editor.applyTo(cfg)
            assertEquals("local2", cfg.secretsFrom)
            editor.setSecrets(null)
            editor.applyTo(cfg)
            assertNull(cfg.secretsFrom)
            cfg.secretsFrom = "staging"
            editor.resetFrom(cfg)
            assertTrue(editor.secretsEnabled())
        } finally {
            editor.dispose()
        }
    }

    fun testTheConfirmationDialogShowsTheSummaryVerbatimAndTheStepOnItsButton() {
        val c = ServiceOps.SecretsConfirmation("t", "Start q with the REAL secret values of environment local2\n  API_KEY <- secretsmanager estate/api-key\nConfirmation 1 of 2.", 1, 2, "local2")
        val d = SecretsConfirmDialog(project, c)
        try {
            assertEquals("Confirm 1 of 2", d.confirmLabel)
            assertEquals(c.summary, d.shownText())
            assertEquals("Real secret values from local2", d.title)
        } finally {
            d.disposeIfNeeded()
        }
    }

    fun testTheProcessHandlerSaysWhichEnvironmentsValuesTheContainerGot() {
        // a handler whose start is replaced by the real daemon's refusal path can only show the failure; the success line is checked on the pure note instead
        ops.confirmer = { false }
        val h = ServiceProcessHandler(project, "q", "local", JsonObject().apply { addProperty("secrets-from", "local") }, null, false)
        val text = StringBuffer()
        h.addProcessListener(object : com.intellij.execution.process.ProcessAdapter() {
            override fun onTextAvailable(event: com.intellij.execution.process.ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) { text.append(event.text) }
        })
        h.begin()
        com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("process ends", { h.isProcessTerminated }, 60)
        assertTrue(h.transcript(), h.transcript().contains("service.start failed - service/cancelled"))
        assertTrue(h.transcript(), h.transcript().contains("nothing was started"))
        assertEquals(1, h.exitCode)
    }
}
