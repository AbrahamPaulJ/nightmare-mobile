package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⭐ Which launches get `NM_KEEP_RUNNERS` (`dit/095`, LIST-N4 H1c) and `NM_SPECTRUM` (`dit/094`, GitHub #98): Anima's
 * DiT only, the one family measured; keeping only on a 12 GB phone (~3 GB more held between renders).
 */
class DitSpeedEnvTest {
    private val anima = ModelCatalog.byId("anima_turbo_1_1")!!
    private val twelve = 11L shl 30
    private val eight = 7L shl 30

    @Test
    fun animaOnATwelveGbPhoneKeepsItsWeights() {
        assertEquals(mapOf("NM_KEEP_RUNNERS" to "1"), BackendProcess.ditSpeedEnv(anima, twelve, spectrum = false))
        assertEquals(mapOf("NM_KEEP_RUNNERS" to "1", "NM_SPECTRUM" to "4"), BackendProcess.ditSpeedEnv(anima, twelve, spectrum = true))
    }

    @Test
    fun anEightGbPhoneLoadsAgainButMayStillPredictSteps() {
        assertEquals(emptyMap<String, String>(), BackendProcess.ditSpeedEnv(anima, eight, spectrum = false))
        assertEquals(mapOf("NM_SPECTRUM" to "4"), BackendProcess.ditSpeedEnv(anima, eight, spectrum = true))
        // ⚠ Unknown RAM (0) is the safe side: nothing held.
        assertEquals(emptyMap<String, String>(), BackendProcess.ditSpeedEnv(anima, 0L, spectrum = false))
    }

    @Test
    fun anUnmeasuredFamilyGetsNeither() {
        val others = ModelCatalog.all.filter { it.isDit && it.family != Family.ANIMA_DIT }
        assert(others.isNotEmpty())
        for (spec in others) assertEquals(spec.id, emptyMap<String, String>(), BackendProcess.ditSpeedEnv(spec, twelve, spectrum = true))
        assertEquals(emptyMap<String, String>(), BackendProcess.ditSpeedEnv(null, twelve, spectrum = true))
    }
}
