/**
 * How the answers of `service.reload` read in a console or a balloon. Pure text: the daemon's events, result and typed errors in, lines out
 * (nothing is decided here; `fallback` and `errors` are the daemon's words). [CompileLinkFilter] makes the `file:line:col` of a compiler
 * error clickable in any console it is added to.
 */
package com.neex.deliberator.services

import com.google.gson.JsonObject
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.neex.deliberator.daemon.DaemonEvent

/** One entry of `service/compile-failed`'s `errors`. */
data class CompileError(val file: String, val line: Int, val col: Int?, val message: String) {
    /** The javac-style position clickable in a console. */
    fun position() = "$file:$line" + (col?.let { ":$it" } ?: "")
}

/** A failed reload ready to print: the [lines] and the structured pieces a balloon links to. */
data class ReloadFailure(val type: String, val lines: List<String>, val errors: List<CompileError>, val log: String?)

object ReloadRender {
    private fun JsonObject.s(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.i(k: String): Int? = get(k)?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull()

    /** `service.reload.step` -> one line. */
    fun step(ev: DaemonEvent): String = step(ev.json)

    fun step(j: JsonObject): String {
        val step = j.s("step") ?: "?"
        val detail = j.s("detail")
        val files = j.get("files")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString }.orEmpty()
        val shown = if (files.isEmpty()) "" else "  [" + files.take(5).joinToString(", ") + (if (files.size > 5) ", ... +${files.size - 5}" else "") + "]"
        return "reload: $step" + (detail?.let { " - $it" } ?: "") + shown
    }

    /** The `service.reload` result: what was redefined, how long, and a visible fallback. */
    fun result(r: JsonObject): List<String> {
        val changed = r.get("changed")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0
        val redefined = r.get("redefined")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0
        val out = mutableListOf<String>()
        val mech = r.s("mechanism") ?: "?"
        val took = r.i("took-ms")?.let { " in ${it} ms" } ?: ""
        val count = r.i("reload-count")?.let { ", in-place reload #$it" } ?: ""
        out += "reload: done - $changed file(s) changed, $redefined class(es) redefined$took ($mech$count)"
        r.s("fallback")?.let { out += "reload: FELL BACK TO RESTART - $it" }
        r.s("log")?.let { out += "reload: log $it" }
        return out
    }

    /** A typed error of `service.reload` (the terminal's `error` object): `compile-failed` with its positions, anything else type + message + hint. */
    fun failure(error: JsonObject?, fallbackType: String = "service/error", fallbackMessage: String = "reload failed"): ReloadFailure {
        val type = error?.s("type") ?: fallbackType
        val message = error?.s("message") ?: fallbackMessage
        val hint = error?.s("hint")
        val errors = error?.get("errors")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { x -> x.isJsonObject }?.asJsonObject }?.mapNotNull { e ->
            val f = e.s("file") ?: return@mapNotNull null
            CompileError(f, e.i("line") ?: 0, e.i("col"), e.s("message") ?: "")
        }.orEmpty()
        val log = error?.s("log")
        val out = mutableListOf("reload: FAILED ($type) $message")
        if (type == "service/compile-failed") {
            if (errors.isNotEmpty()) errors.forEach { out += "  ${it.position()}: ${it.message}" }
            else error?.get("lines")?.takeIf { it.isJsonArray }?.asJsonArray?.take(15)?.forEach { out += "  ${it.asString}" }
        }
        hint?.let { out += "  hint: $it" }
        log?.let { out += "  log: $it" }
        return ReloadFailure(type, out, errors, log)
    }
}

/** Turns `/abs/File.java:12` and `/abs/File.java:12:5` in console output into links to the file and line. */
class CompileLinkFilter(private val project: Project) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val m = POSITION.find(line) ?: return null
        val vf = LocalFileSystem.getInstance().findFileByPath(m.groupValues[1]) ?: return null
        val l = m.groupValues[2].toInt() - 1
        val c = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.minus(1) ?: 0
        val start = entireLength - line.length + m.range.first
        return Filter.Result(start, entireLength - line.length + m.range.last + 1, OpenFileHyperlinkInfo(project, vf, maxOf(0, l), maxOf(0, c)))
    }

    companion object {
        private val POSITION = Regex("""(/[^\s:]+\.(?:java|kt|kts|xml|properties|yml|yaml|groovy)):(\d+)(?::(\d+))?""")
    }
}
