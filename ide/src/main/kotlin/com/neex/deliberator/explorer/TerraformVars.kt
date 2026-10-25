package com.neex.deliberator.explorer

import com.google.gson.JsonObject

/** The final `-var` values the daemon will give a Terraform root (`terraform.local` with action `vars`), and the Apply confirmation text that lists them. */
object TerraformVars {
    fun parse(result: JsonObject): Map<String, String> =
        result.getAsJsonObject("vars")?.entrySet()?.associate { it.key to it.value.asString }?.toSortedMap() ?: emptyMap()

    fun confirmText(root: String, env: String, vars: Map<String, String>): String {
        val sb = StringBuilder("terraform apply of\n$root\nonto environment $env?\n\n")
        if (vars.isEmpty()) sb.append("No variables are passed.")
        else {
            sb.append("Variables passed (-var):\n")
            val w = vars.keys.maxOf { it.length }
            vars.forEach { (k, v) -> sb.append("  ${k.padEnd(w)} = $v\n") }
        }
        return sb.toString().trimEnd()
    }
}
