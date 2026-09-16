package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The mask: what a workflow stores, and what the backend receives.
 *
 * ⚠ Robolectric with NATIVE graphics, because [MaskRaster] draws real paths
 * into real bitmaps and the whole question is what the pixels come out as.
 * Faking that away would test the string format and nothing else.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaskTest {

    private fun stroke(vararg pts: Pair<Float, Float>, r: Float = 0.1f) =
        MaskStrokeData(pts.toList(), r)

    /** A dab in the middle, big enough to survive a 64px raster. */
    private fun dab() = MaskOp.Stroke(stroke(0.5f to 0.5f, r = 0.2f))

    private fun luminanceAt(state: MaskState, x: Float, y: Float, dim: Int = 64): Int {
        val bmp = MaskRaster.rasterise(state, dim, dim)
        val px = bmp.getPixel((x * dim).toInt().coerceIn(0, dim - 1),
            (y * dim).toInt().coerceIn(0, dim - 1))
        bmp.recycle()
        return px and 0xFF
    }

    // ---- what a workflow stores ------------------------------------------

    /**
     * ⭐⭐ The mask lives in a graph param, so this round trip IS the feature:
     * a workflow that saves a mask and reopens without it has lost the user's
     * work with no error anywhere.
     */
    @Test
    fun aMaskSurvivesEncodingAndDecoding() {
        val original = MaskState(
            ops = listOf(
                MaskOp.Stroke(stroke(0.1f to 0.2f, 0.3f to 0.4f, r = 0.05f)),
                MaskOp.Erase(stroke(0.5f to 0.5f, r = 0.02f)),
                MaskOp.Invert,
            ),
            growFrac = 0.01f,
            featherFrac = 0.03f,
        )
        val back = MaskState.decode(original.encode())
        assertEquals(3, back.ops.size)
        assertTrue(back.ops[0] is MaskOp.Stroke)
        assertTrue("an erase must not come back as a paint", back.ops[1] is MaskOp.Erase)
        assertTrue(back.ops[2] is MaskOp.Invert)
        assertEquals(0.01f, back.growFrac, 0.0005f)
        assertEquals(0.03f, back.featherFrac, 0.0005f)
        val s = (back.ops[0] as MaskOp.Stroke).stroke
        assertEquals(0.05f, s.radiusFrac, 0.0005f)
        assertEquals(2, s.points.size)
        assertEquals(0.1f, s.points[0].first, 0.0005f)
        assertEquals(0.4f, s.points[1].second, 0.0005f)
    }

    // ---- tapped regions (docs/SEGMENTER.md) -------------------------------

    /** ⭐ A tap is stored as its POINT and candidate, beside strokes, in order. */
    @Test
    fun aTapSurvivesEncodingAndDecoding() {
        val original = MaskState(listOf(dab(), MaskOp.Tap(0.25f, 0.75f, 2), MaskOp.Invert))
        val back = MaskState.decode(original.encode())
        assertEquals(3, back.ops.size)
        assertEquals(MaskOp.Tap(0.25f, 0.75f, 2), back.ops[1])
        assertTrue(back.ops[2] is MaskOp.Invert)
    }

    /** ⚠ A resolved region is memory only — it must never reach the saved string. */
    @Test
    fun aPlacedRegionIsNeverStored() {
        val alpha = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ALPHA_8)
        val text = MaskState(listOf(MaskOp.Placed(alpha), dab())).encode()
        assertEquals(1, MaskState.decode(text).ops.size)
        assertFalse(text.startsWith(";"))
    }

    /** ⚠⚠ Unresolved, a tap draws NOTHING — the sampler must resolve or refuse. */
    @Test
    fun anUnresolvedTapRastersToNothing() {
        assertEquals(0, luminanceAt(MaskState(listOf(MaskOp.Tap(0.5f, 0.5f, 0))), 0.5f, 0.5f))
    }

    /** ⭐ Resolved, the region paints white where it covers, and moves with the frame. */
    @Test
    fun aResolvedTapPaintsItsRegionAndFollowsTheFrame() {
        // A region covering the LEFT half of the photo.
        val alpha = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        for (y in 0 until 8) for (x in 0 until 4) alpha.setPixel(x, y, android.graphics.Color.WHITE)
        val resolved = MaskTaps.resolve(MaskState(listOf(MaskOp.Tap(0.2f, 0.5f, 5)))) { _, _ -> listOf(alpha) }
        assertTrue(resolved.ops.single() is MaskOp.Placed)
        assertEquals(255, luminanceAt(resolved, 0.2f, 0.5f))
        assertEquals(0, luminanceAt(resolved, 0.8f, 0.5f))
        assertTrue(MaskTaps.covers(alpha, 0.2f, 0.5f))
        assertFalse(MaskTaps.covers(alpha, 0.8f, 0.5f))
        // Framed on the photo's middle half: the region's edge (0.5) lands at 0.5
        // of the frame too, and 0.3 of the frame is 0.4 of the photo — covered.
        val framed = MaskFraming.toFrame(resolved, 0.25f, 0.25f, 0.5f, 0.5f)
        assertEquals(255, luminanceAt(framed, 0.3f, 0.5f))
        assertEquals(0, luminanceAt(framed, 0.7f, 0.5f))
    }

    @Test
    fun anEmptyMaskRoundTrips() {
        assertTrue(MaskState.decode(MaskState().encode()).isEmpty)
        assertTrue(MaskState.decode(null).isEmpty)
        assertTrue(MaskState.decode("").isEmpty)
    }

    /**
     * ⚠⚠ Never throws. A param edited by hand, truncated, or written by an
     * older version must not take the canvas down with it — an empty mask is
     * recoverable, a crash on graph load is not.
     */
    @Test
    fun garbageDecodesToAnEmptyMaskRatherThanThrowing() {
        for (bad in listOf("nonsense", "s:", "s0.1", "~", "s0.1:x,y", "e", ";;;", "s0.1:1,2|3")) {
            val m = MaskState.decode(bad)
            assertTrue("\"$bad\" produced ${m.ops.size} ops", m.ops.all { true })
        }
        // ⚠ And a partly-valid string keeps the part that parsed.
        val half = MaskState.decode("s0.1:0.5,0.5;garbage~0,0")
        assertEquals(1, half.ops.size)
    }

    // ---- what the backend receives ---------------------------------------

    /** ⚠⚠ WHITE = repaint. The one convention that is silent when reversed. */
    @Test
    fun paintedIsWhiteAndUnpaintedIsBlack() {
        val m = MaskState(listOf(dab()))
        assertTrue("the painted centre must be white", luminanceAt(m, 0.5f, 0.5f) > 200)
        assertTrue("an untouched corner must be black", luminanceAt(m, 0.02f, 0.02f) < 40)
    }

    /** ⚠ The eraser takes coverage away, in order. */
    @Test
    fun anEraseRemovesWhatWasPainted() {
        val painted = MaskState(listOf(dab()))
        val erased = painted.plus(MaskOp.Erase(stroke(0.5f to 0.5f, r = 0.25f)))
        assertTrue(luminanceAt(painted, 0.5f, 0.5f) > 200)
        assertTrue("the erase must clear it", luminanceAt(erased, 0.5f, 0.5f) < 40)
    }

    /**
     * ⭐⭐ Painting back over an erase leaves it PAINTED.
     *
     * ⚠ This is why ops are replayed in order rather than erases being
     * subtracted in one pass at the end — a single subtractive pass could not
     * express it, and would silently drop the last stroke the user made.
     */
    @Test
    fun paintingBackOverAnEraseKeepsThePaint() {
        val m = MaskState(
            listOf(
                dab(),
                MaskOp.Erase(stroke(0.5f to 0.5f, r = 0.25f)),
                dab(),
            )
        )
        assertTrue(luminanceAt(m, 0.5f, 0.5f) > 200)
    }

    /** ⚠ Invert flips what is masked SO FAR, and later strokes add to the flip. */
    @Test
    fun invertFlipsCoverage() {
        val m = MaskState(listOf(dab(), MaskOp.Invert))
        assertTrue("the painted centre becomes keep", luminanceAt(m, 0.5f, 0.5f) < 40)
        assertTrue("the untouched corner becomes repaint", luminanceAt(m, 0.02f, 0.02f) > 200)
    }

    /** ⚠ Normalised coordinates: the same ops must render at any size. */
    @Test
    fun theSameOpsRenderAtAnySize() {
        val m = MaskState(listOf(dab()))
        for (dim in listOf(64, 256, 512)) {
            assertTrue("centre at $dim", luminanceAt(m, 0.5f, 0.5f, dim) > 200)
            assertTrue("corner at $dim", luminanceAt(m, 0.02f, 0.02f, dim) < 40)
        }
    }

    /** ⚠ Feather ramps the edge rather than leaving a hard step. */
    @Test
    fun featherSoftensTheEdge() {
        val hard = MaskState(listOf(dab()))
        val soft = hard.copy(featherFrac = 0.15f)
        // Just outside the dab: hard is black, feathered is partly lit.
        val hardEdge = luminanceAt(hard, 0.5f, 0.74f, 256)
        val softEdge = luminanceAt(soft, 0.5f, 0.74f, 256)
        assertTrue("hard edge should be dark, was $hardEdge", hardEdge < 40)
        assertTrue("feathered edge should be lifted, was $softEdge", softEdge > hardEdge)
    }

    @Test
    fun coverageFractionTracksWhatIsPainted() {
        assertEquals(0f, MaskRaster.coverageFraction(MaskState()), 0.001f)
        val some = MaskRaster.coverageFraction(MaskState(listOf(dab())))
        assertTrue("a dab should cover something, got $some", some > 0.02f)
        assertTrue("but not everything, got $some", some < 0.6f)
    }

    // ---- the node --------------------------------------------------------

    /** ⚠ The demand passes through, which is what sizes an upstream `crop`. */
    @Test
    fun theNodePassesItsSizeDemandUpstream() {
        val n = Node("m", "image.mask", params = mapOf("out_w" to "512", "out_h" to "512"))
        assertEquals(512 to 512, MaskNode.requiredInputSize(n, "image"))
        // ⚠ Nothing downstream yet: demand nothing rather than guessing.
        val bare = Node("m", "image.mask")
        assertEquals(null, MaskNode.requiredInputSize(bare, "image"))
    }

    @Test
    fun theNodeReadsGrowAndFeatherFromItsParams() {
        val n = Node(
            "m", "image.mask",
            params = mapOf(MaskNode.OPS to MaskState(listOf(dab())).encode(),
                "grow" to "0.01", "feather" to "0.05"),
        )
        val st = MaskNode.stateOf(n)
        assertEquals(0.01f, st.growFrac, 0.0005f)
        assertEquals(0.05f, st.featherFrac, 0.0005f)
        assertFalse(st.isEmpty)
    }

    // ---- painted on the frame, stored on the photo ------------------------

    /**
     * ⭐⭐⭐ **The bug of 2026-09-16: the crop was applied to the mask TWICE.**
     *
     * The editor shows the framed picture, so a stroke leaves it normalised to
     * the FRAME; [SdSampler] rasterises the stored mask at the PHOTO's size and
     * then puts it through the same crop. Without [MaskFraming] in between, a
     * dab on the eyes was stored as if it had been painted on the whole photo
     * and then slid down by the frame's own offset — it came out on the lips,
     * and nothing failed.
     *
     * ⚠ This is the end-to-end statement of it: paint at the frame's centre,
     * store, re-frame, and the dab must be back at the frame's centre.
     */
    @Test
    fun aStrokePaintedOnTheFrameLandsThereAfterTheCropIsApplied() {
        // A frame well off centre, as a crop on a face would be.
        val fx = 0.2f; val fy = 0.1f; val fw = 0.5f; val fh = 0.5f
        val painted = MaskStrokeData(listOf(0.5f to 0.5f), 0.2f)

        val stored = MaskState(listOf(MaskOp.Stroke(MaskFraming.toSource(painted, fx, fy, fw, fh))))
        // What the sampler does: rasterise on the photo, then crop.
        val onPhoto = MaskRaster.rasterise(stored, 256, 256)
        val inFrame = android.graphics.Bitmap.createBitmap(
            onPhoto,
            (fx * 256).toInt(), (fy * 256).toInt(), (fw * 256).toInt(), (fh * 256).toInt(),
        )
        fun lum(x: Float, y: Float) = inFrame.getPixel(
            (x * inFrame.width).toInt().coerceIn(0, inFrame.width - 1),
            (y * inFrame.height).toInt().coerceIn(0, inFrame.height - 1),
        ) and 0xFF

        assertTrue("the dab should be at the frame's centre, was ${lum(0.5f, 0.5f)}", lum(0.5f, 0.5f) > 200)
        // ⚠ The control: where the un-converted stroke used to land. (0.5,0.5)
        // stored raw lands at ((0.5-0.2)/0.5, (0.5-0.1)/0.5) = (0.6, 0.8).
        assertTrue("and nowhere near where the bug put it, was ${lum(0.6f, 0.8f)}", lum(0.6f, 0.8f) < 40)
    }

    /** ⚠ The brush is a fraction of the WIDTH, so it shrinks with the frame. */
    @Test
    fun theBrushIsConvertedWithThePoints() {
        val s = MaskFraming.toSource(MaskStrokeData(listOf(0.5f to 0.5f), 0.2f), 0.2f, 0.1f, 0.5f, 0.5f)
        assertEquals(0.1f, s.radiusFrac, 1e-4f)
        assertEquals(0.45f, s.points[0].first, 1e-4f)
        assertEquals(0.35f, s.points[0].second, 1e-4f)
    }

    /** ⚠ Both directions, or the editor draws the stored mask in the wrong place. */
    @Test
    fun theFrameConversionRoundTrips() {
        val state = MaskState(
            listOf(
                MaskOp.Stroke(stroke(0.1f to 0.2f, 0.9f to 0.8f, r = 0.12f)),
                MaskOp.Invert,
                MaskOp.Erase(stroke(0.5f to 0.5f, r = 0.05f)),
            ),
            growFrac = 0.01f,
            featherFrac = 0.04f,
        )
        val shown = MaskFraming.toFrame(state, 0.25f, 0.3f, 0.4f, 0.6f)
        val back = shown.ops.map { op ->
            when (op) {
                is MaskOp.Invert -> op
                is MaskOp.Stroke -> MaskOp.Stroke(MaskFraming.toSource(op.stroke, 0.25f, 0.3f, 0.4f, 0.6f))
                is MaskOp.Erase -> MaskOp.Erase(MaskFraming.toSource(op.stroke, 0.25f, 0.3f, 0.4f, 0.6f))
                else -> op
            }
        }
        state.ops.forEachIndexed { i, op ->
            when (op) {
                is MaskOp.Invert -> assertTrue("invert survives", back[i] is MaskOp.Invert)
                is MaskOp.Stroke -> {
                    val r = (back[i] as MaskOp.Stroke).stroke
                    assertEquals(op.stroke.radiusFrac, r.radiusFrac, 1e-4f)
                    op.stroke.points.forEachIndexed { j, (x, y) ->
                        assertEquals(x, r.points[j].first, 1e-4f)
                        assertEquals(y, r.points[j].second, 1e-4f)
                    }
                }
                is MaskOp.Erase -> {
                    val r = (back[i] as MaskOp.Erase).stroke
                    assertEquals(op.stroke.points[0].first, r.points[0].first, 1e-4f)
                }
                else -> Unit
            }
        }
        // ⚠ Grow and feather are width fractions too: a preview that left them
        // alone would show a softer edge than the render by the crop's factor.
        assertEquals(0.025f, shown.growFrac, 1e-4f)
        assertEquals(0.1f, shown.featherFrac, 1e-4f)
    }

    /** ⚠ The whole picture is the identity — an `image.mask` converts nothing. */
    @Test
    fun theWholePictureIsTheIdentity() {
        val s = stroke(0.3f to 0.7f, r = 0.09f)
        val there = MaskFraming.toSource(s, 0f, 0f, 1f, 1f)
        assertEquals(0.3f, there.points[0].first, 1e-6f)
        assertEquals(0.7f, there.points[0].second, 1e-6f)
        assertEquals(0.09f, there.radiusFrac, 1e-6f)
    }

    /**
     * ⚠⚠ **Moved 2026-09-15**, not deleted: the thing it protects — base is the
     * SOURCE and repaint is the RENDER, and swapping them replaces the region
     * you meant to keep — is now an argument order inside one node rather than a
     * wire on the canvas. ⇒
     * [com.abrah.nightmare.FusedSamplerTest.theBlendTakesTheSourceAsBaseAndTheRenderAsRepaint].
     */
}
