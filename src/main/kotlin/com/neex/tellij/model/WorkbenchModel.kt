package com.neex.tellij.model

/**
 * Offsets into the document text, end exclusive. Our own type (not the platform's TextRange) so the
 * parser and everything downstream of it stay free of IntelliJ classes and unit-test without a fixture.
 */
data class TextRange(val start: Int, val end: Int) {
    val length: Int get() = end - start
    fun contains(offset: Int): Boolean = offset in start..<end
    fun substring(text: CharSequence): String = text.subSequence(start, end).toString()
}

/** `# @var NAME = VALUE` in the header; values are evaluated top-down at snapshot time. */
data class VarDecl(val name: String, val rawValue: String, val line: Int)

/** `# @connect KIND NAME k=v k="quoted v"` in the header. */
data class ConnectDecl(val kind: String, val name: String, val options: Map<String, String>, val line: Int) {
    fun option(key: String): String? = options[key]
}

/** `# @NAME VALUE` inside a block. */
data class Directive(val name: String, val value: String, val line: Int)

/**
 * One `###` block. [range] spans from the separator to the next separator (or EOF) and therefore
 * includes an existing [result] section; [body] is the payload/query text without comments.
 */
data class Block(
    val index: Int,
    val kind: String,
    val name: String?,
    val label: String?,
    val separatorLine: Int,
    val range: TextRange,
    val separator: TextRange,
    val directives: List<Directive>,
    val body: TextRange?,
    val result: TextRange?,
) {
    fun directive(name: String): String? = directives.lastOrNull { it.name == name }?.value
    fun directives(name: String): List<String> = directives.filter { it.name == name }.map { it.value }
    val displayName: String get() = if (name != null) "$kind $name" else kind
}

data class WorkbenchModel(
    val vars: List<VarDecl>,
    val connections: List<ConnectDecl>,
    val blocks: List<Block>,
    /** Everything before the first separator. */
    val headerRange: TextRange,
) {
    fun connection(kind: String, name: String): ConnectDecl? =
        connections.firstOrNull { it.kind == kind && it.name == name }

    fun connectionsOf(kind: String): List<ConnectDecl> = connections.filter { it.kind == kind }

    /** The block whose range contains [offset]; the caret at EOF still belongs to the last block. */
    fun blockAt(offset: Int): Block? =
        blocks.firstOrNull { it.range.contains(offset) } ?: blocks.lastOrNull()?.takeIf { offset == it.range.end }
}
