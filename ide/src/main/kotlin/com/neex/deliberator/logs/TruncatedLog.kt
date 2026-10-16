package com.neex.deliberator.logs

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import javax.swing.JComponent

/**
 * A log the platform loaded only in part (it caps what an editor holds; a plugin cannot raise that cap): [loadedLines] of
 * [totalLines] lines are in the document, the rest is on disk at [path]. (Decision 42.)
 */
data class LogTruncation(val path: String, val loadedLines: Int, val totalLines: Int, val fileBytes: Long) {
    /** The banner text; [beyond] are the marker hits the editor cannot show, when `logs.match` found any. */
    fun message(beyond: BeyondHits? = null): String {
        val head = "Truncated: the editor shows lines 1-$loadedLines of $totalLines (the IDE does not load the rest of a ${fileBytes / (1024 * 1024)} MB file); the whole log is on disk at $path."
        return if (beyond == null) head
        else head + " ${beyond.count} marker hit${if (beyond.count == 1) "" else "s"} lie beyond the loaded part, the first at line ${beyond.firstLine}."
    }
}

/** Marker hits of a log that are further down than the editor holds: how many, and where the first one is. */
data class BeyondHits(val count: Int, val firstLine: Int, val loadedLines: Int)

object LogTruncations {
    private class Counted(val length: Long, val stamp: Long, val lines: Int)

    private val counted = ConcurrentHashMap<String, Counted>()

    /** Lines of the file on disk as an editor would count them (`\n` ends a line; a last line without it counts too). Streams; cached by size and time. */
    fun totalLines(file: VirtualFile): Int {
        counted[file.path]?.let { if (it.length == file.length && it.stamp == file.timeStamp) return it.lines }
        var n = 0
        var last = '\n'.code
        val buf = ByteArray(1 shl 20)
        Files.newInputStream(Path.of(file.path)).use { ins ->
            while (true) {
                val k = ins.read(buf)
                if (k < 0) break
                for (i in 0 until k) if (buf[i].toInt() == '\n'.code) n++
                if (k > 0) last = buf[k - 1].toInt()
            }
        }
        if (last != '\n'.code) n++
        counted[file.path] = Counted(file.length, file.timeStamp, n)
        return n
    }

    /** Null when [doc] holds the whole of [file]. A file that is not bigger than its document is never counted (cheap exit for ordinary logs). */
    fun of(file: VirtualFile, doc: Document): LogTruncation? {
        if (file.length - doc.textLength <= 1024) return null
        val total = try { totalLines(file) } catch (_: Exception) { return null }
        val chars = doc.charsSequence
        val loaded = doc.lineCount - (if (chars.isNotEmpty() && chars[chars.length - 1] == '\n') 1 else 0)
        return if (total > loaded) LogTruncation(file.path, loaded, total, file.length) else null
    }
}

/** The banner above a log editor that holds only the head of its file (and the marker hits it cannot show). */
class TruncatedLogBanner : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (!file.isInLocalFileSystem || !LogFiles.isLog(file)) return null
        val doc = FileDocumentManager.getInstance().getCachedDocument(file) ?: return null
        val t = LogTruncations.of(file, doc) ?: return null
        val text = t.message(LogMatcher.getInstance(project).beyond(file.path))
        return Function { editor -> EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning).apply { this.text = text } }
    }
}
