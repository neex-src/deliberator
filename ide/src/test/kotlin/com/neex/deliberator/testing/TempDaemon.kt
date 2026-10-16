package com.neex.deliberator.testing

import com.neex.deliberator.daemon.ClosedHomes
import com.neex.deliberator.daemon.DaemonClient
import com.neex.deliberator.daemon.DaemonConnection
import com.neex.deliberator.daemon.DaemonLauncher
import com.neex.deliberator.daemon.DaemonException
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * A real `deliberatord` on a throwaway home under `ide/build/th/` (the Gradle test task sets
 * `deliberator.test.homes` and `deliberator.jar`), started on the test JVM's own `java.home`, never
 * a mock. [close] stops it through `daemon.stop` (SIGTERM to the pid in `daemon/lock` as the
 * fallback) and deletes the home. A daemon also exits by itself after 5 idle minutes, in case a
 * test dies before its teardown. For platform tests: set `DeliberatorSettings.state.home = home`
 * and the service talks to this daemon.
 */
class TempDaemon private constructor(val home: Path, val jar: Path) : AutoCloseable {
    val launcher = launcher(jar)

    /** Finds or starts the daemon of [home] with [jar]; blocking. */
    fun connect(): DaemonConnection = launcher.connectCompatible(home)

    val client: DaemonClient get() = connect().client

    /** `workspace.open`: a workspace exists only once opened (reading ops answer `workspace/not-found` for an unknown one). */
    fun openWorkspace(ws: String) {
        client.call("workspace.open", com.google.gson.JsonObject().apply { addProperty("ws", ws) })
    }

    /** The pid in `daemon/lock`, or null. */
    fun lockPid(): Long? =
        runCatching { Files.readString(home.resolve("daemon").resolve("lock")).trim().toLong() }.getOrNull()

    /** All lines of `daemon/daemon.log` (one per start, stop and request). */
    fun log(): List<String> = runCatching { Files.readAllLines(home.resolve("daemon").resolve("daemon.log")) }.getOrDefault(emptyList())

    /** Writes a scenario under the home (outside any daemon-owned directory) and returns its path. */
    fun scenario(name: String, text: String): String =
        home.resolve(name).also { Files.writeString(it, text) }.toString()

    /**
     * Marks the home closed (a late `DaemonService.ensure()` now fails with `daemon/home-closed` instead of starting a daemon),
     * stops the daemon, then kills whatever still carries `--home <home>`, and deletes the home. Idempotent.
     */
    override fun close() {
        ClosedHomes.close(home)
        try {
            val r = launcher.stopDaemon(home)
            if (r.running && !r.gone) lockPid()?.let { ProcessHandle.of(it).ifPresent { h -> h.destroy() } }
        } catch (_: DaemonException) {
            lockPid()?.let { ProcessHandle.of(it).ifPresent { h -> h.destroy() } }
        }
        survivors(home).forEach { it.destroyForcibly() }
        home.toFile().deleteRecursively()
    }

    companion object {
        /** Processes whose command line carries `--home <home>` (a `deliberatord` of that home). */
        fun survivors(home: Path): List<ProcessHandle> = ProcessHandle.allProcesses().filter { commandOwnsHome(it.info().commandLine().orElse(""), home) }.toList()

        /** True when [commandLine] is a daemon started with exactly `--home <home>` (not a longer sibling path). */
        fun commandOwnsHome(commandLine: String, home: Path): Boolean {
            val h = "--home $home"
            val i = commandLine.indexOf(h)
            return commandLine.contains("deliberatord") && i >= 0 && (commandLine.length == i + h.length || commandLine[i + h.length] == ' ')
        }

        private fun launcher(jar: Path) =
            DaemonLauncher(System.getProperty("java.home"), jar, listOf("--idle-minutes", "5"), startTimeoutMs = 60_000)

        /** The jar the Gradle test task built (`core/target/deliberatord.jar`). */
        fun shippedJar(): Path = Path.of(System.getProperty("deliberator.jar") ?: error("deliberator.jar is not set: run through Gradle"))

        /** A fresh temp home (a short path: unix socket paths are limited to ~100 bytes). */
        fun create(jar: Path = shippedJar()): TempDaemon {
            val base = File(System.getProperty("deliberator.test.homes") ?: error("deliberator.test.homes is not set: run through Gradle"))
            val home = base.toPath().resolve(java.lang.Long.toString(System.nanoTime() % 1679616L, 36))
            Files.createDirectories(home)
            return TempDaemon(home, jar)
        }

        /** A fresh temp home whose directory is named [dirName] (a space, a long name: the audit's odd homes). */
        fun createNamed(dirName: String, jar: Path = shippedJar()): TempDaemon {
            val base = File(System.getProperty("deliberator.test.homes") ?: error("deliberator.test.homes is not set: run through Gradle"))
            val home = base.toPath().resolve(dirName)
            Files.createDirectories(home)
            return TempDaemon(home, jar)
        }

        /** A launcher for another jar (to play a plugin that ships another version) to use with this home. */
        fun launcherFor(jar: Path): DaemonLauncher = launcher(jar)

        /** A copy of the shipped jar whose `deliberator/version.edn` says [version]: a stand-in for another release. */
        fun jarWithVersion(version: String, into: Path): Path {
            val out = into.resolve("deliberatord-$version.jar")
            ZipFile(shippedJar().toFile()).use { zin ->
                ZipOutputStream(Files.newOutputStream(out)).use { zout ->
                    for (e in zin.entries()) {
                        // keep each entry's time: Clojure loads a namespace from source when the .clj is not older than its .class, and a
                        // re-zipped jar with "now" on every entry makes every namespace recompile at start (audit F-jar: 13 s+, one 60 s timeout)
                        zout.putNextEntry(ZipEntry(e.name).also { n -> e.lastModifiedTime?.let { n.lastModifiedTime = it } })
                        if (e.name == "deliberator/version.edn") zout.write("{:version \"$version\"}\n".toByteArray())
                        else zin.getInputStream(e).use { it.copyTo(zout) }
                        zout.closeEntry()
                    }
                }
            }
            return out
        }
    }
}
