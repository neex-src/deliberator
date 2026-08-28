package com.neex.tellij.lang

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TellijLexerTest : BasePlatformTestCase() {

    private fun tokens(text: String): List<Pair<String, String>> {
        val lexer = TellijLexer()
        lexer.start(text, 0, text.length, 0)
        val out = ArrayList<Pair<String, String>>()
        while (lexer.tokenType != null) {
            out += lexer.tokenType.toString() to text.substring(lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return out
    }

    fun testLineClassification() {
        val t = tokens("# c\n# @var a = 1\n### kafka x\nbody\n#> result kafka x\nin\n#< end\nafter\n")
        assertEquals(
            listOf(
                "TELLIJ_COMMENT" to "# c\n",
                "TELLIJ_DIRECTIVE" to "# @var a = 1\n",
                "TELLIJ_SEPARATOR" to "### kafka x\n",
                "TELLIJ_TEXT" to "body\n",
                "TELLIJ_RESULT_MARKER" to "#> result kafka x\n",
                "TELLIJ_RESULT_BODY" to "in\n",
                "TELLIJ_RESULT_MARKER" to "#< end\n",
                "TELLIJ_TEXT" to "after\n",
            ), t
        )
    }

    fun testVarsAreSplitOut() {
        val t = tokens("# @key {{id}}\n{\"a\": \"{{id}}\"}\n{{x}}")
        assertEquals(
            listOf(
                "TELLIJ_DIRECTIVE" to "# @key ",
                "TELLIJ_VAR" to "{{id}}",
                "TELLIJ_DIRECTIVE" to "\n",
                "TELLIJ_TEXT" to "{\"a\": \"",
                "TELLIJ_VAR" to "{{id}}",
                "TELLIJ_TEXT" to "\"}\n",
                "TELLIJ_VAR" to "{{x}}",
            ), t
        )
    }

    fun testResultBodyKeepsBracesVerbatim() {
        val t = tokens("#> result k\n{{notavar}}\n#< end")
        assertEquals("TELLIJ_RESULT_BODY" to "{{notavar}}\n", t[1])
    }

    fun testHighlighterCoversEverything() {
        val text = "### a\n# @b\n#> result a\n#< end\n"
        val lexer = TellijLexer()
        lexer.start(text, 0, text.length, 0)
        var covered = 0
        while (lexer.tokenType != null) { covered += lexer.tokenEnd - lexer.tokenStart; lexer.advance() }
        assertEquals(text.length, covered)
        assertEquals(1, TellijHighlighter().getTokenHighlights(TellijTokens.SEPARATOR).size)
    }
}
