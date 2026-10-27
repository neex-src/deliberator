package com.neex.deliberator.ci

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.neex.deliberator.daemon.DeliberatorSettings
import com.neex.deliberator.services.FakeOps
import com.neex.deliberator.testing.TempDaemon
import com.neex.deliberator.workspace.WorkspaceService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The flows that run containers of the sandbox's rootless Podman: `ci.local` through a console (act on a fixture workflow whose jobs only print, image debian:stable-slim),
 * and the HTTP fake (record, replay, stop). Opt-in because they own Podman for a while and the core's own act test counts `act-*` containers: run them with
 * `DLB_IDE_PODMAN=1 flock .scratch/runtime.lock bb ide:test --tests '*PodmanFlowsTest'`. Without it each test says so and returns.
 */
class PodmanFlowsTest : BasePlatformTestCase() {
    private lateinit var daemon: TempDaemon
    private lateinit var wsDir: Path
    private val enabled get() = !System.getProperty("deliberator.test.podman").isNullOrEmpty()

    override fun setUp() {
        super.setUp()
        daemon = TempDaemon.create()
        DeliberatorSettings.getInstance().state.home = daemon.home.toString()
        val svc = WorkspaceService.getInstance(project)
        svc.loadState(WorkspaceService.State())
        daemon.openWorkspace("wp")
        svc.bind("wp")
        wsDir = Path.of(bg { svc.call("workspace.show").resultObject().get("path").asString })
        bg { svc.startSession("S1", null) }
    }

    override fun tearDown() {
        try {
            runCatching { bg { WorkspaceService.getInstance(project).call("runtime.down") } }
            DeliberatorSettings.getInstance().state.home = ""
            daemon.close()
        } finally {
            super.tearDown()
        }
    }

    private fun <T> bg(f: () -> T): T = ApplicationManager.getApplication().executeOnPooledThread<T> { f() }.get(300, TimeUnit.SECONDS)
    private fun wait(what: String, secs: Int = 240, cond: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out: $what", cond, secs)

    private fun git(dir: Path, vararg a: String) {
        val p = ProcessBuilder(listOf("git", "-c", "user.email=t@t", "-c", "user.name=t", "-c", "commit.gpgsign=false") + a).directory(dir.toFile()).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) { "git ${a.toList()}: $out" }
    }

    fun testALocalJobRunsInAConsoleHandlerAndItsResultIsTheLastLine() {
        if (!enabled) { println("SKIP PodmanFlowsTest: set DLB_IDE_PODMAN=1 (and hold .scratch/runtime.lock)"); return }
        val dir = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "cil").resolve("proj")
        Files.createDirectories(dir.resolve(".github/workflows"))
        Files.writeString(dir.resolve(".github/workflows/fixture.yml"), """
            name: fixture
            on: workflow_dispatch
            jobs:
              say:
                runs-on: ubuntu-latest
                steps:
                  - run: echo "endpoint=${'$'}AWS_ENDPOINT_URL region=${'$'}AWS_REGION"
              boom:
                runs-on: ubuntu-latest
                steps:
                  - run: exit 3
        """.trimIndent() + "\n")
        git(dir, "init", "-q", "-b", "main"); git(dir, "add", "."); git(dir, "commit", "-qm", "fixture")
        val loc = WorkflowFiles.locate(dir.resolve(".github/workflows/fixture.yml").toString())!!
        assertEquals(dir.toString() to "fixture.yml", loc)
        val image = "docker.io/library/debian:stable-slim"

        val green = bg { CiOps.getInstance(project).local(loc.first, loc.second, "say", image) }
        green.begin()
        wait("the green job ends") { green.isProcessTerminated }
        val g = green.transcript()
        assertEquals(g, 0, green.exit)
        assertTrue(g, g.contains("endpoint=http://localhost:19 region=eu-central-1"))
        assertTrue(g, g.contains("-- ci.local: success (exit 0)"))
        val path = Regex("-- text: (\\S+)").find(g)!!.groupValues[1]
        assertTrue(Files.readString(Path.of(path)).contains("endpoint=http://localhost:19"))

        val red = bg { CiOps.getInstance(project).local(loc.first, loc.second, "boom", image) }
        red.begin()
        wait("the red job ends") { red.isProcessTerminated }
        assertEquals(red.transcript(), 1, red.exit)
        assertTrue(red.transcript(), red.transcript().contains("-- ci.local: failure (exit 1)"))
    }

    fun testStopCancelsTheRequestAndTheProcessEndsWithoutAnAnswer() {
        if (!enabled) { println("SKIP PodmanFlowsTest: set DLB_IDE_PODMAN=1 (and hold .scratch/runtime.lock)"); return }
        val dir = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "cil").resolve("proj")
        Files.createDirectories(dir.resolve(".github/workflows"))
        Files.writeString(dir.resolve(".github/workflows/slow.yml"), "name: slow\non: workflow_dispatch\njobs:\n  nap:\n    runs-on: ubuntu-latest\n    steps:\n      - run: echo sleeping; sleep 300\n")
        git(dir, "init", "-q", "-b", "main"); git(dir, "add", "."); git(dir, "commit", "-qm", "slow")
        val h = bg { CiOps.getInstance(project).local(dir.toString(), "slow.yml", "nap", "docker.io/library/debian:stable-slim") }
        h.begin()
        wait("the job prints") { h.transcript().contains("sleeping") }
        h.destroyProcess()
        wait("the process ends") { h.isProcessTerminated }
        assertEquals(h.transcript(), 130, h.exit)
        assertTrue(h.transcript(), h.transcript().contains("-- stopped"))
    }

    fun testAFakeRecordsReplaysAndStopsThroughTheOps() {
        if (!enabled) { println("SKIP PodmanFlowsTest: set DLB_IDE_PODMAN=1 (and hold .scratch/runtime.lock)"); return }
        val ops = FakeOps.getInstance(project)
        assertEquals(emptyList<String>(), bg { ops.list() }.map { it.name })
        // a fake in front of an URL nobody serves: it records nothing but is declared, started, listed and stopped
        val f = bg { ops.record("up", "http://localhost:9") }
        assertEquals("record", f.mode)
        assertNotNull(f.url)
        val listed = bg { ops.list() }.single()
        assertEquals("up", listed.name)
        assertTrue(listed.recordings.isNotEmpty())
        val r = bg { ops.replay("up", listed.recordings.last()) }
        assertEquals("replay", r.mode)
        bg { ops.stop("up") }
        assertEquals("stopped", bg { ops.list() }.single().mode)
    }
}
