package com.neex.deliberator.explorer

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerraformVarsTest {
    private val vars = TerraformVars.parse(JsonParser.parseString(
        """{"action":"vars","vars":{"service_a_url":"http://deliberator-w-service-a:8081","db_url":"db://deliberator-w-postgres:5432/deliberator"}}""").asJsonObject)

    @Test fun parses_the_result_sorted() {
        assertEquals(listOf("db_url", "service_a_url"), vars.keys.toList())
    }

    @Test fun apply_confirmation_lists_every_variable() {
        val t = TerraformVars.confirmText("/tf/root", "local", vars)
        assertTrue(t.startsWith("terraform apply of\n/tf/root\nonto environment local?"))
        assertTrue(t.contains("db_url        = db://deliberator-w-postgres:5432/deliberator"))
        assertTrue(t.contains("service_a_url = http://deliberator-w-service-a:8081"))
    }

    @Test fun apply_confirmation_without_variables_says_so() {
        assertTrue(TerraformVars.confirmText("/r", "local", emptyMap()).endsWith("No variables are passed."))
    }
}
