/**
 * The answer of `config.chain` as the editor features need it, parsed defensively (a missing key is null, never an
 * exception, so a newer core cannot break the editor). Nothing here knows the store layout or AWS: every path is a
 * string the daemon returned, every status a word it chose (decision 45 has the shapes).
 */
package com.neex.deliberator.config

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.nio.file.Path
import com.neex.deliberator.config.Chain.Companion.str
import java.time.Instant

data class Source(val file: String?, val line: Int?, val expr: String?, val literal: Boolean)

/** The task definition entry that supplies a placeholder: where it is rendered (`file:line`, `via`) and where it is declared ([source]). */
data class Taskdef(
    val kind: String?, val name: String?, val match: String?, val adopted: Boolean, val value: String?, val valueFrom: String?,
    val expr: String?, val literal: Boolean, val file: String?, val line: Int?, val via: String?, val source: Source?,
)

data class Param(
    val ref: String?, val kind: String?, val name: String?, val exists: Boolean?, val status: String?, val reason: String?, val hint: String?,
    val value: String?, val metadata: JsonObject?, val fetchedAt: String?, val secure: Boolean, val jsonKey: String?,
)

data class Entry(
    val placeholder: String?, val default: String?, val file: String?, val line: Int?, val property: String?,
    val internal: Boolean, val usesDefault: Boolean, val taskdef: Taskdef?, val parameter: Param?,
)

/** `broken` / `unresolved` row: `kind`, `reason` and the entry it is about (an `extra` row has no placeholder). */
data class Problem(val kind: String?, val reason: String?, val entry: Entry)

data class Snapshot(val present: Boolean, val path: String?, val fetchedAt: String?, val ageMs: Long?)

class Chain(
    val service: String, val env: String, val module: String?, val snapshot: Snapshot, val entries: List<Entry>,
    val broken: List<Problem>, val unresolved: List<Problem>, val extra: List<Entry>, val hint: String?, val files: List<String>,
    /** The result as the daemon sent it. */
    val raw: JsonObject,
) {
    /** The entry for a placeholder [name] written in [file] at [line] (1-based), or null. The line is only a tie-break among repeats. */
    fun entryAt(file: String, line: Int, name: String): Entry? {
        val same = entries.filter { it.placeholder == name && samePath(it.file, file) }
        return same.firstOrNull { it.line == line } ?: same.firstOrNull()
    }

    /** Every parameter the chain knows (referenced by a placeholder or by an adopted entry nothing uses). */
    fun parameters(): List<Param> = (entries.mapNotNull { it.parameter } + extra.mapNotNull { it.parameter }).distinctBy { it.kind to it.name }

    /** The same facts, ignoring what changes by itself with time (`age-ms`): used to decide whether the editor has anything new to show. */
    fun sameFacts(o: Chain?): Boolean = o != null && withoutAge(raw) == withoutAge(o.raw)

    companion object {
        private fun withoutAge(o: JsonObject): String {
            val c = o.deepCopy()
            c.getAsJsonObject("snapshot")?.remove("age-ms")
            return c.toString()
        }

        fun parse(service: String, env: String, o: JsonObject): Chain = Chain(
            service = o.str("service") ?: service, env = o.str("env") ?: env, module = o.str("module"),
            snapshot = o.obj("snapshot")?.let { Snapshot(it.get("present")?.takeIf { p -> p.isJsonPrimitive }?.asBoolean == true, it.str("path"), it.str("fetched-at"), it.long("age-ms")) }
                ?: Snapshot(false, null, null, null),
            entries = o.objs("entries").map(::entry), broken = o.objs("broken").map(::problem), unresolved = o.objs("unresolved").map(::problem),
            extra = o.objs("extra").map(::entry), hint = o.str("hint"), files = o.get("files").strings(), raw = o,
        )

        private fun problem(o: JsonObject) = Problem(o.str("kind"), o.str("reason"), o.obj("entry")?.let(::entry) ?: entry(JsonObject()))

        private fun entry(o: JsonObject) = Entry(
            o.str("placeholder"), o.str("default"), o.str("file"), o.int("line"), o.str("property"), o.bool("internal"), o.bool("uses-default"),
            o.obj("taskdef")?.let(::taskdef), o.obj("parameter")?.let(::param),
        )

        private fun taskdef(o: JsonObject) = Taskdef(
            o.str("kind"), o.str("name"), o.str("match"), o.bool("adopted"), o.str("value"), o.str("value-from"), o.str("expr"), o.bool("literal"),
            o.str("file"), o.int("line"), o.str("via"),
            o.obj("source")?.let { Source(it.str("file"), it.int("line"), it.str("expr"), it.bool("literal")) },
        )

        private fun param(o: JsonObject) = Param(
            o.str("ref"), o.str("kind"), o.str("name"), o.get("exists")?.takeIf { it.isJsonPrimitive }?.asBoolean, o.str("status"), o.str("reason"), o.str("hint"),
            o.str("value"), o.obj("metadata"), o.str("fetched-at"), o.bool("secure"), o.str("json-key"),
        )

        fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString?.removePrefix(":")?.takeIf { it.isNotEmpty() }
        fun JsonObject.int(k: String): Int? = get(k)?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull()
        fun JsonObject.long(k: String): Long? = get(k)?.takeIf { it.isJsonPrimitive }?.runCatching { asLong }?.getOrNull()
        fun JsonObject.bool(k: String): Boolean = get(k)?.takeIf { it.isJsonPrimitive }?.runCatching { asBoolean }?.getOrNull() == true
        fun JsonObject.obj(k: String): JsonObject? = get(k)?.takeIf { it.isJsonObject }?.asJsonObject
        fun JsonObject.objs(k: String): List<JsonObject> = get(k).let { a -> if (a != null && a.isJsonArray) a.asJsonArray.filter { it.isJsonObject }.map { it.asJsonObject } else emptyList() }
        private fun JsonElement?.strings(): List<String> =
            if (this is JsonArray) mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString } else emptyList()

        /** Two path strings naming the same file (the daemon's absolute path against the IDE's): normalised, real path as the fallback. */
        fun samePath(a: String?, b: String?): Boolean {
            if (a == null || b == null) return false
            val pa = Path.of(a).normalize()
            val pb = Path.of(b).normalize()
            if (pa == pb) return true
            return try { pa.toRealPath() == pb.toRealPath() } catch (_: Exception) { false }
        }

        fun parseInstant(s: String?): Instant? = s?.let { runCatching { Instant.parse(it) }.getOrNull() }
    }
}

