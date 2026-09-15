package com.abrah.nightmare

import com.abrah.nightmare.canvas.inpaintWorkflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐⭐ "Only masked": the rect DreamUI would choose, in source pixels.
 *
 * ⚠ The failures this pins are the silent ones — a crop of the wrong aspect
 * stretches the patch on the way back, one smaller than the output upscales it
 * blurrily, and one that pokes outside the frame reads pixels that are not there.
 * Every one of them still renders.
 */
class InpaintCropTest {

    @Test
    fun aSmallMaskGetsAnOutputSizedCropCentredOnIt() {
        // A 100 px blob in the middle of a 2000x1500 photo, rendering 512x512.
        val b = floatArrayOf(950f / 2000, 700f / 1500, 1050f / 2000, 800f / 1500)
        val r = InpaintCrop.compute(b, 2000, 1500, 512, 512)
        assertNotNull(r)
        r!!
        // ⚠ Never below one source pixel per output pixel: the patch is always
        // downscaled into the model, never enlarged.
        assertEquals(512, r[2])
        assertEquals(512, r[3])
        // Centred on the blob's centre (1000, 750).
        assertEquals(1000, r[0] + r[2] / 2)
        assertEquals(750, r[1] + r[3] / 2)
    }

    @Test
    fun theCropHasTheOutputsAspect() {
        val b = floatArrayOf(0.2f, 0.2f, 0.5f, 0.4f)
        val r = InpaintCrop.compute(b, 3000, 2000, 768, 512)!!
        assertEquals(768f / 512f, r[2].toFloat() / r[3], 0.01f)
    }

    @Test
    fun aMaskByTheEdgeSlidesTheCropInsideRatherThanClippingIt() {
        val b = floatArrayOf(0f, 0f, 0.05f, 0.05f)
        val r = InpaintCrop.compute(b, 2000, 2000, 512, 512)!!
        assertEquals(0, r[0])
        assertEquals(0, r[1])
        assertEquals(512, r[2])
    }

    @Test
    fun aMaskCoveringMostOfThePictureUsesTheWholeFrame() {
        assertNull(InpaintCrop.compute(floatArrayOf(0.05f, 0.05f, 0.95f, 0.95f), 1024, 1024, 512, 512))
        assertNull(InpaintCrop.compute(null, 1024, 1024, 512, 512))
    }

    @Test
    fun theWholeFrameFallbackIsTheLargestCentredRectOfTheOutputAspect() {
        val r = InpaintCrop.whole(2000, 1000, 512, 512)
        assertEquals(listOf(500, 0, 1000, 1000), r.toList())
    }

    @Test
    fun aPatchLandsOnThePhotoThroughTheFramesOwnRect() {
        // A frame cut from (400,300)-(1400,1300) of a photo, output at 1000x1000,
        // and a patch at (100,200)-(612,712) of that frame.
        val frame = Region(400f, 300f, 1400f, 1300f, 3000, 2000)
        val inner = Region(100f, 200f, 612f, 712f, 1000, 1000, frame)
        val r = InpaintCrop.toParent(inner, frame)
        assertEquals(listOf(500f, 500f, 1012f, 1012f), r.toList())
    }

    @Test
    fun aScaledFrameScalesThePatchRectWithIt() {
        // The frame covers 2000 photo px but was output at 1000: 2x.
        val frame = Region(0f, 0f, 2000f, 2000f, 4000, 4000)
        val inner = Region(100f, 100f, 356f, 356f, 1000, 1000, frame)
        val r = InpaintCrop.toParent(inner, frame)
        assertEquals(listOf(200f, 200f, 712f, 712f), r.toList())
    }

    /**
     * ⚠⚠ **Deleted 2026-09-15, and this is the note rather than the test.**
     * It asserted that the inpaint RECIPE left its `frame` node unsized and its
     * `cut` sized by the encoder. The recipe has neither node: the fused sampler
     * frames, cuts and pastes internally (docs/ARCHITECTURE.md §5.7), and what
     * it does with the rect is covered by [com.abrah.nightmare.FusedSamplerTest]
     * and by the arithmetic below, which is unchanged.
     */
}
