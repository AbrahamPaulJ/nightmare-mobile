package com.abrah.nightmare.canvas

import com.abrah.nightmare.CropGeometry
import com.abrah.nightmare.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crop rectangle's rules, and the pan/zoom that produces one.
 *
 * ⚠⚠ Pure maths, tested without a screen, because this is the part that fails
 * SILENTLY: a framing that is off by a factor renders the wrong region of the
 * photo and never throws. The editor would look correct and the picture would
 * be wrong, and no golden of the editor shows it.
 */
class CropRectTest {

    // A landscape photo, framed by a square viewport.
    private val vw = 400f
    private val vh = 400f
    private val iw = 1600f
    private val ih = 900f

    /**
     * ⚠ `outW = 0` is "nothing downstream demands a size", which is the case
     * every test below except the padding ones assumes. It is also the case in
     * which the old covering rule is unchanged, so these still pin what they
     * always pinned.
     */
    private fun minScale(outW: Int = 0) =
        com.abrah.nightmare.CropGeometry.minScale(vw, vh, iw, ih, outW)

    /**
     * ⭐ The whole contract in one line: what the viewport shows IS the crop.
     * At the covering scale, a square viewport over a 16:9 photo takes the full
     * height and a 9/16 slice of the width.
     */
    @Test
    fun theViewportIsTheCrop() {
        val s = minScale()
        val r = CropView.rectOf(vw, vh, iw, ih, s, 0f, 0f)
        assertEquals("full height", 1f, r.h, 1e-3f)
        assertEquals("a square slice of the width", 900f / 1600f, r.w, 1e-3f)
        assertEquals(0f, r.x, 1e-3f)
    }

    /**
     * ⚠⚠ The crop is SQUARE IN PIXELS whatever the picture's shape.
     *
     * This is the aspect lock, and it is geometry rather than a feature: the old
     * editor let a rectangle be any shape while the output was square, so a
     * non-square selection was silently stretched. Here the rect's normalised
     * numbers differ, and it is `w*imgW == h*imgH` that has to hold.
     */
    @Test
    fun theCropIsSquareInPixelsEvenThoughTheFractionsDiffer() {
        val s = minScale() * 1.7f
        val r = CropView.rectOf(vw, vh, iw, ih, s, -120f, -60f)
        assertTrue("the fractions should not be equal", kotlin.math.abs(r.w - r.h) > 0.05f)
        assertEquals("but the pixels must be", r.w * iw, r.h * ih, 1.0f)
    }

    /** ⚠ Round trip: framing a rect and reading it back must be the same rect. */
    @Test
    fun aRectSurvivesBeingPutOnScreenAndReadBack() {
        val r = CropRect(0.25f, 0.1f, 0.3f, 0.533f)
        val s = CropView.scaleFor(vw, iw, r)
        val back = CropView.rectOf(vw, vh, iw, ih, s, -r.x * iw * s, -r.y * ih * s)
        assertEquals(r.x, back.x, 2e-3f)
        assertEquals(r.y, back.y, 2e-3f)
        assertEquals(r.w, back.w, 2e-3f)
    }

    /**
     * ⚠⚠ A picture BIG ENOUGH may not be zoomed smaller than the frame:
     * bars around a photo that had the pixels all along would be a mistake, not
     * a choice.
     */
    @Test
    fun aBigEnoughPictureCannotBeZoomedSmallerThanTheFrame() {
        // 1600x900 asked for 512x512 -- both edges are larger, so covering holds.
        for (outW in listOf(0, 512)) {
            val s = minScale(outW)
            assertTrue("outW=$outW", iw * s >= vw - 0.5f)
            assertTrue("outW=$outW", ih * s >= vh - 0.5f)
        }
    }

    // --- padding: the one case where the frame leaves the picture ----------

    /**
     * ⭐⭐ …and a picture TOO SMALL may, because the only alternative is to
     * enlarge it. A 300x400 photo asked for 512² has fewer pixels than the
     * output in both axes; the honest answer is bars, not a soft picture.
     */
    @Test
    fun aTooSmallPictureMayBeZoomedOutUntilItStopsBeingEnlarged() {
        val small = 300f
        val tall = 400f
        val cover = CropGeometry.minScale(vw, vh, small, tall, 0)
        val padded = CropGeometry.minScale(vw, vh, small, tall, 512)
        assertTrue("padding must allow zooming out further", padded < cover)
        // ⚠ The floor is exactly 1 source pixel per output pixel -- below it
        // the crop would be enlarging again, which is what this avoids.
        assertEquals(vw / 512f, padded, 1e-4f)
        // …and at that floor the picture really is smaller than the frame.
        assertTrue("expected bars", small * padded < vw)
    }

    /**
     * ⚠⚠ "Too small" is per AXIS, not per area. A 600x400 photo has plenty
     * of width for a 512² output and not enough height, and enlarging it to
     * cover would soften the whole picture for the sake of one edge.
     */
    @Test
    fun oneShortEdgeIsEnoughToAllowPadding() {
        assertTrue(CropGeometry.needsPadding(600, 400, 512, 512))
        assertTrue(!CropGeometry.needsPadding(600, 600, 512, 512))
        // ⚠ …and with nothing demanding a size there is no enlargement to
        // avoid, so there is never padding.
        assertTrue(!CropGeometry.needsPadding(10, 10, 0, 0))
    }

