package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ⭐ [SpillFill] reads the size QNN asked for. The line is the one a user's
 * phone showed, 2026-10-01 (an SDXL checkpoint that would not start).
 */
class SpillFillTest {

    @Test
    fun itReadsTheSizeQnnAskedFor() {
        val log = listOf(
            "exec: libstable_diffusion_core.so --type sdxl",
            "[spill-fill] SDXL context group sharing enabled: 964689920 bytes",
            "QnnDsp <E> Shared spill-fill size 964689920 is smaller than required spill-fill size 1104281600",
            "ERROR: Pipeline initialization failed!",
        )
        assertEquals(1_104_281_600L, SpillFill.required(log))
    }

    /** ⚠ Two contexts, two needs: the group must cover the bigger. */
    @Test
    fun theBiggestNeedWins() {
        val log = listOf(
            "Shared spill-fill size 1 is smaller than required spill-fill size 700",
            "Shared spill-fill size 1 is smaller than required spill-fill size 900",
        )
        assertEquals(900L, SpillFill.required(log))
    }

    @Test
    fun anOrdinaryFailureTeachesNothing() {
        assertNull(SpillFill.required(listOf("ERROR: Pipeline initialization failed!")))
    }

    @Test
    fun onlyTheGroupedFamiliesHaveAVariable() {
        assertEquals("LOCALDREAM_SDXL_SPILL_FILL_BYTES", SpillFill.envFor(Family.SDXL))
        assertEquals("LOCALDREAM_ANIMA_SPILL_FILL_BYTES", SpillFill.envFor(Family.ANIMA))
        assertNull(SpillFill.envFor(Family.SD15))
        assertNull(SpillFill.envFor(Family.FLUX2))
    }
}
