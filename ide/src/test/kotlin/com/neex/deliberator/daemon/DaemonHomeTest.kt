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
