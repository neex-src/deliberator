/**
 * The wire types of the daemon protocol (`docs/architecture.md` section 6): NDJSON, one request per
 * connection here, answered by zero or more events and exactly one terminal line.
 */
package com.neex.deliberator.daemon

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Every failure of the client/lifecycle layer; [type] is stable (`daemon/unreachable`, `daemon/closed`, ...), [hint] says what to do. */
class DaemonException(message: String, val type: String, val hint: String? = null, cause: Throwable? = null) :
    Exception(message, cause)

/** One event line of a request (`{"id":..,"event":"step.started",...}`). */
class DaemonEvent(val name: String, val json: JsonObject)

/** The terminal line of a request: `ok` with a [result], or an error with [errorType] and [errorMessage]. */
class DaemonTerminal(val ok: Boolean, val result: JsonElement?, val error: JsonObject?, val raw: String) {
    val errorType: String? get() = error?.get("type")?.takeIf { it.isJsonPrimitive }?.asString
    val errorMessage: String? get() = error?.get("message")?.takeIf { it.isJsonPrimitive }?.asString

    /** The result as an object; throws a [DaemonException] carrying the daemon's error when the request failed. */
    fun resultObject(): JsonObject {
        if (!ok) throw DaemonException(errorMessage ?: "request failed", errorType ?: "daemon/error")
        return result?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
    }
}

/** The answer of `hello`; [compatible] is only present when the client sent a version. */
data class Hello(
    val version: String,
    val pid: Long,
    val home: String,
    val endpoint: String,
    val runs: Int,
    val compatible: Boolean?,
) {
    companion object {
        fun of(o: JsonObject) = Hello(
            version = o.get("version")?.asString ?: "?",
            pid = o.get("pid")?.asLong ?: -1,
            home = o.get("home")?.asString ?: "",
            endpoint = o.get("endpoint")?.asString ?: "",
            runs = o.get("runs")?.asInt ?: 0,
            compatible = o.get("compatible")?.takeIf { it.isJsonPrimitive }?.asBoolean,
        )
    }
}

/** Dotted/dashed version compare as `dlb.endpoint/version-newer?`: numbers numerically, a longer version is newer than its prefix. */
fun versionNewer(a: String, b: String): Boolean {
    fun parts(v: String) = v.split('.', '-').map { it.toLongOrNull() ?: it }
    val x = parts(a)
    val y = parts(b)
    for (i in 0 until maxOf(x.size, y.size)) {
        if (i >= y.size) return true
        if (i >= x.size) return false
        val l = x[i]
        val r = y[i]
        val c = if (l is Long && r is Long) l.compareTo(r) else l.toString().compareTo(r.toString())
        if (c != 0) return c > 0
    }
    return false
}
