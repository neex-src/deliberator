package com.neex.tellij.vars

import com.neex.tellij.model.WorkbenchParser
import com.neex.tellij.spi.StepError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VarsTest {

    @Test
    fun topDownEvaluationWithBuiltinsAndEnv() {
        val m = WorkbenchParser.parse("# @var id = ord-{{uuid}}\n# @var twice = {{id}}/{{id}}\n# @var pw = {{env.PG_PASSWORD}}\n")
        val vars = VarEngine.resolve(m, mapOf("PG_PASSWORD" to "demo"))
        val id = vars.values.getValue("id")
        assertTrue(id, Regex("ord-[0-9a-f]{8}").matches(id))
        assertEquals("$id/$id", vars.values.getValue("twice"))
        assertEquals("demo", vars.values.getValue("pw"))
    }

    @Test
    fun snapshotIsStableButDirectBuiltinsAreFresh() {
        val vars = VarEngine.resolve(WorkbenchParser.parse("# @var id = {{uuid}}\n"), emptyMap())
        assertEquals(vars.substitute("{{id}}"), vars.substitute("{{ id }}"))
        assertNotEquals(vars.substitute("{{uuid}}"), vars.substitute("{{uuid}}"))
    }

    @Test
    fun unknownVariableHasHint() {
        try {
            Vars(emptyMap()).substitute("x {{nope}} y")
            fail()
        } catch (e: StepError) {
            assertEquals("unknown variable {{nope}}", e.message)
            assertTrue(e.hint!!.contains("@var nope"))
        }
    }

    @Test
    fun referencedValuesInOrder() {
        val vars = Vars(mapOf("a" to "1", "b" to "2", "c" to "3"))
        assertEquals(mapOf("b" to "2", "a" to "1"), vars.referencedValues("{{b}} {{a}} {{b}} {{uuid}}"))
    }
}
