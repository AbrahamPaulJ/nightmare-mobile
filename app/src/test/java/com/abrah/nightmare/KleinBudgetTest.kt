package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ⭐ [SdSampler.kleinEditBudget]: a FLUX.2 run that reads a picture streams its DiT above 512x512 on a phone under the
 * 16 GB class (576x1024 with a reference was reaped unbudgeted on the 12 GB S25, 2026-10-23), and nothing else changes.
 */
class KleinBudgetTest {
    private val gb12 = 11_379_708L * 1024
    private val gb16 = 16L shl 30

    @Test
    fun aKleinEditAbove512SquaredStreamsOnA12GbPhone() {
        assertEquals("4", SdSampler.kleinEditBudget(Family.FLUX2, 2, 576, 1024, gb12, 79))
        assertEquals("4", SdSampler.kleinEditBudget(Family.FLUX2, 1, 896, 1344, gb12, 79))
        // ⚠ An unknown chip (arch 0) is treated as v79 and up.
        assertEquals("4", SdSampler.kleinEditBudget(Family.FLUX2, 1, 576, 1024, gb12, 0))
    }

    @Test
    fun everythingElseKeepsFullSpeed() {
        // Text to image, any size.
        assertNull(SdSampler.kleinEditBudget(Family.FLUX2, 0, 1024, 1024, gb12, 79))
        // 512x512 with a reference: measured resident, 20 s.
        assertNull(SdSampler.kleinEditBudget(Family.FLUX2, 2, 512, 512, gb12, 79))
        // The 16 GB class, and a phone whose RAM is unknown.
        assertNull(SdSampler.kleinEditBudget(Family.FLUX2, 2, 1024, 1024, gb16, 79))
        assertNull(SdSampler.kleinEditBudget(Family.FLUX2, 2, 1024, 1024, 0L, 79))
        // Another family.
        assertNull(SdSampler.kleinEditBudget(Family.ZIMAGE, 1, 1024, 1024, gb12, 79))
        // ⚠ Below v79 the chip's own 2.75 already applies to every DiT; a key budget would override it.
        assertNull(SdSampler.kleinEditBudget(Family.FLUX2, 2, 1024, 1024, gb12, 75))
    }
}
