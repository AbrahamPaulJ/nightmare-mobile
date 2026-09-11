package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Five sampler names and a Karras toggle over nine wire values.
 *
 * ⚠⚠ The thing these tests actually guard is that the PRESENTATION never
 * invents a wire value the backend does not know. `Pipeline.hpp` matches the
 * scheduler with a chain of string comparisons and **an unknown string falls
 * through to `dpm` in silence** — so a UI that could emit `lcm_karras` would
 * render with the wrong sampler and report success.
 */
class SamplerNamingTest {

    /** ⭐ Round trip: every shipped wire value must survive split → join. */
    @Test
    fun everyWireValueRoundTrips() {
        for (v in ModelCatalog.SCHEDULERS) {
            val (base, karras) = ModelCatalog.splitScheduler(v)
            assertEquals("$v did not round-trip", v, ModelCatalog.joinScheduler(base, karras))
        }
    }

    /**
     * ⚠⚠ Whatever the picker can produce must be a value the backend knows.
     * Every (sampler × Karras) combination, checked against the shipped list.
     */
    @Test
    fun thePickerCanOnlyProduceKnownWireValues() {
        for ((id, _) in ModelCatalog.SAMPLERS) {
            for (karras in listOf(false, true)) {
                val v = ModelCatalog.joinScheduler(id, karras)
                assertTrue(
                    "\"$v\" is not a scheduler the backend implements",
                    v in ModelCatalog.SCHEDULERS,
                )
            }
        }
    }

    /**
     * ⚠⚠ **LCM has no Karras variant.** Ticking the box with LCM selected must
     * NOT produce `lcm_karras` — the backend has no such branch, so it would
     * silently render with `dpm`. Upstream guards the same case
     * (`karrasSupported = baseId != "lcm"`).
     */
    @Test
    fun lcmNeverGainsAKarrasSuffix() {
        assertFalse(ModelCatalog.karrasSupported("lcm"))
        assertEquals("lcm", ModelCatalog.joinScheduler("lcm", true))
        assertEquals("lcm", ModelCatalog.joinScheduler("lcm", false))
    }

    /** ⚠ …and every other sampler DOES have one, or the toggle is a lie. */
    @Test
    fun everyOtherSamplerHasAKarrasVariant() {
        for ((id, _) in ModelCatalog.SAMPLERS.filter { it.first != "lcm" }) {
            assertTrue(ModelCatalog.karrasSupported(id))
            assertTrue(ModelCatalog.joinScheduler(id, true) in ModelCatalog.SCHEDULERS)
        }
    }

    /**
     * ⚠ An unknown value resolves to the DEFAULT rather than passing through.
     * The backend would treat it as `dpm` anyway; showing nothing selected is
     * how that stays invisible to the user.
     */
    @Test
    fun anUnknownSchedulerFallsBackToTheDefault() {
        val (base, karras) = ModelCatalog.splitScheduler("heun_exponential")
        assertEquals(ModelCatalog.DEFAULT_SCHEDULER, base)
        assertFalse(karras)
    }

    /** ⚠ The five names cover exactly the nine wire values, with no orphans. */
    @Test
    fun theFiveSamplersCoverEveryWireValue() {
        val reachable = ModelCatalog.SAMPLERS.flatMap { (id, _) ->
            listOf(ModelCatalog.joinScheduler(id, false), ModelCatalog.joinScheduler(id, true))
        }.toSet()
        assertEquals(ModelCatalog.SCHEDULERS.toSet(), reachable)
    }

    /** Labels read the way other SD tools name them, Karras included. */
    @Test
    fun labelsAreTheFamiliarNames() {
        assertEquals("DPM++ 2M", ModelCatalog.schedulerLabel("dpm"))
        assertEquals("DPM++ 2M SDE Karras", ModelCatalog.schedulerLabel("dpm_sde_karras"))
        assertEquals("Euler A", ModelCatalog.schedulerLabel("euler_a"))
        assertEquals("LCM", ModelCatalog.schedulerLabel("lcm"))
    }
}
