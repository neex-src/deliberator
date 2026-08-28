package com.neex.tellij.engine

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.util.Disposer
import com.intellij.util.DocumentUtil
import com.intellij.util.SingleAlarm
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.neex.tellij.model.Block
import com.neex.tellij.model.ResultMarkers
import com.neex.tellij.model.TextRange
import com.neex.tellij.model.WorkbenchParser
import com.neex.tellij.vars.Vars
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * A block's result section in the document, written to from any thread. Lines are queued and
 * flushed on the EDT in one undo-transparent write every ~100 ms; appends go in front of the
 * `#< end` marker, which is not greedy to the left and therefore always stays on its own line
 * while the user edits elsewhere. If the user deletes the section mid-run it is re-created; if
 * the whole block is gone the sink goes silent (documented v1 behaviour).
 */
class DocumentResultSink(
    parent: Disposable,
    private val document: Document,
    private val block: Block,
    range: TextRange,
    private val varsUsed: Map<String, String>,
    private val maxLines: Int,
) : com.neex.tellij.spi.ResultSink, Disposable {

    private sealed class Terminal {
        class Done(val summary: String?) : Terminal()
        class Fail(val message: String, val hint: String?) : Terminal()
        class Skipped(val reason: String) : Terminal()
        object Stopped : Terminal()
    }

    private var headerMarker: RangeMarker
    private var endMarker: RangeMarker
    private val queue = ConcurrentLinkedQueue<String>()
    private val terminal = AtomicReference<Terminal?>(null)
    private val alarm = SingleAlarm(::flush, 100, this)

    @Volatile private var startedAt = System.currentTimeMillis()
    @Volatile private var finishedAt = 0L
    private val lock = Any()
    private var lines = 0
    private var bytes = 0L
    private var dropped = 0
    @Volatile var orphaned = false
        private set
    @Volatile var flushedTerminal = false
        private set

    init {
        Disposer.register(parent, this)
        val (h, e) = markersFor(range)
        headerMarker = h
        endMarker = e
    }

    private fun markersFor(range: TextRange): Pair<RangeMarker, RangeMarker> {
        val text = document.immutableCharSequence
        var headerEnd = range.start
        while (headerEnd < range.end && text[headerEnd] != '\n') headerEnd++
        var endStart = range.end
        while (endStart > range.start && text[endStart - 1] != '\n') endStart--
        return document.createRangeMarker(range.start, headerEnd) to document.createRangeMarker(endStart, range.end)
    }

    override fun started() {
        startedAt = System.currentTimeMillis()
    }

    override fun appendLine(line: CharSequence) {
        if (terminal.get() != null) return
        synchronized(lock) {
            if (lines >= maxLines || bytes >= MAX_BYTES) { dropped++; return }
            lines++
            bytes += line.length
        }
        queue.add(line.toString())
        alarm.request()
    }

    override fun done(summary: String?) = terminate(Terminal.Done(summary))
    override fun fail(message: String, hint: String?) = terminate(Terminal.Fail(message, hint))
    override fun skipped(reason: String) = terminate(Terminal.Skipped(reason))
    override fun stopped() = terminate(Terminal.Stopped)

    private fun terminate(t: Terminal) {
        if (!terminal.compareAndSet(null, t)) return
        finishedAt = System.currentTimeMillis()
        val droppedNow = synchronized(lock) { dropped }
        if (droppedNow > 0) queue.add("… $droppedNow more lines (limit $maxLines, # @limit N to raise)")
        when (t) {
            is Terminal.Fail -> {
                queue.add("error: ${t.message}")
                if (t.hint != null) queue.add("hint: ${t.hint}")
            }
            is Terminal.Skipped -> queue.add("skipped: ${t.reason}")
            else -> {}
        }
        alarm.cancelAndRequest()
    }

    val isTerminated: Boolean get() = terminal.get() != null

    @TestOnly
    fun flushNow() = flush()

    @RequiresEdt
    private fun flush() {
        if (orphaned) { queue.clear(); return }
        val pending = ArrayList<String>()
        while (true) pending += queue.poll() ?: break
        val t = terminal.get()
        if (pending.isEmpty() && (t == null || flushedTerminal)) return
        DocumentUtil.writeInRunUndoTransparentAction {
            if (!ensureMarkers()) return@writeInRunUndoTransparentAction
            if (pending.isNotEmpty()) {
                val at = endMarker.startOffset
                document.insertString(at, pending.joinToString("\n", postfix = "\n"))
            }
            if (t != null && !flushedTerminal) {
                rewriteHeader(headerFor(t))
                flushedTerminal = true
            }
        }
    }

    private fun headerFor(t: Terminal): String {
        val at = Vars.timestamp(java.time.Instant.ofEpochMilli(startedAt))
        val elapsed = ResultMarkers.elapsed(maxOf(0L, finishedAt - startedAt))
        val count = synchronized(lock) { lines }
        return when (t) {
            is Terminal.Done -> ResultMarkers.header(block.kind, block.name, at, elapsed, t.summary ?: (if (count != 1) "$count lines" else null))
            is Terminal.Fail -> ResultMarkers.header(block.kind, block.name, at, elapsed, "ERROR")
            is Terminal.Skipped -> ResultMarkers.header(block.kind, block.name, at, null, "skipped")
            Terminal.Stopped -> ResultMarkers.header(block.kind, block.name, at, elapsed, "stopped")
        }
    }

    private fun rewriteHeader(header: String) {
        val s = headerMarker.startOffset
        val e = headerMarker.endOffset
        document.replaceString(s, e, header)
        headerMarker.dispose()
        headerMarker = document.createRangeMarker(s, s + header.length)
    }

    /** Markers still frame a real section? Otherwise find or re-create the section. */
    private fun ensureMarkers(): Boolean {
        val text = document.immutableCharSequence
        if (headerMarker.isValid && endMarker.isValid &&
            ResultMarkers.isStart(text.subSequence(headerMarker.startOffset, minOf(text.length, headerMarker.startOffset + ResultMarkers.START.length))) &&
            text.subSequence(endMarker.startOffset, endMarker.endOffset).startsWith(ResultMarkers.END)
        ) return true

        val model = WorkbenchParser.parse(text)
        val current = model.blocks.getOrNull(block.index)?.takeIf { it.kind == block.kind && it.name == block.name }
            ?: model.blocks.firstOrNull { it.kind == block.kind && it.name == block.name }
        if (current == null) {
            orphaned = true
            return false
        }
        val range = current.result?.let { existing ->
            // Take over whatever section is there; its header will be rewritten on completion.
            existing
        } ?: ResultWriter.write(document, current, ResultWriter.initialSection(current, varsUsed, Vars.timestamp(java.time.Instant.ofEpochMilli(startedAt))))
        headerMarker.dispose(); endMarker.dispose()
        val (h, e) = markersFor(range)
        headerMarker = h
        endMarker = e
        return true
    }

    override fun dispose() {
        headerMarker.dispose()
        endMarker.dispose()
    }

    companion object {
        const val MAX_BYTES = 256L * 1024
        const val DEFAULT_MAX_LINES = 500
    }
}
