package com.abrah.nightmare.canvas

import org.junit.Assert.assertEquals
import org.junit.Test

/** ⭐ GitHub #95: the real step where the fraction IS the steps, a percentage where it is not. */
class RunStepLabelTest {
    private fun label(step: Pair<Int, Int>, asked: Int?, phase: String? = null) =
        RunLogState(step = step, askedSteps = asked, phase = phase).stepLabel

    @Test
    fun aDitTextToImageNamesItsStep() {
        assertEquals("3/8 · 33%", label(3 to 9, 8))
        assertEquals("0/8 · 0%", label(0 to 9, 8))
    }

    /** ⚠ The decode's slot, past the last step: "9/8" is the bug this must never show. */
    @Test
    fun theDecodeIsNotAStep() = assertEquals("100%", label(9 to 9, 8))

    /** ⚠ The QNN pipeline reports `steps + 2`: its numerator is not the user's step (see [RunLogState.step]). */
    @Test
    fun theQnnPipelineStaysAPercentage() = assertEquals("45%", label(10 to 22, 20))

    @Test
    fun noAskedStepsIsAPercentage() = assertEquals("33%", label(3 to 9, null))

    @Test
    fun aPhaseKeepsItsOwnCount() = assertEquals("pose 12/49", label(12 to 49, 8, "pose"))
}
