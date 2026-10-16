package com.neex.deliberator.services

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.neex.deliberator.daemon.DaemonEvent
import com.neex.deliberator.daemon.DeliberatorSettings
import com.neex.deliberator.testing.TempDaemon
import com.neex.deliberator.workspace.ServiceRow
import com.neex.deliberator.workspace.WorkspaceModel
import com.neex.deliberator.workspace.WorkspaceService
import org.jdom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.jar.Attributes
import javax.tools.ToolProvider

/**
 * Piece 06 against the real daemon on a temp home. Headless part (no container): the tab's rows from `service.list`, the topology switch through
 * the ops, the run configuration (persistence, editor, runners), the reload rendering, the console links, the process handler's
 * failure path. Container part: a stub service (a 15-line HttpServer jar compiled here, on the plain temurin image, host ports 19181/19185
 * in `runtime.edn :services`, no infrastructure containers) runs through the configuration: start, tail through ONE `logs.fetch {follow true}`, the debug
 * state's `RemoteConnection`, reload into the console, Stop. It skips with the reason when the container engine is unreachable or there is no
 * compiler; `runtime.down` in `tearDown` removes the container.
 */
class ServicesTest : BasePlatformTestCase() {
    private lateinit var daemon: TempDaemon
    private lateinit var svc: WorkspaceService
    private lateinit var wsDir: Path
    private val panels = mutableListOf<ServicesPanel>()

    override fun setUp() {
        super.setUp()
        daemon = TempDaemon.create()
        DeliberatorSettings.getInstance().state.home = daemon.home.toString()
        svc = WorkspaceService.getInstance(project)
        svc.loadState(WorkspaceService.State())
        daemon.openWorkspace("w6")
        svc.bind("w6")
        ServiceProcessHandler.retryMs = 300
        wsDir = Path.of(bg { svc.call("workspace.show").resultObject().get("path").asString })
    }

    override fun tearDown() {
        try {
            panels.forEach { com.intellij.openapi.util.Disposer.dispose(it) }
            val rm = RunManager.getInstance(project)
            rm.allSettings.filter { it.type.id == ServiceRunConfigurationType.ID }.forEach { rm.removeConfiguration(it) }
            runCatching { bg { svc.call("runtime.down") } }
            ServiceProcessHandler.retryMs = 5000
            DeliberatorSettings.getInstance().state.home = ""
            daemon.close()
        } finally {
            super.tearDown()
        }
    }

    // helpers -------------------------------------------------------------------------------------------------

    private fun <T> bg(f: () -> T): T = ApplicationManager.getApplication().executeOnPooledThread<T> { f() }.get(180, TimeUnit.SECONDS)
    private fun args(vararg kv: Pair<String, String>) = JsonObject().apply { kv.forEach { addProperty(it.first, it.second) } }
    private fun wait(what: String, secs: Int = 60, cond: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out: $what", cond, secs)
    private fun ops() = ServiceOps.getInstance(project)

    /** The `ok` fixture of ConfigChainTest: adopted from its description, no container needed for list/show/status. */
    private fun adoptFixture(): ServiceRow {
        val dir = daemon.home.resolve("fixture")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("deliberator-service.edn"), """
            {:name "ok" :kind :spring-boot :build {:cmd ["mvn" "-q" "package"] :dir "." :artifact "target/ok.jar" :java 21}
             :image "docker.io/library/eclipse-temurin:21-jre" :port 9001 :jdwp 5005 :health "/actuator/health"}
        """.trimIndent())
        bg { ops().adopt(dir.toString()) }
        return bg { svc.refreshNow() }.services.single { it.name == "ok" }
    }