    /**
     * ⭐ A picture smaller than the frame SLIDES INSIDE it rather than escaping
     * it -- the same clamp, read the other way round.
     */
    @Test
    fun aPaddedPictureStaysInsideTheFrame() {
        val s = 0.5f            // 1600*0.5 = 800 wide in a 400 viewport? no:
        val smallW = 300f
        val smallH = 400f
        val sc = CropGeometry.minScale(vw, vh, smallW, smallH, 512)
        val o = CropView.clampOffset(vw, vh, smallW, smallH, sc, -9999f, 9999f)
        assertTrue("pushed off the left: $o", o.x >= -0.001f)
        assertTrue("pushed off the bottom: $o", o.y <= vh - smallH * sc + 0.001f)
        assertTrue(s > 0f)
    }

    /** ⚠ …and the rect that describes it is allowed to hang over the edge. */
    @Test
    fun aPaddedRectMayReachOutsideTheImage() {
        val r = CropRect(-0.2f, -0.1f, 1.4f, 1.2f).clamped()
        assertEquals(-0.2f, r.x, 1e-4f)
        assertEquals(1.4f, r.w, 1e-4f)
    }

    /** ⚠⚠ …but never so far that the picture leaves the frame entirely. */
    @Test
    fun aRectCannotBeMovedOffThePictureAltogether() {
        val r = CropRect(-9f, 0f, 1f, 1f).clamped()
        assertTrue("no overlap left: $r", r.x + r.w > 0f)
        val q = CropRect(9f, 0f, 1f, 1f).clamped()
        assertTrue("no overlap left: $q", q.x < 1f)
    }

    /** ⚠ …and panning cannot walk the frame off the edge of the picture. */
    @Test
    fun panningStopsAtTheEdgeOfThePicture() {
        val s = minScale() * 1.4f
        val o = CropView.clampOffset(vw, vh, iw, ih, s, 9999f, -9999f)
        assertEquals("cannot pan past the left edge", 0f, o.x, 1e-3f)
        assertEquals("cannot pan past the bottom", vh - ih * s, o.y, 1e-3f)
        val r = CropView.rectOf(vw, vh, iw, ih, s, o.x, o.y)
        assertTrue("escaped: $r", r.x >= -1e-3f && r.x + r.w <= 1.001f)
        assertTrue("escaped: $r", r.y >= -1e-3f && r.y + r.h <= 1.001f)
    }

    @Test
    fun aRectCannotBeCollapsedToNothing() {
        // ⚠ A zero-width crop reaches `Bitmap.createBitmap` as width 0 and
        // crashes there -- at the wrong end of the app from the gesture.
        val r = CropRect(0.2f, 0.2f, 0f, -1f).clamped()
        assertTrue("w collapsed: $r", r.w >= CropRect.MIN)
        assertTrue("h collapsed: $r", r.h >= CropRect.MIN)
    }

    /** ⭐ The rect round-trips through the node params, which is where it lives. */
    @Test
    fun theRectRoundTripsThroughNodeParams() {
        val r = CropRect(0.125f, 0.25f, 0.5f, 0.375f)
        val node = Node("c", "image.crop", params = r.asParams().toMap())
        val back = cropRectOf(node)
        assertEquals(r.x, back.x, 1e-3f)
        assertEquals(r.y, back.y, 1e-3f)
        assertEquals(r.w, back.w, 1e-3f)
        assertEquals(r.h, back.h, 1e-3f)
    }

    /**
     * ⚠⚠ …and `near` has to accept that round trip, or the editor re-frames
     * itself on every pointer event: it emits a rect, the params round it to
     * three decimals, it comes back as a "change from outside", and the picture
     * jumps under the finger.
     */
    @Test
    fun aRoundedRectStillCountsAsTheSameOne() {
        val r = CropRect(0.1234f, 0.5678f, 0.4321f, 0.4321f)
        val back = cropRectOf(Node("c", "image.crop", params = r.asParams().toMap()))
        assertTrue("$r vs $back", r.near(back))
        assertTrue("a real move must NOT count as the same", !r.near(r.copy(x = r.x + 0.05f)))
    }

    /** ⚠ A node with no crop params yet is the WHOLE image, not an empty one. */
    @Test
    fun aFreshCropNodeFramesEverything() {
        assertEquals(CropRect.FULL, cropRectOf(Node("c", "image.crop")))
    }

    /** ⚠ And a malformed param must not stop the sheet opening. */
    @Test
    fun rubbishParamsFallBackRatherThanThrowing() {
        val back = cropRectOf(Node("c", "image.crop", params = mapOf("x" to "banana", "w" to "")))
        assertEquals(0f, back.x, 1e-4f)
        assertEquals(1f, back.w, 1e-4f)
    }
}