/** `service.list` + `repo.list` reduced to what the file-to-service mapping needs. */
class ServiceIndex(val modules: List<Pair<String, Path>>) {
    /** The service whose module directory holds [file] (the deepest wins when modules nest), or null. */
    fun serviceOf(file: String): String? {
        val p = runCatching { Path.of(file).normalize() }.getOrNull() ?: return null
        return modules.filter { p.startsWith(it.second) }.maxByOrNull { it.second.nameCount }?.first
    }

    val services: List<String> get() = modules.map { it.first }

    companion object {
        fun parse(services: JsonElement?, repos: JsonElement?): ServiceIndex {
            val paths = HashMap<String, String>()
            (repos as? JsonArray)?.forEach { r -> (r as? JsonObject)?.let { o -> o.str("name")?.let { n -> o.str("path")?.let { paths[n] = it } } } }
            val out = ArrayList<Pair<String, Path>>()
            (services as? JsonArray)?.forEach { s ->
                val o = s as? JsonObject ?: return@forEach
                val name = o.str("name") ?: return@forEach
                val module = o.str("module") ?: return@forEach
                val repo = o.str("repo")
                val dir = if (repo != null) paths[repo]?.let { Path.of(it).resolve(module) } else Path.of(module)
                if (dir != null && dir.isAbsolute) out += name to dir.normalize()
            }
            return ServiceIndex(out)
        }
    }
}

/** Placeholder scanning of a piece of config text: `${NAME}` and `${NAME:default}`, nested defaults included (as `config.chain` lists them). */
object Placeholders {
    class Hit(val name: String, val start: Int, val end: Int)

    private val FILE = Regex("^application(-[^./\\\\]+)?\\.(properties|ya?ml)$")

    fun isConfigFileName(name: String) = FILE.matches(name)

    fun scan(text: CharSequence): List<Hit> {
        val out = ArrayList<Hit>()
        var i = 0
        while (i < text.length - 1) {
            if (text[i] == '$' && text[i + 1] == '{') {
                var j = i + 2
                while (j < text.length && text[j] != ':' && text[j] != '}' && text[j] != '\n' && text[j] != '$') j++
                if (j > i + 2 && j < text.length && (text[j] == ':' || text[j] == '}')) out += Hit(text.subSequence(i + 2, j).toString().trim(), i + 2, j)
                i += 2
            } else i++
        }
        return out
    }
}
