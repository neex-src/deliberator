package com.neex.deliberator.daemon

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** A connected, version-checked daemon: [hello] is its answer to our handshake. */
class DaemonConnection(
    val client: DaemonClient,
    val hello: Hello,
    val home: Path,
    /** The daemon process was started by this connect. */
    val started: Boolean,
    /** An older daemon was stopped and replaced by the shipped jar. */
    val restarted: Boolean,
) {
    /** Text for the `Daemon status` action. */
    fun describe(): String = buildString {
        appendLine("deliberatord ${hello.version} (pid ${hello.pid})")
        appendLine("endpoint: ${hello.endpoint}")
        appendLine("home: ${hello.home}")
        appendLine("runs in flight: ${hello.runs}")
        append(if (restarted) "replaced an older daemon" else if (started) "started now" else "already running, reused")
    }
}

/**
 * Finding, starting and handshaking with `deliberatord`, the Kotlin twin of `dlb.endpoint` (same
 * protocol, same rules). The only knowledge of the daemon's files is the discovery contract:
 * `<home>/daemon/endpoint` (one line), and `<home>/daemon/daemon.out` for the output of a daemon we
 * start. No platform classes: the lifecycle is testable without an IDE.
 *
 * [connectCompatible] connects (starting the daemon from [jar] on `[javaHome]/bin/java` when none
 * answers), sends `hello` with the jar's version, and on `compatible:false` replaces the daemon once
 * when the jar is newer and no run is in flight (`daemon.stop`, SIGTERM to the announced pid when
 * the op is unknown, wait until gone, start, hello again); a newer daemon or runs in flight is a
 * [DaemonException] `daemon/incompatible` with a hint. A daemon is otherwise never killed.
 */
