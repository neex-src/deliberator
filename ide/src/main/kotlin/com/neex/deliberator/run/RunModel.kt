/**
 * The run view's data, parsed defensively and free of the platform: the sections of a run's `result.txt`
 * (one per step, split on the section head lines `### kind ... - status - n ms`), the trailer, the history
 * rows of `runs.list` and the confirmations of a `confirm-required` error. Every path is a string the
 * daemon returned; nothing here knows the store layout.
 */
package com.neex.deliberator.run

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** One step's section of `result.txt`: the head line and the lines under it, exactly as in the file. */
data class StepSection(val n: Int, val head: String, val lines: List<String>) {
    /** `ok`, `failed`, `error`, `cancelled` or `skipped`. */
    val status: String get() = HEAD.matchEntire(head)?.groupValues?.get(1) ?: "?"
    val elapsed: String? get() = HEAD.matchEntire(head)?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }
    val text: String get() = (listOf(head) + lines).joinToString("\n")
    val checks: List<String> get() = lines.filter { it.startsWith("✓ ") || it.startsWith("✗ ") }
    val failedChecks: List<String> get() = lines.filter { it.startsWith("✗ ") }
    val isBad: Boolean get() = status == "failed" || status == "error"

    /** The one line of the collapsed inlay: glyph, status, time, check count and the first thing that went wrong. */
    fun summary(): String {
        val glyph = when (status) { "ok" -> "✓"; "failed", "error" -> "✗"; "cancelled" -> "■"; else -> "–" }
        val parts = ArrayList<String>()
        parts += "$glyph $status"
        elapsed?.let { parts += it }
        if (checks.isNotEmpty()) parts += "${checks.size} check" + (if (checks.size == 1) "" else "s")
        val why = failedChecks.firstOrNull() ?: lines.firstOrNull { it.startsWith("ERROR:") }
        if (why != null) parts += why
        return parts.joinToString(" · ")
    }

    companion object {
        val HEAD = Regex("^### .+ · (ok|failed|error|cancelled|skipped)(?: · (\\d+ ms))?$")
    }
}

/** A run as the view needs it: the file text, its sections by step number, the trailer, where it lives. */
data class RunSnapshot(
    val runId: String,
    val runDir: String,
    val resultPath: String?,
    val status: String,
    val scenarioSha: String?,
    val sections: Map<Int, StepSection>,
    val trailer: List<String>,
    val text: String,
    /** Set when `result.txt` could not be matched to the run's steps (then no section is attached to a step). */
    val problem: String? = null,
) {
    val trailerText: String get() = trailer.joinToString("  ·  ")
}

object ResultText {
    private val RUN = Regex("^RUN \\w+ steps \\d+ failed \\d+$")

    /** Sections and trailer of [text]; [stepNs] are the step numbers of the run in file order. */
    fun parse(text: String, stepNs: List<Int>): Triple<Map<Int, StepSection>, List<String>, String?> {
        val lines = text.split("\n").let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
        var t = lines.indexOfLast { RUN.matches(it) }
        if (t < 0) return Triple(emptyMap(), emptyList(), "result.txt has no RUN line")
        val trailerEnd = t
        while (t > 0 && (lines[t - 1].startsWith("TOPOLOGY ") || lines[t - 1].startsWith("FAILED ") || lines[t - 1].startsWith("ERROR: "))) t--
        val trailer = lines.subList(t, trailerEnd + 1)
        val heads = (0 until t).filter { StepSection.HEAD.matches(lines[it]) }
        if (heads.size != stepNs.size) {
            return Triple(emptyMap(), trailer, "result.txt has ${heads.size} sections but the run has ${stepNs.size} steps")
        }
        val out = LinkedHashMap<Int, StepSection>()
        heads.forEachIndexed { k, h ->
            val end = if (k + 1 < heads.size) heads[k + 1] else t
            val body = lines.subList(h + 1, end).toMutableList()
            while (body.isNotEmpty() && body.last().isEmpty()) body.removeAt(body.size - 1)
            out[stepNs[k]] = StepSection(stepNs[k], lines[h], body)
        }
        return Triple(out, trailer, null)
    }
}

/** A row of `runs.list`. */
data class HistoryRow(val runId: String, val status: String?, val started: String?, val steps: Int?, val failed: Int?, val path: String) {
    val label: String get() = "$runId  ${status ?: "?"}  ${steps ?: "?"} steps, ${failed ?: 0} failed" + (started?.let { "  $it" } ?: "")

    companion object {
        fun parse(result: JsonObject): List<HistoryRow> = (result.get("runs") as? JsonArray)?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.removePrefix(":")
            fun i(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull()
            HistoryRow(s("run-id") ?: return@mapNotNull null, s("status"), s("started"), i("steps"), i("failed"), s("path") ?: "")
        } ?: emptyList()
    }
}

/** One confirmation the daemon asked for: step [step] needs [token] to do [summary]. */
data class Confirmation(val step: Int, val token: String, val summary: String) {
    /** The arguments of the repeated `scenario.run`: the same file, only this step, with the token. */
    fun rerunArgs(path: String, env: String?): JsonObject = JsonObject().apply {
        addProperty("path", path)
        env?.let { addProperty("env", it) }
        add("steps", JsonArray().also { it.add(step) })
        addProperty("confirm", token)
    }

    companion object {
        /** The confirmations of a `confirm-required` terminal error (`confirmations`, else the single `token summary step`). */
        fun parse(error: JsonObject?): List<Confirmation> {
            if (error == null) return emptyList()
            fun one(o: JsonObject): Confirmation? {
                fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString
                val step = o.get("step")?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull() ?: return null
                return Confirmation(step, s("token") ?: return null, s("summary") ?: "")
            }
            val list = (error.get("confirmations") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::one) }.orEmpty()
            return list.ifEmpty { listOfNotNull(one(error)) }
        }
    }
}

internal fun JsonElement?.objectOrNull(): JsonObject? = this?.takeIf { it.isJsonObject }?.asJsonObject
