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
 * Offset-armed tail of a growing file. [arm] remembers the current size; every [poll] returns the
 * complete lines appended since. A file that shrank was rotated or truncated — we restart from 0.
 * Bytes after the last newline wait in [partial] until their line completes.
 */
class FileTail(val path: Path, private val filter: LogFilter = LogFilter.ALL) {
    private var offset = 0L
    private val partial = StringBuilder()

    fun arm() {
        offset = if (Files.isRegularFile(path)) Files.size(path) else 0L
        partial.setLength(0)
    }

    fun poll(): List<String> {
        if (!Files.isRegularFile(path)) return emptyList()
        val size = Files.size(path)
        if (size < offset) { offset = 0; partial.setLength(0) }
        if (size == offset) return emptyList()
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
            if (filter.matches(line)) out += line
        }
        return out
    }
}
