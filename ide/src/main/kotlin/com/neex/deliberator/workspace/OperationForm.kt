/**
 * The generic operations form without UI: `op.describe` entries become [OpSpec]s, typed field values
 * become the args object ([build]) or a list of problems, and [run] sends the request. The dialog
 * is sugar over these three functions, so a new op is usable the day the core lists it.
 */
package com.neex.deliberator.workspace

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.neex.deliberator.daemon.DaemonClient
import com.neex.deliberator.daemon.DaemonEvent
import com.neex.deliberator.daemon.DaemonException
import com.neex.deliberator.daemon.DaemonTerminal

data class ArgSpec(val name: String, val type: String, val required: Boolean, val doc: String?, val default: JsonElement?, val positional: Boolean)

/** One operation: [args] are in declared order and exclude `ws`, which the project supplies ([takesWs]). */
data class OpSpec(val op: String, val mutating: Boolean, val doc: String?, val args: List<ArgSpec>, val takesWs: Boolean, val events: List<String>)

data class FormResult(val args: JsonObject?, val problems: List<String>)

object OperationForm {
    fun parse(describe: JsonArray): List<OpSpec> = describe.filter { it.isJsonObject }.map { it.asJsonObject }.map { e ->
        val argsObj = e.getAsJsonObject("args") ?: JsonObject()
        val order = e.get("order")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString } ?: argsObj.keySet().toList()
        val names = order + argsObj.keySet().filter { it !in order }
        val specs = names.mapNotNull { n ->
            val a = argsObj.getAsJsonObject(n) ?: return@mapNotNull null
            ArgSpec(n, a.get("type")?.asString ?: "string", a.get("required")?.asBoolean ?: false,
                a.get("doc")?.takeIf { it.isJsonPrimitive }?.asString,
                a.get("default")?.takeIf { !it.isJsonNull },
                a.get("positional")?.asBoolean ?: false)
        }
        OpSpec(e.get("op").asString, e.get("class")?.asString == "mutate", e.get("doc")?.takeIf { it.isJsonPrimitive }?.asString,
            specs.filter { it.name != "ws" }, specs.any { it.name == "ws" },
            e.get("events")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString } ?: emptyList())
    }.sortedBy { it.op }

    /** Text the form shows for an argument before the user types: its default, rendered as the field would be typed. */
    fun initialText(a: ArgSpec): String = a.default?.let { if (it.isJsonPrimitive) it.asString else it.toString() } ?: ""

    /** Field texts to args: blank optional fields are left out, `ws` is always [ws] when the op takes one. */
    fun build(spec: OpSpec, values: Map<String, String>, ws: String?): FormResult {
        val out = JsonObject()
        val problems = mutableListOf<String>()
        for (a in spec.args) {
            val raw = (values[a.name] ?: "").trim()
            if (raw.isEmpty()) {
                if (a.required) problems += "${a.name} is required"
                continue
            }
            try {
                out.add(a.name, convert(a.type, raw))
            } catch (e: IllegalArgumentException) {
                problems += "${a.name}: ${e.message}"
            }
        }
        if (spec.takesWs && ws != null) out.addProperty("ws", ws)
        return FormResult(if (problems.isEmpty()) out else null, problems)
    }

    private fun convert(type: String, raw: String): JsonElement = when (type) {
        "string" -> JsonPrimitive(raw)
        "int" -> JsonPrimitive(raw.toLongOrNull() ?: throw IllegalArgumentException("not an integer: $raw"))
        "number" -> JsonPrimitive(raw.toDoubleOrNull() ?: throw IllegalArgumentException("not a number: $raw"))
        "bool" -> when (raw.lowercase()) {
            "true", "yes", "on" -> JsonPrimitive(true)
            "false", "no", "off" -> JsonPrimitive(false)
            else -> throw IllegalArgumentException("not true or false: $raw")
        }
        "string[]" -> JsonArray().also { a -> parts(raw).forEach { a.add(it) } }
        "int[]" -> JsonArray().also { a -> parts(raw).forEach { a.add(it.toLongOrNull() ?: throw IllegalArgumentException("not an integer: $it")) } }
        else -> try { JsonParser.parseString(raw) } catch (e: Exception) { throw IllegalArgumentException("not JSON: ${e.message}") }
    }

    private fun parts(raw: String) = raw.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** Sends the request; events go to [onEvent] on the request's thread. Never call on the EDT. */
    fun run(client: DaemonClient, spec: OpSpec, args: JsonObject, onEvent: (DaemonEvent) -> Unit = {}): DaemonTerminal =
        client.call(spec.op, args, onEvent)

    /** `op.describe` as specs. */
    fun load(client: DaemonClient): List<OpSpec> {
        val t = client.call("op.describe")
        if (!t.ok) throw DaemonException(t.errorMessage ?: "op.describe failed", t.errorType ?: "daemon/error")
        return parse(t.result?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray())
    }
}
