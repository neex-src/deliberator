package com.neex.deliberator.run

import com.google.gson.JsonObject
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.neex.deliberator.workspace.WorkspaceService

/** The two normalised texts of `runs.diff` (ids, instants, durations and paths already replaced by the daemon). */
data class DiffTexts(val a: String, val b: String, val equal: Boolean, val aRun: String, val bRun: String)

object RunDiff {
    /** `runs.diff a b`; blocking, off the EDT. */
    fun fetch(svc: WorkspaceService, a: HistoryRow, b: HistoryRow): DiffTexts {
        val r = svc.call("runs.diff", JsonObject().apply {
            addProperty("a", a.path.ifBlank { a.runId })
            addProperty("b", b.path.ifBlank { b.runId })
        }).resultObject()
        fun s(k: String) = r.get(k)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        return DiffTexts(s("a-text"), s("b-text"), r.get("equal")?.asBoolean ?: false, a.runId, b.runId)
    }

    /** The request for the IDE diff viewer: older run left, newer right when the caller orders them so. */
    fun request(project: Project?, t: DiffTexts): SimpleDiffRequest {
        val f = DiffContentFactory.getInstance()
        return SimpleDiffRequest("Runs " + t.aRun + " / " + t.bRun + (if (t.equal) " (no difference)" else ""),
            f.create(project, t.a, PlainTextFileType.INSTANCE), f.create(project, t.b, PlainTextFileType.INSTANCE), t.aRun, t.bRun)
    }
}
