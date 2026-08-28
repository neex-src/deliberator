package com.neex.tellij.sources.logs

import com.neex.tellij.spi.StepError
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

/** `# @filter text` (substring) or `# @filter /regex/`; several filters must all match. */
class LogFilter(private val matchers: List<(String) -> Boolean>) {
    fun matches(line: String): Boolean = matchers.all { it(line) }

    companion object {
        val ALL = LogFilter(emptyList())

        fun parse(specs: List<String>): LogFilter = LogFilter(specs.map { spec ->
            val s = spec.trim()
            if (s.length >= 2 && s.startsWith("/") && s.endsWith("/")) {
                val re = try { Regex(s.substring(1, s.length - 1)) } catch (e: Exception) { throw StepError("bad regex in @filter $s", e.message) }
                ({ line: String -> re.containsMatchIn(line) })
            } else {
                ({ line: String -> line.contains(s) })
            }
        })
    }
}

/**
 * Groups a header line with the indented lines that follow it (a Postgres statement, a Java stack
 * trace) so the filter sees — and the buffer shows — whole entries. An entry completes when the
 * next non-indented line arrives or when the source goes idle ([flush]).
 */
class EntryGrouper(private val filter: LogFilter) {
    private val pending = ArrayList<String>()

    fun feed(line: String): List<String> {
        if (pending.isNotEmpty() && line.isNotEmpty() && (line[0] == ' ' || line[0] == '\t')) {
            pending += line
            return emptyList()
        }
        val out = flush()
        pending += line
        return out
    }

    fun flush(): List<String> {
        if (pending.isEmpty()) return emptyList()
        val entry = pending.toList()
        pending.clear()
        return if (entry.any { filter.matches(it) }) entry else emptyList()
    }
}

/**
 * Offset-armed tail of a growing file. [arm] remembers the current size; every [poll] returns the
 * complete lines appended since. A file that shrank was rotated or truncated — we restart from 0.
 * Bytes after the last newline wait in [partial] until their line completes.
 */
class FileTail(val path: Path, filter: LogFilter = LogFilter.ALL) {
    private var offset = 0L
    private val partial = StringBuilder()
    private val grouper = EntryGrouper(filter)

    fun arm() {
        offset = if (Files.isRegularFile(path)) Files.size(path) else 0L
        partial.setLength(0)
    }

    /** New complete entries; a poll that finds nothing new completes the entry in progress. */
    fun poll(): List<String> {
        if (!Files.isRegularFile(path)) return grouper.flush()
        val size = Files.size(path)
        if (size < offset) { offset = 0; partial.setLength(0) }
        if (size == offset) return grouper.flush()
        val bytes = ByteArray((size - offset).toInt())
        RandomAccessFile(path.toFile(), "r").use { raf ->
            raf.seek(offset)
            raf.readFully(bytes)
        }
        offset = size
        partial.append(String(bytes, Charsets.UTF_8))
        val out = ArrayList<String>()
        while (true) {
            val nl = partial.indexOf("\n")
            if (nl < 0) break
            val line = partial.substring(0, nl).trimEnd('\r')
            partial.delete(0, nl + 1)
            out += grouper.feed(line)
        }
        return out
    }
}
