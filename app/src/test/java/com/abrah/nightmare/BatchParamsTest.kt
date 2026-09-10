package com.abrah.nightmare

import com.abrah.nightmare.canvas.inpaintWorkflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchParamsTest {

    private fun armed(vararg pairs: Pair<String, String>): Graph {
        var g = inpaintWorkflow().graph
        for ((param, spec) in pairs) {
            g = g.withParam("sample", BatchParams.keyFor(param), spec)
        }
        return g
    }

    /**
     * ⭐⭐⭐ **The property the whole storage decision rests on.** The spec lives
     * in the node's params so it saves and shares — and it must NOT reach the
     * cache key, or arming a sweep would re-render the sampler and everything
     * downstream to change an icon's colour.
     */
    @Test
    fun armingDoesNotChangeTheCacheKey() {
        val plain = inpaintWorkflow().graph.byId.getValue("sample")
        val withBatch = armed("cfg" to "1..9 by 2").byId.getValue("sample")
        val inputs = emptyMap<String, Value>()
        assertEquals(
            cacheKey("sd.sample", "1", SampleNode.effectiveParams(plain), inputs),
            cacheKey("sd.sample", "1", SampleNode.effectiveParams(withBatch), inputs),
        )
    }

    /** ⚠ …but a REAL param change still must. The guard above must not be a hole. */
    @Test
    fun anOrdinaryParamStillChangesTheCacheKey() {
        val a = inpaintWorkflow().graph.byId.getValue("sample")
        val b = inpaintWorkflow().graph.withParam("sample", "steps", "31").byId.getValue("sample")
        val inputs = emptyMap<String, Value>()
        assertTrue(
            cacheKey("sd.sample", "1", SampleNode.effectiveParams(a), inputs) !=
                cacheKey("sd.sample", "1", SampleNode.effectiveParams(b), inputs)
        )
    }

    @Test
    fun armedAxesAreFoundOnTheGraph() {
        val axes = BatchParams.axesOf(armed("cfg" to "1..9 by 2"))
        assertEquals(1, axes.size)
        assertEquals("sample", axes[0].nodeId)
        assertEquals("cfg", axes[0].param)
        assertEquals(listOf("1", "3", "5", "7", "9"), axes[0].values)
        assertEquals(5, BatchParams.runCount(armed("cfg" to "1..9 by 2")))
    }

    /** ⚠ Two axes multiply — that is the 10x10 = 100 the estimate must warn about. */
    @Test
    fun twoAxesMultiply() {
        val g = armed("cfg" to "1..10", "steps" to "10, 20")
        assertEquals(2, BatchParams.axesOf(g).size)
        assertEquals(20, BatchParams.runCount(g))
    }

    /** ⚠ `scheduler` is a set of options, never a range — `dpm..euler` is nonsense. */
    @Test
    fun theSchedulerIsAnOptionSetNotARange() {
        assertTrue(!BatchParams.isRange("scheduler"))
        assertEquals(
            listOf("euler_a", "dpm", "ddim"),
            BatchParams.valuesOf("scheduler", "euler_a, dpm, ddim"),
        )
    }

    /**
     * ⚠⚠ The caps are refusals with a REASON, shown while typing rather than at
     * Run. "11 values" must say what to do about it.
     */
    @Test
    fun theCapsAreRefusedWithAReason() {
        val tooMany = BatchParams.refusalFor("cfg", "1..11", alreadyArmed = 0)
        assertNotNull(tooMany)
        assertTrue(tooMany!!.contains("${BatchParams.MAX_PER_AXIS}"))

        val thirdAxis = BatchParams.refusalFor("cfg", "1..3", alreadyArmed = 2)
        assertNotNull(thirdAxis)
        assertTrue(thirdAxis!!.contains("release"))

        assertNull(BatchParams.refusalFor("cfg", "1..3", alreadyArmed = 0))
        // ⚠ Blank is "not armed", not "invalid" — clearing a knob is how you
        // release it, and that must not read as an error.
        assertNull(BatchParams.refusalFor("cfg", "", alreadyArmed = 0))
    }

    /**
     * ⚠ Sampler knobs only — and `seed` IS one of them.
     *
     * ⚠⚠ This assertion flipped on 2026-09-10: seed was excluded because it
     * declares no range, and the answer was that a seed is swept by COUNT
     * rather than by range, not that it cannot be swept. The context key stays
     * out either way — varying it costs a relaunch per run.
     */
    @Test
    fun onlyTheAllowedParamsAreBatchable() {
        for (p in listOf("seed", "steps", "cfg", "denoise", "scheduler")) {
            assertTrue(p, BatchParams.isBatchable("sd.sample", p))
        }
        for (p in listOf("model", "width", "height")) {
            assertTrue(p, !BatchParams.isBatchable("sd.sample", p))
        }
        assertTrue(!BatchParams.isBatchable("image.crop", "x"))
    }

    /**
     * ⭐⭐ **A seed sweep is a RANGE of real seeds**, 1..10 in whole numbers.
     *
     * ⚠⚠ It was briefly a "count" of placeholder zeros that `runRolled` turned
     * into fresh random seeds. That ran, but every card was labelled "seed 0",
     * and rerunning the same sweep gave five different pictures — so the one
     * thing a seed is for, reproducing a picture, was lost. The user's call,
     * 2026-09-10: seeds 1..5 are seeds 1, 2, 3, 4, 5, every time.
     */
    @Test
    fun aSeedSweepIsAPlainRange() {
        assertTrue(BatchParams.isRange("seed"))
        val sweep = BatchParams.sweepFor("seed", null, null, true)
        assertEquals(1.0, sweep.min, 1e-9)
        assertEquals(10.0, sweep.max, 1e-9)
        assertEquals(1.0, sweep.step, 1e-9)
        assertTrue(sweep.int)
        assertEquals(listOf("1", "2", "3"), BatchParams.valuesOf("seed", "1..3"))
    }

    /**
     * ⚠⚠ **Two is the smallest sweep.** One value is the knob's ordinary value
     * with extra ceremony, and it would put a "1 run" batch in the run bar
     * doing exactly what Run already does.
     */
    @Test
    fun oneValueIsNotASweep() {
        assertEquals(2, BatchParams.MIN_PER_AXIS)
        assertEquals(emptyList<String>(), BatchParams.valuesOf("cfg", "7.5"))
        assertEquals(emptyList<String>(), BatchParams.valuesOf("scheduler", "dpm"))
        val why = BatchParams.refusalFor("cfg", "7.5", alreadyArmed = 0)
        assertNotNull(why)
        assertTrue(why!!.contains("not a sweep"))
        // ⚠ …and two still is one.
        assertEquals(2, BatchParams.valuesOf("cfg", "7.5, 8").size)
    }

    /**
     * ⚠⚠ Each knob sweeps in ITS OWN increment, and the step may only grow.
     * `cfg` in tenths because checkpoints publish 1.5 and 7.5; `steps` whole,
     * because "12.5 steps" is not a thing the sampler can do.
     */
    @Test
    fun eachKnobHasItsOwnStep() {
        assertEquals(0.1, BatchParams.sweepFor("cfg", 1.0, 20.0, false).step, 1e-9)
        assertEquals(1.0, BatchParams.sweepFor("steps", 1.0, 50.0, true).step, 1e-9)
        assertTrue(BatchParams.sweepFor("steps", 1.0, 50.0, true).int)
        assertEquals(0.05, BatchParams.sweepFor("denoise", 0.0, 1.0, false).step, 1e-9)
        assertTrue(!BatchParams.sweepFor("cfg", 1.0, 20.0, false).int)
    }
}
