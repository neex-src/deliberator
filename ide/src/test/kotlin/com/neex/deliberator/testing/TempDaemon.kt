package com.neex.deliberator.testing

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

    /** The pid in `daemon/lock`, or null. */
    fun lockPid(): Long? =
        runCatching { Files.readString(home.resolve("daemon").resolve("lock")).trim().toLong() }.getOrNull()

    /** All lines of `daemon/daemon.log` (one per start, stop and request). */
    fun log(): List<String> = runCatching { Files.readAllLines(home.resolve("daemon").resolve("daemon.log")) }.getOrDefault(emptyList())

    /** Writes a scenario under the home (outside any daemon-owned directory) and returns its path. */
    fun scenario(name: String, text: String): String =
        home.resolve(name).also { Files.writeString(it, text) }.toString()

    override fun close() {
        try {
            val r = launcher.stopDaemon(home)
            if (r.running && !r.gone) lockPid()?.let { ProcessHandle.of(it).ifPresent { h -> h.destroy() } }
        } catch (_: DaemonException) {
            lockPid()?.let { ProcessHandle.of(it).ifPresent { h -> h.destroy() } }
        }
        home.toFile().deleteRecursively()
    }

    companion object {
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

        /** A launcher for another jar (to play a plugin that ships another version) to use with this home. */
        fun launcherFor(jar: Path): DaemonLauncher = launcher(jar)

        /** A copy of the shipped jar whose `deliberator/version.edn` says [version]: a stand-in for another release. */
        fun jarWithVersion(version: String, into: Path): Path {
            val out = into.resolve("deliberatord-$version.jar")
            ZipFile(shippedJar().toFile()).use { zin ->
                ZipOutputStream(Files.newOutputStream(out)).use { zout ->
                    for (e in zin.entries()) {
                        zout.putNextEntry(ZipEntry(e.name))
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
