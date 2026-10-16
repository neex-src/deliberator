package com.neex.deliberator.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class DaemonHomeTest {
    private val home = "/home/u"

    @Test fun settingWinsOverEnvironment() {
        val p = DaemonHome.resolve("/data/from-setting", mapOf("DELIBERATOR_HOME" to "/data/from-env"), home)
        assertEquals(Path.of("/data/from-setting"), p)
    }

    @Test fun environmentWinsOverDefault() {
        assertEquals(Path.of("/data/from-env"), DaemonHome.resolve("  ", mapOf("DELIBERATOR_HOME" to "/data/from-env"), home))
        assertEquals(Path.of("/data/from-env"), DaemonHome.resolve(null, mapOf("DELIBERATOR_HOME" to "/data/from-env"), home))
    }

    @Test fun defaultIsTheDlbDefault() {
        assertEquals(Path.of("~/.local/share/deliberator"), DaemonHome.resolve("", emptyMap(), home))
    }

    @Test fun jarFromPropertyThenEnvThenPluginLib() {
        val lib = Path.of("/plugin/lib")
        assertEquals(Path.of("/a.jar"), DaemonHome.resolveJar("/a.jar", mapOf("DELIBERATOR_JAR" to "/b.jar"), lib))
        assertEquals(Path.of("/b.jar"), DaemonHome.resolveJar(null, mapOf("DELIBERATOR_JAR" to "/b.jar"), lib))
        assertEquals(Path.of("/plugin/lib/deliberatord.jar"), DaemonHome.resolveJar(null, emptyMap(), lib))
        assertTrue(DaemonHome.resolveJar(null, emptyMap(), null) == null)
    }

    @Test fun versionOrder() {
        assertTrue(versionNewer("0.2.0", "0.1.0-m1"))
        assertTrue(versionNewer("0.10.0", "0.9.0"))
        assertTrue(versionNewer("0.1.0-m1", "0.1.0"))
        assertTrue(!versionNewer("0.1.0-m1", "0.1.0-m1"))
        assertTrue(!versionNewer("0.1.0", "0.1.0-m1"))
    }
}

/** The net under every IDE test: a home outside `deliberator.test.homes` (above all the real default) is refused. */
class TestHomeGuardTest {
    @Test fun aHomeOutsideTheTestHomesIsRefused() {
        val th = "/work/ide/build/th"
        assertEquals(Path.of("$th/abc"), DaemonHome.guardUnderTest(Path.of("$th/abc"), th))
        for (bad in listOf("~/.local/share/deliberator", "/work/ide/build/never-use-home", "/work/ide/build/th2/x")) {
            val e = try { DaemonHome.guardUnderTest(Path.of(bad), th); null } catch (e: DaemonException) { e }
            assertEquals(bad, "daemon/test-home", e?.type)
        }
        assertEquals(Path.of("/anywhere"), DaemonHome.guardUnderTest(Path.of("/anywhere"), null))
    }

    @Test fun theRunningTestJvmIsGuardedAndNeverResolvesTheDefaultHome() {
        assertTrue("deliberator.test.homes must be set by the Gradle test task", !System.getProperty("deliberator.test.homes").isNullOrBlank())
        val real = Path.of(System.getProperty("user.home"), ".local/share/deliberator")
        val resolved = DaemonHome.resolve("", System.getenv(), System.getProperty("user.home"))
        assertTrue("DELIBERATOR_HOME must point below ide/build in tests: $resolved", resolved != real && resolved.toString().contains("/build/"))
    }
}
