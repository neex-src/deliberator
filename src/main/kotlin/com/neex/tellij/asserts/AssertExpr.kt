package com.neex.tellij.asserts

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.neex.tellij.sources.http.JsonPathLite
import com.neex.tellij.spi.StepError
import java.math.BigDecimal

/**
 * `# @assert <operand> <op> <value>` — one line, one check, on the facts a source published.
 *
 *     status == 201          rows >= 1            $.status == "PAID"       col.status == "PAID"
 *     lines > 0              docs == 2            body contains "PAID"     messageId matches /^[0-9a-f-]+$/
 *     headers.content-type contains json          elapsed < 500            $.items[0].sku exists
 *
 * Numbers compare numerically when both sides are numeric, everything else as text. Missing
 * facts fail the check (with the list of facts that do exist), they never throw.
 */
class AssertExpr private constructor(val text: String, val operand: String, val op: Op, val value: Value?) {

    enum class Op(val token: String) { EQ("=="), NE("!="), LE("<="), GE(">="), LT("<"), GT(">"), CONTAINS("contains"), MATCHES("matches"), EXISTS("exists"), MISSING("missing") }

    sealed class Value(val text: String) {
        class Num(val n: BigDecimal, text: String) : Value(text)
        class Str(text: String) : Value(text)
        class Bool(val b: Boolean) : Value(b.toString())
        object Null : Value("null")
        class Re(val regex: Regex, text: String) : Value(text)
    }

    fun eval(facts: Facts): AssertResult {
        val found = facts.lookup(operand)
        val present = found is Facts.Lookup.Present && found.value != null && found.value !is JsonNull
        when (op) {
            Op.EXISTS -> return AssertResult(text, present, if (present) null else "missing")
            Op.MISSING -> return AssertResult(text, !present, if (present) Facts.text((found as Facts.Lookup.Present).value) else null)
            else -> {}
        }
        if (found !is Facts.Lookup.Present) return AssertResult(text, false, "no fact '$operand'; facts: ${facts.names().joinToString(", ")}")
        val actual = found.value
        val actualText = Facts.text(actual)
        val expected = value ?: return AssertResult(text, false, "missing value")
        val ok = when (op) {
            Op.CONTAINS -> actualText.contains(expected.text)
            Op.MATCHES -> (expected as? Value.Re)?.regex?.containsMatchIn(actualText) ?: actualText.contains(expected.text)
            else -> {
                val a = Facts.number(actual)
                val b = (expected as? Value.Num)?.n ?: expected.text.toBigDecimalOrNull()
                if (a != null && b != null) {
                    val c = a.compareTo(b)
                    when (op) { Op.EQ -> c == 0; Op.NE -> c != 0; Op.LT -> c < 0; Op.LE -> c <= 0; Op.GT -> c > 0; Op.GE -> c >= 0; else -> false }
                } else when (op) {
                    Op.EQ -> actualText == expected.text
                    Op.NE -> actualText != expected.text
                    else -> return AssertResult(text, false, "not a number: ${Facts.display(actual)}")
                }
            }
        }
        return AssertResult(text, ok, if (ok) null else Facts.display(actual))
    }

