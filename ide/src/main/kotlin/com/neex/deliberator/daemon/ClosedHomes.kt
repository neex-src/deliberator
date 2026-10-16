package com.neex.deliberator.daemon

import java.nio.file.Path

/**
 * Homes that were shut down on purpose (a test's temporary home). [DaemonService.ensure] refuses them
 * (`daemon/home-closed`) instead of starting a daemon on a directory nobody owns any more, so a background refresh that runs
 * after the owner closed cannot leave a process behind. Nothing marks a home in production. The lock is the one `ensure`
 * runs under: [close] returns only after an `ensure` that was already starting a daemon has finished.
 */
object ClosedHomes {
    private val closed = HashSet<Path>()

    /** Marks [home] closed; waits for an in-flight `ensure`. */
    fun close(home: Path): Unit = synchronized(this) { closed.add(home.toAbsolutePath().normalize()); Unit }

    /** Runs [body] under the lock shared with [close]; throws `daemon/home-closed` when [home] is closed. */
    fun <T> whileOpen(home: Path, body: () -> T): T = synchronized(this) {
        if (home.toAbsolutePath().normalize() in closed)
            throw DaemonException("daemon home $home was closed; not starting a daemon on it", "daemon/home-closed")
        body()
    }
}
