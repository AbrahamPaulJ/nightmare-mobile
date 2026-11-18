package com.abrah.nightmare

import com.abrah.nightmare.canvas.RunLogState
import com.abrah.nightmare.canvas.RunProgress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐ Item 88 (Saved #8241-8244): one bar for the whole run. "Starting" swept to 80 %, then "0/8 · 0%" sat still for 13 s
 * while MOSS loaded, then jumped. Preparing fills its learned share by time and never past it; steps fill the rest.
 */
class RunProgressTest {

    @After
    fun clear() = RunProgress.clearForTest()

    @Test
    fun preparingFillsItsShareByTimeAndStopsShortOfIt() {
        val moss = RunProgress.Learned(prepMs = 13_000, totalMs = 16_000)
        assertEquals(0f, RunProgress.fraction(null, 0, moss), 1e-4f)
        val half = RunProgress.fraction(0 to 9, 6_500, moss)
        assertEquals(0.8125f * 0.5f, half, 1e-3f)
        // Past its usual time, still below the share: it never claims a step that has not come.
        val late = RunProgress.fraction(0 to 9, 60_000, moss)
        assertTrue(late < 0.8125f)
        // The first step starts AT the share, so the bar does not go backwards.
        val first = RunProgress.fraction(1 to 9, 60_000, moss)
        assertTrue(first > late)
        assertEquals(1f, RunProgress.fraction(9 to 9, 0, moss), 1e-4f)
    }

    @Test
    fun aModelNeverRunHereGetsTheDefaultSplit() {
        assertEquals(RunProgress.DEFAULT_SHARE * 0.5f, RunProgress.fraction(null, RunProgress.DEFAULT_PREP_MS / 2, null), 1e-3f)
        assertEquals(RunProgress.DEFAULT_SHARE + (1 - RunProgress.DEFAULT_SHARE) * 0.5f, RunProgress.fraction(5 to 10, 0, null), 1e-3f)
    }

    @Test
    fun theSplitIsLearnedPerModelAsARunningAverage() {
        RunProgress.record("moss", 12_000, 16_000)
        RunProgress.record("moss", 14_000, 16_000)
        assertEquals(RunProgress.Learned(13_000, 16_000), RunProgress.of("moss"))
        assertNull(RunProgress.of("sd15"))
        RunProgress.record("bad", 20_000, 16_000)
        assertNull("a prep longer than the node is ignored", RunProgress.of("bad"))
        assertEquals(RunProgress.Learned(5, 9), RunProgress.parse("5,9"))
        assertNull(RunProgress.parse("x,9"))
    }

    @Test
    fun theRunStateSaysPreparingOnlyForASamplerCountingNothing() {
        val s = RunLogState(now = "generate", startedAtMs = 1_000, progressKey = "moss", step = 0 to 9)
        assertTrue(s.preparing)
        assertNull("not 0/8 · 0%", s.stepLabel)
        assertTrue(!s.copy(step = 1 to 9).preparing)
        assertTrue("a node with no steps is not preparing", !s.copy(progressKey = null).preparing)
        // After the sampler, the output node is the run's end, not a fresh empty bar.
        assertEquals(1f, RunLogState(now = "output", startedAtMs = 1_000, stepsDone = true).fraction(30_000), 1e-4f)
    }
}