    private fun captured(h: com.intellij.execution.process.ProcessHandler): StringBuffer {
        val sb = StringBuffer()
        h.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) { sb.append(event.text) }
        })
        return sb
    }

    private fun panel(): ServicesPanel = ServicesPanel(project).also { panels += it }

    // registration ----------------------------------------------------------------------------------------------

    fun testActionsTypeAndRunnersAreRegistered() {
        val am = ActionManager.getInstance()
        for (id in listOf("service.start", "service.stop", "service.restart", "service.reload", "service.logs", "service.debug", "service.build", "service.adopt", "topology.set"))
            assertNotNull("deliberator.$id", am.getAction("deliberator.$id"))
        assertNotNull(am.getAction("deliberator.services"))
        val type = ConfigurationTypeUtil.findConfigurationType(ServiceRunConfigurationType::class.java)
        assertEquals("Deliberator Service", type.displayName)
        val cfg = ServiceActions.configurationFor(project, "ok").configuration as ServiceRunConfiguration
        // the java plugin's generic debugger would refuse the configuration (not a ModuleRunProfile): ours takes it
        assertTrue(ProgramRunner.getRunner(DefaultDebugExecutor.EXECUTOR_ID, cfg) is ServiceDebugRunner)
        assertNotNull(ProgramRunner.getRunner(DefaultRunExecutor.EXECUTOR_ID, cfg))
    }

    fun testALoggingBreakpointIsTheNonSuspendingLineBreakpointOfThePlatform() {
        val f = myFixture.configureByText("A.java", "class A {\n    void m() {\n        int x = 1;\n    }\n}\n")
        assertNotNull(ActionManager.getInstance().getAction("deliberator.marker.logpoint"))
        val bp = Logpoints.add(project, f.virtualFile, 2, "\"hit \" + 1")
        try {
            assertEquals(com.intellij.xdebugger.breakpoints.SuspendPolicy.NONE, bp.suspendPolicy)
            assertTrue(bp.isLogMessage)
            assertEquals("\"hit \" + 1", bp.logExpressionObject?.expression)
            assertEquals(2, bp.line)
            assertTrue(com.intellij.xdebugger.XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints.contains(bp))
        } finally {
            com.intellij.openapi.application.runWriteAction { com.intellij.xdebugger.XDebuggerManager.getInstance(project).breakpointManager.removeBreakpoint(bp) }
        }
    }

    // the tab -------------------------------------------------------------------------------------------------------

    fun testTheTabRendersServiceListWithModeStatusAndPorts() {
        val row = adoptFixture()
        assertEquals("local", row.mode)
        assertEquals("absent", row.status)
        assertEquals(9001, row.port)
        assertEquals(5005, row.jdwp)
        val p = panel()
        p.render(svc.model)
        assertEquals(1, p.table.rowCount)
        assertEquals(listOf("ok", "local", "absent", "9001", "5005", ""), (0 until 6).map { p.table.getValueAt(0, it).toString() })
        assertEquals(ServicesPanel.COLUMNS.toList(), (0 until p.table.columnCount).map { p.table.getColumnName(it) })
        p.table.setRowSelectionInterval(0, 0)
        assertEquals("ok", p.selected()?.name)
    }

    fun testTheTabFollowsAWorkspaceWithoutServices() {
        bg { svc.refreshNow() }
        val p = panel()
        p.render(svc.model)
        assertEquals(0, p.table.rowCount)
    }

    fun testTopologySwitchThroughTheOps() {
        adoptFixture()
        bg { ops().setTopology("ok", "off") }
        var r = svc.model.services.single()
        assertEquals("off", r.mode)
        assertEquals("off", r.status)
        val p = panel(); p.render(svc.model)
        assertEquals("off", p.table.getValueAt(0, 1))

        bg { ops().setTopology("ok", "external", "http://127.0.0.1:18089", "http://x:8081", "x-container") }
        r = svc.model.services.single()
        assertEquals("external", r.mode)
        assertEquals("http://127.0.0.1:18089", r.url)
        p.render(svc.model)
        assertEquals("external (http://127.0.0.1:18089)", p.table.getValueAt(0, 1))
        val shown = bg { svc.call("topology.show", args("env" to "local")).resultObject() }
        assertTrue(shown.toString(), shown.toString().contains("18089"))

        bg { ops().setTopology("ok", "local") }
        assertEquals("local", svc.model.services.single().mode)
    }

    fun testExternalWithoutAUrlIsTheDaemonsTypedError() {
        adoptFixture()
        val e = try { bg { ops().setTopology("ok", "external") }; null } catch (x: Throwable) { generateSequence(x) { it.cause }.filterIsInstance<com.neex.deliberator.daemon.DaemonException>().firstOrNull() }
        assertNotNull("typed error", e)
        assertEquals("local", bg { svc.refreshNow() }.services.single().mode)
    }

    // the run configuration --------------------------------------------------------------------------------------------

    fun testTheConfigurationPersistsItsTwoSettingsAndTheEditorRoundTrips() {
        val s = ServiceActions.configurationFor(project, "ok")
        val cfg = s.configuration as ServiceRunConfiguration
        assertEquals("Deliberator Service: ok", s.name)
        cfg.envName = "staging"
        val xml = Element("configuration")
        cfg.writeExternal(xml)
        val back = cfg.factory!!.createTemplateConfiguration(project) as ServiceRunConfiguration
        back.readExternal(xml)
        assertEquals("ok", back.serviceName)
        assertEquals("staging", back.envName)
        assertEquals("staging", back.effectiveEnv())
        back.envName = ""
        assertEquals("the project's selected environment", svc.selectedEnv, back.effectiveEnv())
        assertTrue(back.startArgs().entrySet().isEmpty())
        // the same configuration is found again, not duplicated
        assertSame(s, ServiceActions.configurationFor(project, "ok"))

        val blank = cfg.factory!!.createTemplateConfiguration(project) as ServiceRunConfiguration
        assertTrue(try { blank.checkConfiguration(); false } catch (e: RuntimeConfigurationError) { true })

        val editor = ServiceRunEditor(project)
        try {
            assertNotNull(editor.component)
            editor.resetFrom(cfg)
            val other = cfg.factory!!.createTemplateConfiguration(project) as ServiceRunConfiguration
            editor.setService("other")
            editor.applyTo(other)
            assertEquals("other", other.serviceName)
            assertEquals("staging", other.envName)
            assertNotNull("room for piece 08b's row", editor.extraRows)
        } finally {
            editor.dispose()
        }
    }

    fun testADebugStateWithoutAStartOrWithoutJdwpFailsWithWords() {
        val cfg = ServiceActions.configurationFor(project, "ok").configuration as ServiceRunConfiguration
        val env = ExecutionEnvironmentBuilder.create(DefaultDebugExecutor.getDebugExecutorInstance(), cfg).build()
        val state = cfg.getState(DefaultDebugExecutor.getDebugExecutorInstance(), env) as ServiceRunState
        assertTrue(try { state.remoteConnection; false } catch (e: com.intellij.execution.ExecutionException) { e.message!!.contains("not started") })
        state.prestarted = Started("ok", "running", "c", "8081", null)
        assertTrue(try { state.remoteConnection; false } catch (e: com.intellij.execution.ExecutionException) { e.message!!.contains(":jdwp") })
        state.prestarted = Started("ok", "running", "c", "8081", "15005")
        val c = state.remoteConnection
        assertEquals("localhost", c.hostName)
        assertEquals("15005", c.address)
        assertTrue(c.isUseSockets)
        assertFalse("attach, not listen", c.isServerMode)
    }

    fun testStartingAServiceTheTopologyTurnedOffEndsTheProcessWithTheTypedError() {
        adoptFixture()
        bg { ops().setTopology("ok", "off") }
        val h = ServiceProcessHandler(project, "ok", "local", JsonObject(), null, false)
        val text = captured(h)
        h.begin()
        wait("the process ends") { h.isProcessTerminated }
        assertTrue(text.toString(), text.contains("topology/service-not-local"))
        assertTrue(text.toString(), text.contains("dlb topology set local ok local"))
        assertEquals(1, h.exitCode)
        assertNull("a finished handler is not offered to Reload", ServiceConsoles.getInstance(project).of("ok"))
    }

    // reload ----------------------------------------------------------------------------------------------------------

    private fun json(s: String) = JsonParser.parseString(s).asJsonObject

    fun testReloadRenderingOfStepsResultFallbackAndCompileErrors() {
        assertEquals("reload: compiling - mvn -o -q compile", ReloadRender.step(DaemonEvent("service.reload.step", json("""{"step":"compiling","detail":"mvn -o -q compile"}"""))))
        assertEquals("reload: changed - changed 2  [a/A.class, a/B.class]", ReloadRender.step(json("""{"step":"changed","detail":"changed 2","n":2,"files":["a/A.class","a/B.class"]}""")))
        val ok = ReloadRender.result(json("""{"name":"a","mechanism":"hotswap-agent","changed":["x","y"],"redefined":["X"],"took-ms":3400,"reload-count":3,"log":"/l/r.log"}"""))
        assertEquals("reload: done - 2 file(s) changed, 1 class(es) redefined in 3400 ms (hotswap-agent, in-place reload #3)", ok[0])
        assertEquals(listOf("reload: log /l/r.log"), ok.drop(1))
        val fb = ReloadRender.result(json("""{"mechanism":"jdwp","changed":["x"],"redefined":[],"fallback":"field added: the JVM cannot take it"}"""))
        assertTrue(fb.toString(), fb.any { it == "reload: FELL BACK TO RESTART - field added: the JVM cannot take it" })

        val f = ReloadRender.failure(json("""{"type":"service/compile-failed","message":"compile of a failed: A.java:12 boom","hint":"fix and reload","log":"/l/c.log",
            "errors":[{"file":"/src/A.java","line":12,"col":5,"message":"cannot find symbol"},{"file":"/src/B.java","line":3,"message":"';' expected"}]}"""))
        assertEquals("service/compile-failed", f.type)
        assertEquals(listOf(CompileError("/src/A.java", 12, 5, "cannot find symbol"), CompileError("/src/B.java", 3, null, "';' expected")), f.errors)
        assertEquals(listOf("reload: FAILED (service/compile-failed) compile of a failed: A.java:12 boom", "  /src/A.java:12:5: cannot find symbol",
            "  /src/B.java:3: ';' expected", "  hint: fix and reload", "  log: /l/c.log"), f.lines)
        val g = ReloadRender.failure(json("""{"type":"service/not-running","message":"a is not running","hint":"service.start a"}"""))
        assertEquals(listOf("reload: FAILED (service/not-running) a is not running", "  hint: service.start a"), g.lines)
        assertTrue(g.errors.isEmpty())
    }

    fun testCompileErrorPositionsInAConsoleAreLinks() {
        val src = daemon.home.resolve("A.java")
        Files.writeString(src, "class A {}\n")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(src)!!
        val f = CompileLinkFilter(project)
        val line = "  $src:1:7: cannot find symbol"
        val r = f.applyFilter(line, 100 + line.length)!!
        val item = r.resultItems.single()
        assertTrue(item.hyperlinkInfo is OpenFileHyperlinkInfo)
        assertEquals("$src:1:7", line.substring(item.highlightStartOffset - 100, item.highlightEndOffset - 100))
        assertNull(f.applyFilter("nothing to link here: 12", 24))
        assertNull("a file that does not exist", f.applyFilter("/no/such/File.java:3", 20))
    }

    fun testReloadOfAServiceThatIsNotRunningIsARenderedFailureNotAnException() {
        adoptFixture()
        val out = mutableListOf<String>()
        val o = bg { ops().reload("ok") { out += it } }
        assertFalse(o.ok)
        assertNotNull(o.failure)
        assertTrue(o.lines.toString(), o.lines.first().startsWith("reload: FAILED (service/"))
        assertEquals("every line also reached the sink", o.lines, out)
    }

    // a real container ----------------------------------------------------------------------------------------------------

    /** The JVM's own compiler when it has one (the platform's runtime usually does not), else the `javac` on the PATH. */
    private fun compile(out: Path, src: Path): Boolean {
        ToolProvider.getSystemJavaCompiler()?.let { return it.run(null, null, null, "--release", "17", "-d", out.toString(), src.toString()) == 0 }
        return try {
            ProcessBuilder("javac", "--release", "17", "-d", out.toString(), src.toString()).redirectErrorStream(true).start().let { p ->
                p.inputStream.readAllBytes(); p.waitFor() == 0
            }
        } catch (e: java.io.IOException) { false }
    }

    /** Compiles the stub and jars it; null when there is no Java compiler. */
    private fun buildStub(dir: Path): Path? {
        val src = dir.resolve("Stub.java")
        Files.createDirectories(dir)
        Files.writeString(src, """
            import com.sun.net.httpserver.HttpServer;
            import java.net.InetSocketAddress;
            public class Stub {
                public static void main(String[] a) throws Exception {
                    HttpServer s = HttpServer.create(new InetSocketAddress(8080), 0);
                    s.createContext("/", ex -> { byte[] b = "{\"status\":\"UP\"}".getBytes(); ex.getResponseHeaders().add("Content-Type", "application/json");
                        ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
                    s.start();
                    System.out.println("stub started");
                    for (int i = 0; ; i++) { Thread.sleep(1000); System.out.println("stub tick " + i); }
                }
            }
        """.trimIndent())
        val out = dir.resolve("out")
        Files.createDirectories(out)
        if (!compile(out, src)) return null
        val jar = dir.resolve("stub.jar")
        val mf = Manifest().apply { mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"; mainAttributes[Attributes.Name.MAIN_CLASS] = "Stub" }
        JarOutputStream(Files.newOutputStream(jar), mf).use { j ->
            j.putNextEntry(java.util.jar.JarEntry("Stub.class")); j.write(Files.readAllBytes(out.resolve("Stub.class"))); j.closeEntry()
            for (extra in Files.list(out).use { it.filter { p -> p.fileName.toString().startsWith("Stub$") }.toList() }) {
                j.putNextEntry(java.util.jar.JarEntry(extra.fileName.toString())); j.write(Files.readAllBytes(extra)); j.closeEntry()
            }
        }
        Files.writeString(dir.resolve("deliberator-service.edn"), """
            {:name "stub" :kind :spring-boot :build {:cmd ["true"] :dir "." :artifact "stub.jar" :java 21}
             :image "docker.io/library/eclipse-temurin:21-jre" :port 8080 :jdwp 5005 :health "/actuator/health"}
        """.trimIndent())
        return dir
    }

    fun testRunConfigurationStartsTailsReloadsAndStopsAStubService() {
        val engine = bg { svc.refreshNow() }.engineReachable
        if (engine != true) { println("SKIPPED testRunConfigurationStartsTailsReloadsAndStopsAStubService: the container engine is not reachable (${svc.model.engineHint})"); return }
        val dir = buildStub(daemon.home.resolve("stub")) ?: run { println("SKIPPED testRunConfigurationStartsTailsReloadsAndStopsAStubService: this JVM has no Java compiler"); return }
        // no infrastructure containers, nothing on 4566/5432/8081/5005: only the stub, on its own host ports
        Files.writeString(wsDir.resolve("runtime.edn"), """{:network "deliberator" :containers {} :services {"stub" {:ports {8080 19181 5005 19185}}}}""")
        bg { ops().adopt(dir.toString()) }
        bg { svc.startSession("T-6", null) }

        val s = ServiceActions.configurationFor(project, "stub")
        val cfg = s.configuration as ServiceRunConfiguration
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val env = ExecutionEnvironmentBuilder.create(executor, cfg).build()
        val runner = ProgramRunner.getRunner(DefaultRunExecutor.EXECUTOR_ID, cfg)!!
        val state = cfg.getState(executor, env) as ServiceRunState
        val result = state.execute(executor, runner)
        val h = result.processHandler as ServiceProcessHandler
        val text = captured(h)
        try {
            wait("the container's own log lines reach the console through logs.fetch for", 120) { text.contains("stub tick") }
            // the handler's transcript has what the console got before this test's listener was attached
            val t = h.transcript()
            assertTrue(t, t.contains("> service.start stub"))
            assertTrue(t, t.contains("service stub running"))
            assertTrue(t, t.contains("port 19181") && t.contains("JDWP 19185"))
            assertTrue(t, t.contains("stub started"))
            assertSame("the running configuration's console is the reload target", h, ServiceConsoles.getInstance(project).of("stub"))

            val row = bg { svc.refreshNow() }.services.single { it.name == "stub" }
            assertEquals("running", row.status)
            assertEquals("19181", row.hostPort)
            assertEquals("19185", row.hostJdwp)
            val p = panel(); p.render(svc.model)
            assertEquals("running", p.table.getValueAt(0, 2))
            assertEquals("19181:8080", p.table.getValueAt(0, 3))
            assertEquals("19185:5005", p.table.getValueAt(0, 4))

            // Reload writes into this console (the stub has no :reload command: a typed failure, rendered)
            val o = bg { ServiceActions.reload(project, "stub") }
            assertFalse(o.ok)
            wait("reload lines in the console") { text.contains("reload: FAILED (service/") }
            assertTrue(text.toString(), text.contains("> service.reload stub"))

            // Debug: what service.start answered is where the platform attaches
            val dbgEnv = ExecutionEnvironmentBuilder.create(DefaultDebugExecutor.getDebugExecutorInstance(), cfg).build()
            val dbg = cfg.getState(DefaultDebugExecutor.getDebugExecutorInstance(), dbgEnv) as ServiceRunState
            dbg.prestarted = bg { ops().start("stub") }
            val conn = dbg.remoteConnection
            assertEquals("localhost", conn.hostName)
            assertEquals("19185", conn.address)
            assertFalse(conn.isServerMode)
            assertTrue(ProgramRunner.getRunner(DefaultDebugExecutor.EXECUTOR_ID, cfg) is ServiceDebugRunner)
        } finally {
            h.destroyProcess()
        }
        wait("Stop ends the process") { h.isProcessTerminated }
        assertTrue(text.toString(), text.contains("> service.stop stub"))
        assertEquals(0, h.exitCode)
        assertEquals("stopped", bg { svc.refreshNow() }.services.single { it.name == "stub" }.status)
        assertNull(ServiceConsoles.getInstance(project).of("stub"))
    }

    fun testADebugSessionsStopDetachesAndLeavesTheServiceRunning() {
        val engine = bg { svc.refreshNow() }.engineReachable
        if (engine != true) { println("SKIPPED testADebugSessionsStopDetachesAndLeavesTheServiceRunning: the container engine is not reachable"); return }
        val dir = buildStub(daemon.home.resolve("stub")) ?: run { println("SKIPPED testADebugSessionsStopDetachesAndLeavesTheServiceRunning: no Java compiler"); return }
        Files.writeString(wsDir.resolve("runtime.edn"), """{:network "deliberator" :containers {} :services {"stub" {:ports {8080 19181 5005 19185}}}}""")
        bg { ops().adopt(dir.toString()) }
        bg { svc.startSession("T-6", null) }
        val started = bg { ops().start("stub") }
        val h = ServiceProcessHandler(project, "stub", "local", JsonObject(), started, true)
        val text = captured(h)
        assertTrue("a debug session's Stop is a detach", h.detachIsDefault())
        h.begin()
        wait("tail") { text.contains("stub tick") }
        val followFiles = Files.list(wsDir.resolve("sessions").resolve("T-6").resolve("logs")).use { st -> st.filter { it.fileName.toString().startsWith("log-service-stub") }.count() }
        assertEquals("one follow call for the whole session: one file, not one per window", 1L, followFiles)
        h.detachProcess()
        wait("detached") { h.isProcessTerminated }
        assertTrue(text.toString(), text.contains("debugger detached; the service keeps running"))
        assertEquals("running", bg { svc.refreshNow() }.services.single { it.name == "stub" }.status)
    }
}
