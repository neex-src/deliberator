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

    // B39 / B36 / B37: what the real-account walk showed the explorer was missing: a hand change, the kinds the profile could not list, the refresh
    private val r = Drift.parse(JsonParser.parseString("""{"env":"ide-write","source":"/tf","declared":4,"matching":4,"missing":[],"undeclared":[],
        "refreshed":{"lines":25,"sha":"abc"},
        "changed":[{"address":"aws_ssm_parameter.p02","type":"aws_ssm_parameter","name":"p02","kind":"ssm","aws-name":"/rt1028-page/p02","actions":["update"],"attributes":["value","version"]}],
        "unknown":[{"kind":"iam","name":"role-x","address":"aws_iam_role.x"}]}""").asJsonObject)

    @Test fun a_refreshed_answer_names_what_was_changed_by_hand() {
        assertTrue(r.refreshed)
        assertEquals(listOf("value", "version"), r.changeOf("ssm", "/rt1028-page/p02")?.attributes)
        assertEquals("aws_ssm_parameter.p02", r.changeOf("ssm", "/rt1028-page/p02")?.address)
        assertEquals(null, r.changeOf("ssm", "/rt1028-page/p01"))
        assertEquals(null, d.changeOf("sqs", "gone"))
        assertTrue(!d.refreshed)
    }

    @Test fun the_report_lists_changed_and_unknown_and_never_values() {
        val t = r.text().lines()
        assertTrue(t[0].contains("refreshed from AWS"))
        assertTrue(t[1].contains("changed 1") && t[1].contains("unknown 1"))
        assertTrue(t.any { it.startsWith("CHANGED") && it.contains("/rt1028-page/p02") && it.contains("aws_ssm_parameter.p02") && it.contains("value, version") })
        assertTrue(t.any { it.startsWith("UNKNOWN") && it.contains("role-x") })
        assertTrue(!r.text().contains("no drift"))
        assertTrue("an answer without a refresh says nothing of changes", !d.text().contains("changed"))
    }
}
