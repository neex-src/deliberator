package com.neex.tellij.java.endpoint

/** What a Spring MVC handler exposes, gathered from the impl method and its interface chain. */
data class Endpoint(
    val method: String,
    val path: String,
    val summary: String?,
    val description: String?,
    val tags: List<String>,
    val params: List<Param>,
    val body: DtoShape?,
    val responses: List<Response>,
    val impl: MethodRef,
) {
    val title: String get() = "$method $path"
    val primary: Response? get() = responses.firstOrNull { it.status in 200..299 }
}

enum class ParamWhere(val label: String) { PATH("path"), QUERY("query"), HEADER("header"), BODY("body") }

data class Param(val name: String, val where: ParamWhere, val type: String, val required: Boolean, val default: String?, val description: String?)

data class Response(val status: Int, val description: String?, val type: String?)

data class Field(val name: String, val type: String, val required: Boolean, val constraints: List<String>, val description: String?, val nested: DtoShape?)

data class DtoShape(val type: String, val fields: List<Field>)

/** Pure data about the implementation method — usable off the read action (debugger, results). */
data class MethodRef(
    val classFqn: String,
    val methodName: String,
    val declaredIn: String?,
    val fileUrl: String?,
    val line: Int,
    val firstStatementLine: Int,
) {
    val shortClass: String get() = classFqn.substringAfterLast('.')
    val display: String get() = "$shortClass.$methodName" + (declaredIn?.let { " ← $it" } ?: "")
}
