package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seven sampler names and three schedules over the wire values `<sampler>[_karras|_exponential]`
 * (backend 029, GitHub #19), and the DiT engine's samplers by their own names.
 *
 * ⚠⚠ The thing these tests actually guard is that the PRESENTATION never
 * invents a wire value the backend does not know. `Pipeline.hpp` matches the
 * scheduler by name and **an unknown base falls through to `dpm` in silence** —
 * so a UI that could emit `lcm_karras` would render with the wrong sampler and
 * report success.
 */
class SamplerNamingTest {

    /** ⭐ Round trip: every shipped wire value must survive split → join. */
    @Test
    fun everyWireValueRoundTrips() {
        for (v in ModelCatalog.SCHEDULERS + ModelCatalog.DIT_SCHEDULERS) {
            val (base, schedule) = ModelCatalog.splitScheduler(v)
            assertEquals("$v did not round-trip", v, ModelCatalog.joinScheduler(base, schedule))
        }
    }

    /** ⚠⚠ Whatever the picker can produce must be a value the backend knows. */
    @Test
    fun thePickerCanOnlyProduceKnownWireValues() {
        for ((id, _) in ModelCatalog.SAMPLERS) {
            for ((sfx, _) in ModelCatalog.SCHEDULES) {
                val v = ModelCatalog.joinScheduler(id, sfx)
                assertTrue("\"$v\" is not a scheduler the backend implements", v in ModelCatalog.SCHEDULERS)
            }
        }
    }

    /** ⚠⚠ **LCM has no schedule to change**: never `lcm_karras` or `lcm_exponential`. */
    @Test
    fun lcmNeverGainsAScheduleSuffix() {
        assertFalse(ModelCatalog.schedulesSupported("lcm"))
        assertEquals("lcm", ModelCatalog.joinScheduler("lcm", "_karras"))
        assertEquals("lcm", ModelCatalog.joinScheduler("lcm", "_exponential"))
        assertEquals("lcm", ModelCatalog.joinScheduler("lcm", true))
    }

    /** ⚠ …and every other SD sampler has all three, or the dropdown is a lie. */
    @Test
    fun everyOtherSamplerHasEverySchedule() {
        for ((id, _) in ModelCatalog.SAMPLERS.filter { it.first != "lcm" }) {
            assertTrue(ModelCatalog.schedulesSupported(id))
            assertTrue(ModelCatalog.joinScheduler(id, "_karras") in ModelCatalog.SCHEDULERS)
            assertTrue(ModelCatalog.joinScheduler(id, "_exponential") in ModelCatalog.SCHEDULERS)
        }
    }

    /** ⚠ The old ids (saved flows, `config.json`s) still parse as they did. */
    @Test
    fun theOldIdsStillParse() {
        assertEquals("dpm_sde" to "_karras", ModelCatalog.splitScheduler("dpm_sde_karras"))
        assertEquals("euler_a" to "", ModelCatalog.splitScheduler("euler_a"))
        assertEquals("dpm" to "", ModelCatalog.splitScheduler("dpm"))
    }

    /** ⚠ An unknown value resolves to the DEFAULT rather than passing through. */
    @Test
    fun anUnknownSchedulerFallsBackToTheDefault() {
        val (base, schedule) = ModelCatalog.splitScheduler("unipc_exponential")
        assertEquals(ModelCatalog.DEFAULT_SCHEDULER, base)
        assertEquals("", schedule)
    }

    /**
     * ⭐ The DiT families offer the engine's names, and since engine ABI 108 (`dit/032`) the same three schedules as
     * SD; Wan and Iris their names only (no schedule in the video request; Iris has its own sigmas); Anima (QNN) its two.
     */
    @Test
    fun ditFamiliesOfferTheEnginesSamplers() {
        assertEquals(ModelCatalog.DIT_SCHEDULERS, ModelCatalog.schedulersFor(Family.FLUX2))
        assertTrue("er_sde" in ModelCatalog.schedulersFor(Family.ANIMA_DIT))
        assertTrue("er_sde_karras" in ModelCatalog.schedulersFor(Family.ZIMAGE))
        assertEquals(ModelCatalog.DIT_SAMPLER_IDS, ModelCatalog.schedulersFor(Family.WAN))
        assertEquals(ModelCatalog.DIT_SAMPLER_IDS, ModelCatalog.schedulersFor(Family.IRIS))
        assertEquals(listOf("euler", "euler_a"), ModelCatalog.schedulersFor(Family.ANIMA))
        assertEquals("dpm++2m_karras", ModelCatalog.joinScheduler("dpm++2m", "_karras"))
        assertEquals("er_sde" to "_exponential", ModelCatalog.splitScheduler("er_sde_exponential"))
        assertEquals("ER-SDE Karras", ModelCatalog.schedulerLabel("er_sde_karras"))
        // ⚠ LCM has no schedule to change, on either engine.
        assertEquals("lcm", ModelCatalog.joinScheduler("lcm", "_karras"))
    }

    /** Labels read the way other SD tools name them. */
    @Test
    fun labelsAreTheFamiliarNames() {
        assertEquals("DPM++ 2M", ModelCatalog.schedulerLabel("dpm"))
        assertEquals("DPM++ 2M SDE Karras", ModelCatalog.schedulerLabel("dpm_sde_karras"))
        assertEquals("DPM++ 3M SDE Exponential", ModelCatalog.schedulerLabel("dpm3_sde_exponential"))
        assertEquals("Euler A", ModelCatalog.schedulerLabel("euler_a"))
        assertEquals("LCM", ModelCatalog.schedulerLabel("lcm"))
        assertEquals("ER-SDE", ModelCatalog.schedulerLabel("er_sde"))
        assertEquals("DPM++ 2M", ModelCatalog.schedulerLabel("dpm++2m"))
    }

    /** ⭐ SGM Uniform and Bong Tangent (backend 039): every DiT sampler has them, no SD sampler does. */
    @Test
    fun theDitModelsGetSgmUniformAndBongTangent() {
        assertTrue("euler_sgm_uniform" in ModelCatalog.DIT_SCHEDULERS)
        assertTrue("euler_a_bong_tangent" in ModelCatalog.DIT_SCHEDULERS)
        assertTrue(ModelCatalog.SCHEDULERS.none { it.endsWith("_sgm_uniform") || it.endsWith("_bong_tangent") })
        assertEquals("euler_a" to "_bong_tangent", ModelCatalog.splitScheduler("euler_a_bong_tangent"))
        assertEquals("dpm++2m_sde" to "_sgm_uniform", ModelCatalog.splitScheduler("dpm++2m_sde_sgm_uniform"))
        assertEquals("Euler SGM Uniform", ModelCatalog.schedulerLabel("euler_sgm_uniform"))
        assertEquals("lcm", ModelCatalog.joinScheduler("lcm", "_sgm_uniform"))
    }
}
