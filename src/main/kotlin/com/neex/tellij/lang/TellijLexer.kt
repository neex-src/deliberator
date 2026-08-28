package com.neex.tellij.lang

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType
import com.neex.tellij.model.ResultMarkers

object TellijTokens {
    val SEPARATOR = IElementType("TELLIJ_SEPARATOR", TellijLanguage)
    val DIRECTIVE = IElementType("TELLIJ_DIRECTIVE", TellijLanguage)
    val COMMENT = IElementType("TELLIJ_COMMENT", TellijLanguage)
    val VAR = IElementType("TELLIJ_VAR", TellijLanguage)
    val RESULT_MARKER = IElementType("TELLIJ_RESULT_MARKER", TellijLanguage)
    val RESULT_BODY = IElementType("TELLIJ_RESULT_BODY", TellijLanguage)
    val TEXT = IElementType("TELLIJ_TEXT", TellijLanguage)
}

/**
 * Hand-written, line-based lexer: every line is classified by its prefix, and `{{var}}` spans are
 * split out of separator/directive/body lines. State 1 = inside a result section, so incremental
 * re-lexing after an edit restarts correctly mid-file.
 */
class TellijLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var bufferEnd = 0
    private var state = 0

    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null
    private var tokenState = 0

    // The line currently being emitted, when it is split around {{vars}}.
    private var lineEnd = -1
    private var lineType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.bufferEnd = endOffset
        this.state = initialState
        this.tokenEnd = startOffset
        this.lineEnd = -1
        advance()
    }

    override fun getState(): Int = tokenState
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = bufferEnd

    override fun advance() {
        tokenStart = tokenEnd
        tokenState = state
        if (tokenStart >= bufferEnd) {
            tokenType = null
            return
        }
        if (tokenStart >= lineEnd) {
            // Classify a fresh line (including its terminator).
            var e = tokenStart
            while (e < bufferEnd && buffer[e] != '\n') e++
            val textEnd = e
            if (e < bufferEnd) e++
            lineEnd = e
            val line = buffer.subSequence(tokenStart, textEnd)
            lineType = classify(line)
        }
        val type = lineType!!
        if (type === TellijTokens.SEPARATOR || type === TellijTokens.DIRECTIVE || type === TellijTokens.TEXT) {
            // Split around {{...}}.
            val open = indexOf("{{", tokenStart, lineEnd)
            if (open == tokenStart) {
                val close = indexOf("}}", open + 2, lineEnd)
                if (close >= 0) {
                    tokenType = TellijTokens.VAR
                    tokenEnd = close + 2
                    return
                }
            } else if (open > tokenStart && indexOf("}}", open + 2, lineEnd) >= 0) {
                tokenType = type
                tokenEnd = open
                return
            }
        }
        tokenType = type
        tokenEnd = lineEnd
    }

    private fun classify(line: CharSequence): IElementType {
        if (state == 1) {
            if (ResultMarkers.isEnd(line)) { state = 0; return TellijTokens.RESULT_MARKER }
            return TellijTokens.RESULT_BODY
        }
        return when {
            line.startsWith("###") -> TellijTokens.SEPARATOR
            ResultMarkers.isStart(line) -> { state = 1; TellijTokens.RESULT_MARKER }
            line.startsWith("#") -> {
                val rest = line.subSequence(1, line.length).trimStart()
                if (rest.startsWith("@")) TellijTokens.DIRECTIVE else TellijTokens.COMMENT
            }
            else -> TellijTokens.TEXT
        }
    }

    private fun indexOf(s: String, from: Int, to: Int): Int {
        var i = from
        while (i + s.length <= to) {
            if (buffer[i] == s[0] && buffer[i + 1] == s[1]) return i
            i++
        }
        return -1
    }
}
