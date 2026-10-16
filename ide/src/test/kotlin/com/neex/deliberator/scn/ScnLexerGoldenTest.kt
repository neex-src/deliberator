package com.neex.deliberator.scn

import com.intellij.lexer.Lexer
import junit.framework.TestCase
import java.io.File

/**
 * Token goldens (`src/test/resources/scn-golden/<name>.tokens`): every header form, directive, variable and
 * body shape, and all fixture scenarios. `SCN_GOLDEN_UPDATE=1 bb ide:test --tests '*ScnLexerGoldenTest*'`
 * rewrites them; read the diff before committing.
 */
class ScnLexerGoldenTest : TestCase() {
    private val goldenDir = File("src/test/resources/scn-golden")

    private fun dump(text: String): String {
        val lx: Lexer = ScnLexer()
        lx.start(text)
        val sb = StringBuilder()
        while (lx.tokenType != null) {
            val t = text.substring(lx.tokenStart, lx.tokenEnd).replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n")
            sb.append(lx.tokenType).append(" '").append(t).append("'\n")
            lx.advance()
        }
        return sb.toString()
    }

    private fun check(name: String, text: String) {
        val golden = File(goldenDir, "$name.tokens")
        val actual = dump(text)
        if (System.getenv("SCN_GOLDEN_UPDATE") == "1") {
            goldenDir.mkdirs(); golden.writeText(actual)
        } else {
            assertTrue("missing golden ${golden.path}; run with SCN_GOLDEN_UPDATE=1", golden.exists())
            assertEquals(name, golden.readText(), actual)
        }
        // whatever the tokens are, they tile the text without gaps (the highlighter relies on it)
        val lx = ScnLexer(); lx.start(text)
        var p = 0
        while (lx.tokenType != null) { assertEquals(name, p, lx.tokenStart); p = lx.tokenEnd; lx.advance() }
        assertEquals(name, text.length, p)
    }

    fun testLexerForms() { ScnTestData.lexerForms.forEach { (n, t) -> check("forms-$n", t) } }

    fun testFixtures() { ScnTestData.fixtures().forEach { (n, t) -> check("fixture-$n", t) } }

    fun testCorners() { ScnTestData.corners.forEach { (n, t) -> check("corner-$n", t) } }

    fun testOnlyTheFirstTokenIsAtStateZero() {
        val lx = ScnLexer(); lx.start("### wait\n# @for 1s\n")
        var i = 0
        while (lx.tokenType != null) { assertEquals(if (i == 0) 0 else 1, lx.state); i++; lx.advance() }
    }

    /** The cost of relexing from 0 on every edit; the number goes into. */
    fun testFiveThousandStepsTime() {
        val text = buildString {
            append("# @env local\n# @var a = 1\n\n")
            for (i in 1..5000) append("### sqs send queue/inbox step-$i\n# @attr id={{a}}\n# @assert messageId matches /x/\n{\"order\": \"A-$i\", \"qty\": 2}\n\n")
        }
        val times = (1..7).map {
            val t0 = System.nanoTime()
            val lx = ScnLexer(); lx.start(text)
            var c = 0
            while (lx.tokenType != null) { c++; lx.advance() }
            (System.nanoTime() - t0) / 1_000_000.0 to c
        }
        val sorted = times.map { it.first }.sorted()
        println("SCN-LEXER-5000: ${text.length} chars, ${times.last().second} tokens, lexer ms median=${sorted[3]} min=${sorted[0]} max=${sorted[6]}")
        assertTrue("lexing 5000 steps took ${sorted[3]} ms", sorted[3] < 2000)
    }
}