    companion object {
        private val SHAPE = Regex("""^\s*([$\w.\-\[\]]+)\s*(==|!=|<=|>=|<|>|contains|matches|exists|missing)\s*(.*?)\s*$""", RegexOption.IGNORE_CASE)
        const val HINT = "status == 200, \$.status == \"PAID\", rows >= 1, body contains \"x\", id matches /^ord-/"

        fun parse(text: String): AssertExpr {
            val m = SHAPE.matchEntire(text) ?: throw StepError("bad @assert '$text'", HINT)
            val op = Op.entries.first { it.token.equals(m.groupValues[2], ignoreCase = true) }
            val raw = m.groupValues[3]
            val value: Value? = when {
                op == Op.EXISTS || op == Op.MISSING -> { if (raw.isNotEmpty()) throw StepError("bad @assert '$text'", "'${op.token}' takes no value"); null }
                raw.isEmpty() -> throw StepError("bad @assert '$text'", "missing value after '${op.token}'")
                raw[0] in "=<>!" -> throw StepError("bad @assert '$text'", "operators are ${Op.entries.joinToString(" ") { it.token }}")
                op == Op.MATCHES && raw.startsWith("/") && raw.lastIndexOf('/') > 0 -> {
                    val close = raw.lastIndexOf('/')
                    val flags = raw.substring(close + 1)
                    val opts = if (flags.contains('i')) setOf(RegexOption.IGNORE_CASE) else emptySet()
                    Value.Re(try { Regex(raw.substring(1, close), opts) } catch (e: Exception) { throw StepError("bad regex in @assert '$text'", e.message) }, raw)
                }
                (raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2) || (raw.startsWith("'") && raw.endsWith("'") && raw.length >= 2) -> Value.Str(raw.substring(1, raw.length - 1))
                raw == "true" || raw == "false" -> Value.Bool(raw.toBoolean())
                raw == "null" -> Value.Null
                raw.toBigDecimalOrNull() != null -> Value.Num(raw.toBigDecimal(), raw)
                else -> Value.Str(raw)
            }
            val normalized = listOf(m.groupValues[1], op.token, raw).filter { it.isNotEmpty() }.joinToString(" ")
            return AssertExpr(normalized, m.groupValues[1], op, value)
        }

        fun parseAll(directives: List<String>): List<AssertExpr> = directives.map { parse(it) }
    }
}

data class AssertResult(val expr: String, val ok: Boolean, val actual: String?) {
    val line: String get() = if (ok) "✓ $expr" else "✗ $expr" + (actual?.let { " (was $it)" } ?: "")
}

/**
 * What a step published: plain values, plus JSON roots addressable by path (`$.a.b`, `doc.x`).
 * Header and column facts resolve case-insensitively.
 */
class Facts(private val values: Map<String, Any?>) {
    sealed class Lookup {
        class Present(val value: Any?) : Lookup()
        object Absent : Lookup()
    }

    fun names(): List<String> = values.keys.toList()

    fun lookup(path: String): Lookup {
        if (values.containsKey(path)) return Lookup.Present(values[path])
        if (path.startsWith("$")) return json(values["$"], path)
        if (path.startsWith("doc.")) return json(values["doc"], "$." + path.removePrefix("doc."))
        val ci = values.keys.firstOrNull { it.equals(path, ignoreCase = true) } ?: return Lookup.Absent
        return Lookup.Present(values[ci])
    }

    private fun json(root: Any?, path: String): Lookup {
        val el = root as? JsonElement ?: return Lookup.Absent
        return try { Lookup.Present(JsonPathLite.select(el, path)) } catch (e: StepError) { Lookup.Absent }
    }

    companion object {
        fun text(v: Any?): String = when (v) {
            null -> "null"
            is JsonElement -> when {
                v.isJsonNull -> "null"
                v.isJsonPrimitive && v.asJsonPrimitive.isNumber -> plain(v.asBigDecimal)
                v.isJsonPrimitive -> v.asString
                else -> v.toString()
            }
            is Double -> plain(v.toBigDecimal())
            is Float -> plain(v.toBigDecimal())
            is BigDecimal -> plain(v)
            is Collection<*> -> v.joinToString(",") { text(it) }
            else -> v.toString()
        }

        /** For the `(was …)` part: strings quoted so `"" ` and ` ` are distinguishable. */
        fun display(v: Any?): String = when {
            v is String -> "\"$v\""
            v is JsonElement && v.isJsonPrimitive && v.asJsonPrimitive.isString -> "\"${v.asString}\""
            else -> text(v)
        }

        fun number(v: Any?): BigDecimal? = when (v) {
            is Number -> BigDecimal(v.toString())
            is JsonElement -> if (v.isJsonPrimitive && v.asJsonPrimitive.isNumber) v.asBigDecimal else v.takeIf { it.isJsonPrimitive }?.asString?.toBigDecimalOrNull()
            is String -> v.toBigDecimalOrNull()
            else -> null
        }

        private fun plain(n: BigDecimal): String = n.stripTrailingZeros().toPlainString()
    }
}
