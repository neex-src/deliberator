package com.neex.deliberator.scn

import com.google.gson.JsonParser
import com.neex.deliberator.run.StepSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResponsePathsTest {
    @Test fun paths_of_a_response() {
        val j = JsonParser.parseString("""{"Messages":[{"Body":"hi","MessageAttributes":{"a-b":{"S":"x"}}}],"N":3}""")
        val p = ResponsePaths.of(j).associate { it.path to it.preview }
        assertEquals("[1]", p["$.Messages"])
        assertEquals("hi", p["$.Messages[0].Body"])
        assertEquals("x", p["$.Messages[0].MessageAttributes['a-b'].S"])
        assertEquals("3", p["$.N"])
    }

    @Test fun long_arrays_show_three_elements_and_limit_holds() {
        val j = JsonParser.parseString("""{"L":[1,2,3,4,5]}""")
        assertEquals(listOf("$.L", "$.L[0]", "$.L[1]", "$.L[2]"), ResponsePaths.of(j).map { it.path })
        assertEquals(1, ResponsePaths.of(j, limit = 2).size) // the root is walked but not offered
    }

    @Test fun response_is_the_first_json_line_before_the_checks() {
        val s = StepSection(1, "### x · ok · 1 ms", listOf("{\"a\":1}", "✓ a == 1"))
        assertEquals(1, ResponsePaths.of(ResponsePaths.response(s)).size)
        assertNull(ResponsePaths.response(StepSection(1, "### x · ok", listOf("✓ done"))))
        assertNull(ResponsePaths.response(StepSection(1, "### x · ok", listOf("{broken"))))
    }
}
