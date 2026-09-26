package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⭐⭐ The upscale cap and the 2x/3x/4x choice (the user's calls, 2026-09-26):
 * a result may not pass [UpscaleNode.MAX_OUT_EDGE], and a picture too big for
 * the chosen scale falls back to the largest that fits.
 */
class UpscaleScaleTest {

    @Test
    fun fourTimesTakesUpTo1024() {
        assertEquals(4, UpscaleNode.fittingScale(1024, 768, 4))
        assertEquals("1025 px drops to 3x", 3, UpscaleNode.fittingScale(1025, 768, 4))
    }

    @Test
    fun aBigRenderFallsBackThenStops() {
        assertEquals(2, UpscaleNode.fittingScale(2048, 2048, 4))
        assertEquals("never ABOVE the choice", 2, UpscaleNode.fittingScale(512, 512, 2))
        assertEquals("past 2048 nothing fits", null, UpscaleNode.fittingScale(2049, 1000, 4))
    }

    @Test
    fun theParamReadsLikeTheChips() {
        assertEquals(3, UpscaleNode.scaleOf("3x"))
        assertEquals("unreadable is the native 4x", 4, UpscaleNode.scaleOf(null))
        assertEquals(2, UpscaleNode.scaleOf("1x"))
    }
}
