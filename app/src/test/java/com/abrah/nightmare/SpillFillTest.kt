package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertFalse(SpillFill.groupFailed(listOf("ERROR: Pipeline initialization failed!")))
    }

    /**
     * ⭐ GitHub #8 (an SM8850, SDXL Swap, low-RAM off): the head failed on the HTP with no
     * size printed, and the VAE found no group — a group that cannot be made, not a small one.
     */
    @Test
    fun aGroupThatCouldNotBeMadeIsSeen() {
        val log = listOf(
            "[spill-fill] SDXL context group sharing enabled: 964689920 bytes",
            "QnnDsp <E>  contextFromBin (submit) Failed code: 5005",
            "QnnDsp <E> Context group 1 does not exist!",
            "QnnDsp <E> Failed to register spill-fill buffer for groupId 1, contextId 2, buffer size 964689920, priority 2147483647",
            "VAEDecoder Create From Binary failure",
        )
        assertNull(SpillFill.required(log))
        assertTrue(SpillFill.groupFailed(log))
    }

    /** ⭐ The same chain from a different head failure (2026-10-10, an SM8650, plain SDXL, a relaunch). */
    @Test
    fun aHeadThatCouldNotMapItsWeightsIsTheSameFailure() {
        val log = listOf(
            "QnnDsp <E> fastrpc memory map for fd: 27 with length: 2598371328 failed with error: 0x1",
            "QnnDsp <E> Could not allocate persistent weights buffer! core=0 weightsOffset=61194240 weightsSize=2597888000 farSize=0",
            "QnnDsp <E> Context 1 failed on pd 0",
            "QnnDsp <E> Context group 1 does not exist!",
            "VAEDecoder Create From Binary failure",
        )
        assertNull(SpillFill.required(log))
        assertTrue(SpillFill.groupFailed(log))
    }

    @Test
    fun onlyTheGroupedFamiliesHaveAVariable() {
        assertEquals("LOCALDREAM_SDXL_SPILL_FILL_BYTES", SpillFill.envFor(Family.SDXL))
        assertEquals("LOCALDREAM_ANIMA_SPILL_FILL_BYTES", SpillFill.envFor(Family.ANIMA))
        assertNull(SpillFill.envFor(Family.SD15))
        assertNull(SpillFill.envFor(Family.FLUX2))
    }
}