class DaemonLauncher(
    private val javaHome: String,
    private val jar: Path?,
    private val extraArgs: List<String> = emptyList(),
    private val startTimeoutMs: Long = 10_000,
    private val stopTimeoutMs: Long = 20_000,
) {
    fun endpointFile(home: Path): Path = home.resolve("daemon").resolve("endpoint")

    fun readEndpoint(home: Path): String? =
        endpointFile(home).takeIf { Files.isRegularFile(it) }?.let { Files.readString(it).trim() }?.ifEmpty { null }

    /** The version recorded in the jar's `deliberator/version.edn`, or null. */
    fun jarVersion(): String? = jar?.let { jarVersion(it) }

    /** A client for the daemon of [home] when it answers `hello`, else null; never starts anything. */
    fun connectExisting(home: Path): Pair<DaemonClient, Hello>? {
        val ep = readEndpoint(home) ?: return null
        return try {
            val c = DaemonClient(ep)
            c to c.hello()
        } catch (_: DaemonException) {
            null
        }
    }

    /** Starts the daemon detached; returns at once. */
    fun startDaemon(home: Path) {
        val jarPath = jar?.takeIf { Files.isRegularFile(it) } ?: throw DaemonException(
            "no daemon jar" + (jar?.let { " at $it" } ?: ""), "daemon/no-jar",
            "the plugin zip carries lib/deliberatord.jar; from a checkout run `bb core:build` or set DELIBERATOR_JAR",
        )
        val daemonDir = Files.createDirectories(home.resolve("daemon"))
        val out = daemonDir.resolve("daemon.out").toFile()
        val setsid = onPath("setsid")
        val cmd = buildList {
            // Own session where `setsid` exists (Linux); elsewhere (macOS) the child of a JVM outlives it anyway.
            if (setsid != null) { add(setsid); add("-f") }
            add(Path.of(javaHome, "bin", "java").toString())
            add("-jar"); add(jarPath.toString()); add("--home"); add(home.toString())
            addAll(extraArgs)
        }
        val p = ProcessBuilder(cmd)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .redirectOutput(ProcessBuilder.Redirect.appendTo(out))
            .redirectError(ProcessBuilder.Redirect.appendTo(out))
            .start()
        // `setsid -f` forks and exits at once: reap it. The daemon itself is not our child then.
        if (setsid != null) p.waitFor()
    }

    /** A client for the daemon of [home], started when needed -> client, hello, started?. */
    fun connect(home: Path): Triple<DaemonClient, Hello, Boolean> {
        connectExisting(home)?.let { return Triple(it.first, it.second, false) }
        startDaemon(home)
        val deadline = System.currentTimeMillis() + startTimeoutMs
        while (true) {
            connectExisting(home)?.let { return Triple(it.first, it.second, true) }
            if (System.currentTimeMillis() > deadline) {
                val out = home.resolve("daemon").resolve("daemon.out")
                val tail = runCatching { Files.readAllLines(out).takeLast(5).joinToString("\n") }.getOrDefault("")
                throw DaemonException(
                    "the daemon did not come up in time" + if (tail.isNotEmpty()) ":\n$tail" else "", "daemon/unreachable", "see $out",
                )
            }
            Thread.sleep(50)
        }
    }

    data class StopResult(val running: Boolean, val pid: Long = -1, val runs: Int = 0, val via: String = "", val gone: Boolean = false)

    /** Stops the daemon of [home] without ever starting one; waits until the process and its endpoint are gone. */
    fun stopDaemon(home: Path): StopResult {
        val (client, hello) = connectExisting(home) ?: return StopResult(false)
        val r = client.call("daemon.stop")
        val unknown = !r.ok && r.errorType == "unknown-op"
        if (!r.ok && !unknown) throw DaemonException("daemon.stop failed: ${r.errorMessage}", "daemon/stop-failed")
        if (unknown) ProcessHandle.of(hello.pid).ifPresent { it.destroy() }
        val runs = r.result?.takeIf { it.isJsonObject }?.asJsonObject?.get("runs")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0
        return StopResult(true, hello.pid, runs, if (unknown) "SIGTERM" else "daemon.stop", waitGone(home, hello.pid))
    }

    private fun waitGone(home: Path, pid: Long): Boolean {
        val deadline = System.currentTimeMillis() + stopTimeoutMs
        while (true) {
            val alive = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
            if (!alive && readEndpoint(home) == null) return true
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(50)
        }
    }

    /** The one way in: see the class comment. */
    fun connectCompatible(home: Path): DaemonConnection {
        val want = jarVersion()
        val (client, hello, started) = connect(home)
        val first = if (want == null) hello else client.hello(want)
        if (first.compatible != false) return DaemonConnection(client, first, home, started, false)

        val running = "daemon pid ${first.pid} runs ${first.version} but this plugin ships $want"
        fun refuse(msg: String, hint: String): Nothing = throw DaemonException(msg, "daemon/incompatible", hint)
        when {
            first.runs > 0 -> refuse(
                "$running and has ${first.runs} run(s) in flight",
                "wait for them or cancel them, then run `dlb daemon stop`; the IDE restarts the daemon on the next request",
            )
            want == null || !versionNewer(want, first.version) -> refuse(
                "$running (the daemon is newer than this plugin)",
                "update the plugin or run `dlb daemon stop` to start the daemon this plugin ships",
            )
        }
        val stopped = stopDaemon(home)
        if (!stopped.gone) refuse("the old daemon (pid ${first.pid}) did not exit", "see ${home.resolve("daemon").resolve("daemon.log")}")
        val (c2, _, _) = connect(home)
        val again = c2.hello(want)
        if (again.compatible == false) refuse("the restarted daemon still runs ${again.version}, this plugin ships $want", "check the daemon jar")
        return DaemonConnection(c2, again, home, true, true)
    }

    companion object {
        fun jarVersion(jar: Path): String? = try {
            ZipFile(jar.toFile()).use { z ->
                z.getEntry("deliberator/version.edn")?.let { e ->
                    Regex(""":version\s+"([^"]+)"""").find(z.getInputStream(e).readBytes().toString(Charsets.UTF_8))?.groupValues?.get(1)
                }
            }
        } catch (_: IOException) {
            null
        }

        private fun onPath(name: String): String? =
            System.getenv("PATH")?.split(File.pathSeparator)?.map { File(it, name) }?.firstOrNull { it.canExecute() }?.path
    }
}
