package com.neex.deliberator.services

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.neex.deliberator.daemon.DaemonClient
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.workspace.WorkspaceService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Services tab's ear on the daemon (piece 02c): ONE `service.events` request per tab, open while the tab lives. The ECS watch of the daemon stops and starts
 * containers on its own (an event on the bus, a Lambda scaling a service to 0); each `service.started` / `service.stopped` it streams refreshes the workspace
 * model at once, so the rows change within about two seconds and not at the 15 s tick. Refreshes are coalesced: a burst of events (a stop and its recreate) asks the
 * daemon once more, never once per event. A request that ends (the daemon restarted, the workspace is not bound yet) is asked again after [RETRY_MS]. Stop it with [stop].
 */
class ServiceEvents(private val project: Project, @Suppress("unused") parent: com.intellij.openapi.Disposable) {
    private val stopped = AtomicBoolean(false)
    private val refreshing = AtomicBoolean(false)
    @Volatile private var call: DaemonClient.Call? = null
    @Volatile private var thread: Thread? = null

    /** The events the watch sent (tests). */
    val seen = AtomicInteger()

    /** True while the request is open at the daemon (tests). */
    @Volatile var listening: Boolean = false; private set

    fun start() {
        if (thread != null) return
        thread = Thread({ loop() }, "deliberator-service-events").apply { isDaemon = true; start() }
    }

    fun stop() {
        stopped.set(true)
        call?.cancel()
        thread?.interrupt()
    }

    private fun loop() {
        val svc = WorkspaceService.getInstance(project)
        while (!stopped.get()) {
            try {
                if (svc.ws != null) {
                    val c = svc.start("service.events", JsonObject(), true) { ev ->
                        if (ev.name == "service.started" || ev.name == "service.stopped") { seen.incrementAndGet(); poke() }
                    }
                    call = c
                    listening = true
                    c.await()
                }
            } catch (_: DaemonException) {
            } catch (_: InterruptedException) { break } catch (_: Exception) {
            } finally {
                listening = false
                call = null
            }
            if (stopped.get()) break
            try { Thread.sleep(RETRY_MS) } catch (_: InterruptedException) { break }
        }
    }

    /** One refresh at a time; an event that arrives while one runs schedules exactly one more. */
    private fun poke() {
        if (!refreshing.compareAndSet(false, true)) { again.set(true); return }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                do { again.set(false); WorkspaceService.getInstance(project).refreshNow() } while (again.get() && !stopped.get())
            } finally { refreshing.set(false) }
        }
    }

    private val again = AtomicBoolean(false)

    companion object { const val RETRY_MS = 3000L }
}
