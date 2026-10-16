package com.neex.deliberator.scn

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

data class ScnDirectiveSpec(
    val name: String,
    val hint: String?,
    val values: List<String>,
    val repeatable: Boolean,
    val doc: String?,
    val engine: Boolean,
)

data class ScnFactSpec(val name: String, val type: String)

/** One entry of `scenario.kinds`. [verbs] are the words only; [target] is `none|optional|required`. */
data class ScnKind(
    val kind: String,
    val verbs: List<String>,
    val verbOptional: Boolean,
    val target: String,
    val directives: List<ScnDirectiveSpec>,
    val facts: List<ScnFactSpec>,
    val captureRoots: List<String>,
    val concurrent: Boolean,
)

/** The registry as the daemon described it (`scenario.kinds`), by kind name. */
class ScnKinds(val kinds: List<ScnKind>) {
    private val byName = kinds.associateBy { it.kind }
    operator fun get(kind: String?): ScnKind? = if (kind == null) null else byName[kind]

    companion object {
        /** The directives every kind has (`kinds/engine-directives`), the static part of directive completion. */
        val ENGINE_DIRECTIVES = listOf(
            ScnDirectiveSpec("assert", "<fact|\$.path> <op> <value>", listOf("==", "!=", "<", "<=", ">", ">=", "contains", "matches"), true, null, true),
            ScnDirectiveSpec("capture", "name = <fact|\$.path>", emptyList(), true, null, true),
            ScnDirectiveSpec("until", "<assert> within <duration>", emptyList(), false, null, true),
            ScnDirectiveSpec("timeout", "<duration>", emptyList(), false, null, true),
            ScnDirectiveSpec("body", "<file, relative to the scenario>", emptyList(), false, null, true),
            ScnDirectiveSpec("name", "<step name>", emptyList(), false, null, true),
        )

        /** What the header (before the first step) understands. */
        val HEADER_DIRECTIVES = listOf(
            ScnDirectiveSpec("env", "<environment>", emptyList(), false, "the environment this scenario runs against", false),
            ScnDirectiveSpec("var", "name = value", emptyList(), true, "a variable for {{name}}", false),
        )

        val BUILTIN_VARS = listOf("uuid", "now", "now.epoch", "correlation", "step.correlation")

        fun parse(json: JsonElement): ScnKinds {
            val arr: JsonArray = if (json.isJsonArray) json.asJsonArray else JsonArray()
            return ScnKinds(arr.mapNotNull { e -> if (e.isJsonObject) kind(e.asJsonObject) else null })
        }

        private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString
        private fun JsonObject.strs(k: String): List<String> =
            get(k)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { x -> x.isJsonPrimitive }?.asString } ?: emptyList()
        private fun JsonObject.bool(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        private fun JsonObject.objs(k: String): List<JsonObject> =
            get(k)?.takeIf { it.isJsonArray }?.asJsonArray?.filter { it.isJsonObject }?.map { it.asJsonObject } ?: emptyList()

        private fun kind(o: JsonObject): ScnKind? {
            val name = o.str("kind") ?: return null
            return ScnKind(
                kind = name,
                verbs = o.strs("verbs"),
                verbOptional = o.bool("verb-optional"),
                target = o.str("target") ?: "optional",
                directives = o.objs("directives").mapNotNull { d ->
                    ScnDirectiveSpec(d.str("name") ?: return@mapNotNull null, d.str("hint"), d.strs("values"), d.bool("repeatable"), d.str("doc"), d.bool("engine"))
                },
                facts = o.objs("facts").mapNotNull { f -> ScnFactSpec(f.str("name") ?: return@mapNotNull null, f.str("type") ?: "string") },
                captureRoots = o.strs("capture-roots"),
                concurrent = o.bool("concurrent"),
            )
        }
    }
}

/** Which of the words after `### kind` are verb, target and name (indexes into the words, -1 = none): `split-words` of parse.clj. */
data class ScnRoles(val verb: Int, val target: Int, val name: Int) {
    companion object {
        fun targetLike(w: String) = w.contains('/') || w.startsWith("{{")

        /** [words] are the words after the kind; with [kind] the registry decides (as `parse` does with `:kinds`), without it the lexical rule. */
        fun of(words: List<String>, kind: ScnKind?): ScnRoles {
            val hasVerb = if (kind != null) words.isNotEmpty() && words[0] in kind.verbs
            else words.size >= 2 && !targetLike(words[0]) && targetLike(words[1])
            val off = if (hasVerb) 1 else 0
            val n = words.size - off
            val mode = kind?.target ?: "optional"
            var target = -1
            var name = -1
            when {
                n == 1 -> if (mode != "none" && (mode == "required" || targetLike(words[off]))) target = off else name = off
                n >= 2 -> { target = off; name = off + 1 }
            }
            return ScnRoles(if (hasVerb) 0 else -1, target, name)
        }
    }
}
