package com.neex.deliberator.daemon

import java.nio.file.Path

/** Where the daemon's home is: the setting, else `$DELIBERATOR_HOME`, else `~/.local/share/deliberator` (what `dlb` and the core use). */
object DaemonHome {
    fun resolve(setting: String?, env: Map<String, String>, userHome: String): Path {
        val chosen = setting?.trim()?.ifEmpty { null }
            ?: env["DELIBERATOR_HOME"]?.trim()?.ifEmpty { null }
            ?: "$userHome/.local/share/deliberator"
        return Path.of(chosen).toAbsolutePath().normalize()
    }

    /** The daemon jar: system property `deliberator.jar`, else `$DELIBERATOR_JAR`, else [pluginLib]`/deliberatord.jar`. */
    fun resolveJar(property: String?, env: Map<String, String>, pluginLib: Path?): Path? =
        (property?.ifEmpty { null } ?: env["DELIBERATOR_JAR"]?.ifEmpty { null })?.let { Path.of(it).toAbsolutePath() }
            ?: pluginLib?.resolve("deliberatord.jar")
}
