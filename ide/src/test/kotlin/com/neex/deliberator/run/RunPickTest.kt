package com.neex.deliberator.run

import com.neex.deliberator.logs.LogMatcher
import junit.framework.TestCase

/** B3 and B13: which run the correlation filter and Peek Response use. Pure functions, no platform. */
class RunPickTest : TestCase() {
    private fun snap(id: String, vararg steps: Int) =
        RunSnapshot(id, "/r/$id", "/r/$id/result.txt", "ok", null, steps.associateWith { StepSection(it, "STEP $it ok", listOf("response: {}")) }, emptyList(), "")

    fun testFilterPicksTheNewestRunWhoseIdIsInTheLog() {
        val runs = listOf(snap("run-c"), snap("run-b"), snap("run-a"))
        assertEquals("run-b", LogMatcher.runIn(runs, "x run-a y\nz run-b w")?.runId)
        assertEquals("run-c", LogMatcher.runIn(runs, "run-a run-b run-c")?.runId)
    }

    fun testFilterFindsNoRunWhenNoIdIsInTheLog() {
        assertNull(LogMatcher.runIn(listOf(snap("run-c")), "nothing here"))
        assertNull(LogMatcher.runIn(emptyList(), "run-c"))
    }

    fun testPeekUsesTheRunOnViewWhenItHasTheStep() {
        val shown = snap("new", 4)
        val last = mapOf(4 to StepSection(4, "STEP 4 ok", listOf("old")))
        assertSame(shown.sections[4], peekSection(shown, last, 4))
    }

    fun testPeekFallsBackToTheNewestSectionOfAStepTheLatestRunDidNotRun() {
        val shown = snap("single", 4)
        val last = snap("full", 1, 2, 3).sections
        assertSame(last[3], peekSection(shown, last, 3))
    }

    fun testPeekFindsNothingForAStepNeverRun() {
        assertNull(peekSection(snap("single", 4), snap("full", 1, 2).sections, 3))
        assertNull(peekSection(null, emptyMap(), 1))
    }
}
