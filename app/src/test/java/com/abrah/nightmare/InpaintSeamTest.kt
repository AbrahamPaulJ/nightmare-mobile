package com.abrah.nightmare

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * ⭐⭐⭐ **A painted area must survive the composite, right up to the edge of
 * the patch.**
 *
 * ⚠⚠⚠ Reported from the phone 2026-09-21: with AbsoluteReality Inpaint,
 * clothes masked all the way down came back with the bottom edge unpainted.
 * The paste geometry was innocent — [InpaintPixels.feather] ramps alpha to
 * zero at any patch edge sitting inside the photo, to hide the patch's
 * rectangle, and it did that whether or not the MASK reached that edge. With
 * "Only masked" the crop is built around the painting, so the painting
 * reaching the crop edge is the common case, not the exotic one.
 *
 * ⚠⚠ The suite could not have caught it: every inpaint check was either a
 * geometry assertion (right size, right offset) or a golden of a synthetic
 * stripe, and this bug produces a perfectly plausible picture with a band of
 * the ORIGINAL in it. That is the failure mode `ImageOpsTest` warns about at
 * the top of the file, in the one place nobody wrote a test for.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InpaintSeamTest {

    private fun filled(w: Int, h: Int, c: Int) =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(c) }

    /** White (painted) over the bottom half, black above — it RUNS OFF the bottom. */
    private fun maskTouchingBottom(w: Int, h: Int): Bitmap {
        val m = filled(w, h, Color.BLACK)
        for (y in h / 2 until h) for (x in 0 until w) m.setPixel(x, y, Color.WHITE)
        return m
    }

    /**
     * ⭐ The patch is RED, the photo is BLUE, and the mask runs to the patch's
     * bottom. Every row the mask covers must come back red — a blue row inside
     * the painted area is the reported bug.
     *
     * ⚠ The patch is pasted in the MIDDLE of the photo, so `fadeBottom` is
     * live: its bottom edge sits inside the target, which is the condition
     * that used to trigger the ramp.
     */
    @Test
    fun paintReachesTheBottomOfThePatch() {
        val photo = filled(200, 400, Color.BLUE)
        val patch = filled(100, 100, Color.RED)
        val dst = RectF(50f, 100f, 150f, 200f)
        val out = InpaintPixels.composite(photo, patch, dst, maskTouchingBottom(100, 100))

        // The last row INSIDE the patch, at its centre.
        val x = 100
        val bottom = 199
        val px = out.getPixel(x, bottom)
        assertTrue(
            "the bottom row of the painted area is not red — it came back " +
                "#${Integer.toHexString(px)}, i.e. the original showed through",
            Color.red(px) > 200 && Color.blue(px) < 60,
        )
    }

    /**
     * ⚠⚠ The control, and it is what stops the fix from being "never fade".
     * An edge the mask does NOT reach must still fade, or the patch's
     * rectangle shows: its unmasked pixels have been resampled and VAE round
     * tripped, so a hard boundary is visible.
     */
    @Test
    fun anEdgeTheMaskDoesNotReachStillFades() {
        val photo = filled(200, 400, Color.BLUE)
        val patch = filled(100, 100, Color.RED)
        val dst = RectF(50f, 100f, 150f, 200f)
        // Painted only in the middle, nowhere near any edge.
        val mask = filled(100, 100, Color.BLACK)
        for (y in 40 until 60) for (x in 40 until 60) mask.setPixel(x, y, Color.WHITE)

        val out = InpaintPixels.composite(photo, patch, dst, mask)
        // The patch's top-left corner, far from the painting: still the photo.
        val px = out.getPixel(51, 101)
        assertTrue(
            "the patch corner was pasted at full strength — the rectangle will show",
            Color.blue(px) > 200 && Color.red(px) < 60,
        )
    }

    /**
     * ⚠ And the painting itself is still there in that case, or the fix for
     * one edge would have broken the ordinary centre-of-frame inpaint.
     */
    @Test
    fun theCentreIsStillPainted() {
        val photo = filled(200, 400, Color.BLUE)
        val patch = filled(100, 100, Color.RED)
        val dst = RectF(50f, 100f, 150f, 200f)
        val mask = filled(100, 100, Color.BLACK)
        for (y in 40 until 60) for (x in 40 until 60) mask.setPixel(x, y, Color.WHITE)

        val out = InpaintPixels.composite(photo, patch, dst, mask)
        val px = out.getPixel(100, 150)
        assertTrue(
            "the centre of the painted area is not red",
            Color.red(px) > 200 && Color.blue(px) < 60,
        )
    }
}
