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

    /** ⚠ The recipe must wire `base`/`repaint`/`mask` the right way round. */
    @Test
    fun theInpaintRecipeWiresBlendCorrectly() {
        val g = com.abrah.nightmare.canvas.inpaintWorkflow().graph
        val blend = g.byId["blend"]!!
        assertEquals("encode", blend.inputs["base"]?.node)
        assertEquals("sample", blend.inputs["repaint"]?.node)
        assertEquals("mask", blend.inputs["mask"]?.node)
        // ⚠⚠ base is the ORIGINAL. Wiring the sampled latent here replaces
        // everything except what was painted — a plausible picture, silently
        // wrong.
        assertEquals("frame", g.byId["encode"]!!.inputs["image"]?.node)
        assertEquals("frame", g.byId["mask"]!!.inputs["image"]?.node)
    }
}
