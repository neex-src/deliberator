package com.neex.deliberator.explorer

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriftTest {
    private val d = Drift.parse(JsonParser.parseString("""{"env":"dev","source":"/tf","declared":20,"matching":19,
        "missing":[{"kind":"sqs","name":"gone","address":"module.m.aws_sqs_queue.gone"}],"undeclared":[{"kind":"sqs","name":"hand-made"}]}""").asJsonObject)

    @Test fun parses_and_marks() {
        assertEquals(19, d.matching); assertEquals("gone", d.missing[0].name)
        assertTrue(d.isUndeclared("sqs", "hand-made")); assertTrue(!d.isUndeclared("sqs", "gone"))
    }

    @Test fun report_is_text_one_line_per_finding() {
        val t = d.text().lines()
        assertTrue(t[1].contains("missing 1, undeclared 1"))
        assertTrue(t.any { it.startsWith("MISSING") && it.contains("gone") && it.contains("aws_sqs_queue.gone") })
        assertTrue(t.any { it.startsWith("UNDECLARED") && it.contains("hand-made") })
        assertTrue(Drift("e", "s", 0, 0, emptyList(), emptyList()).text().contains("no drift"))
    }
}
