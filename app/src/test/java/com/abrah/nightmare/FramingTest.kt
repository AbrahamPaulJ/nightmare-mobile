package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **What shape a crop has to produce, and which wires can never work.**
 *
 * ⚠⚠ Pure arithmetic and pure graph reasoning, tested with no screen, because
 * both halves fail *silently*: a derived size that is wrong renders the wrong
 * region and never throws, and a wire that should have been refused looks
 * perfectly reasonable on the canvas right up until Run.
 */
class FramingTest {

    private val types = NODE_TYPES

    private fun graph(vararg n: Node) = Graph(n.toList())

    private val photo = Node("photo", "image.load", mapOf("uri" to "/a.png"))
    private val frame = Node("frame", "image.crop", inputs = sources("image" to "photo"))
    private fun encode(id: String, w: Int = 512, h: Int = 512, from: String = "frame") = Node(
        id, "sd.vae_encode",
        mapOf("model" to V1_MODEL, "width" to "$w", "height" to "$h", "seed" to "1"),
        sources("image" to from),
    )

    // --- what the graph demands --------------------------------------------

    /** ⭐ The whole "smart cropper": the consumer's demand, read off the graph. */
    @Test
    fun aConsumerThatNeedsASizeDemandsIt() {
        val d = requiredOutputSize(graph(photo, frame, encode("enc")), types, "frame")
        assertEquals(SizeDemand.Exactly(512, 512, listOf("enc")), d)
    }

    /** ⚠ …and its reason names WHO, because that is what a user needs to change it. */
    @Test
    fun theLockSaysWhoImposedIt() {
        val d = requiredOutputSize(graph(photo, frame, encode("enc")), types, "frame")
        assertTrue((d as SizeDemand.Exactly).reason().contains("enc"))
        assertTrue(d.reason().contains("512x512"))
    }

    /** ⚠ Nothing downstream, nothing demanded — the frame's own pixels are the output. */
    @Test
    fun anUnwiredCropIsDemandedNothing() {
        assertEquals(SizeDemand.None, requiredOutputSize(graph(photo, frame), types, "frame"))
    }

    /** ⚠ Two consumers agreeing is not a conflict; it is one demand with two names. */
    @Test
    fun twoConsumersWantingTheSameSizeAgree() {
        val d = requiredOutputSize(
            graph(photo, frame, encode("a"), encode("b")), types, "frame",
        )
        assertEquals(SizeDemand.Exactly(512, 512, listOf("a", "b")), d)
    }

    /**
     * ⚠⚠ …and two that disagree are a CONFLICT, not a first-wins. Picking one
     * would render a plausible picture for that branch and a wrong one for the
     * other, with nothing on screen admitting a choice had been made.
     */
    @Test
    fun twoConsumersWantingDifferentSizesConflict() {
        val d = requiredOutputSize(
            graph(photo, frame, encode("a"), encode("b", 768, 768)), types, "frame",
        )
        assertTrue("expected a conflict, got $d", d is SizeDemand.Conflict)
    }

    // --- which wires are refused, while the finger is still down -----------

    /**
     * ⭐⭐ `load_image` cannot promise a size now that it hands the photo on
     * whole, so this wire can only ever fail — and it is refused at the drop
     * rather than at Run, naming the fix.
     *
     * ⚠⚠ The refusal is about a PROMISE, not a measurement. Saying "needs
     * 512x512, got 3024x4032" would be worse than useless: it would be wrong for
     * the next photo.
     */
    @Test
    fun aPhotoMayNotBeWiredStraightIntoAnEncoder() {
        val why = sizeRefusal(
            graph(photo, encode("enc", from = "photo")), types, "photo", "enc", "image",
        )
        assertNotNull(why)
        assertTrue("should name the fix: $why", why!!.contains("Crop"))
    }

    /** ⭐ …and putting one between them is allowed, because that is what tells it what to make. */
    @Test
    fun aCropMayAlwaysBeWiredIntoAnEncoder() {
        assertNull(sizeRefusal(graph(photo, frame, encode("enc")), types, "frame", "enc", "image"))
    }

    /**
     * ⚠⚠ …but not into a SECOND encoder that wants something else. One node
     * cannot make two sizes, and refusing the wire that would create the
     * contradiction is kinder than letting the graph hold one and finding out at
     * Run which branch lost.
     */
    @Test
    fun aCropMayNotBeAskedForTwoDifferentSizes() {
        // ⚠ The graph as it is WHILE THE FINGER IS DOWN: `b` exists but its wire
        // has not landed. That is the state `CanvasState.refusal` reasons about,
        // and refusing here is what stops the conflict ever existing.
        val b = Node(
            "b", "sd.vae_encode",
            mapOf("model" to V1_MODEL, "width" to "768", "height" to "768", "seed" to "1"),
        )
        val g = graph(photo, frame, encode("a"), b)
        val why = sizeRefusal(g, types, "frame", "b", "image")
        assertNotNull(why)
        assertTrue("should name both sizes: $why", why!!.contains("768") && why.contains("512"))
    }

    /** ⚠ Re-dropping the wire that is already there is not a conflict with itself. */
    @Test
    fun rewiringTheSameConsumerIsNotAConflict() {
        val g = graph(photo, frame, encode("a"))
        assertNull(sizeRefusal(g, types, "frame", "a", "image"))
    }

    /** ⚠ An input nobody has an opinion about is never refused on size. */
    @Test
    fun anInputWithNoSizeDemandIsAlwaysFine() {
        val g = graph(photo, Node("out", "image.output", inputs = sources("image" to "photo")))
        assertNull(sizeRefusal(g, types, "photo", "out", "image"))
    }

    // --- the frame on the source -------------------------------------------

    /** ⭐ The normalised rect, in source pixels. */
    @Test
    fun theFrameLandsWhereTheFractionsSay() {
        val f = CropGeometry.frameOf(0.25f, 0.5f, 0.5f, 0.25f, 800, 400)
        assertEquals(200f, f.left, 1e-3f)
        assertEquals(200f, f.top, 1e-3f)
        assertEquals(400f, f.width(), 1e-3f)
        assertEquals(100f, f.height(), 1e-3f)
    }

    /**
     * ⚠⚠ …and it is NOT clamped into the bitmap. A frame that hangs over the
     * edge is how padding is expressed; clamping it here would silently
     * re-frame the crop instead of padding it.
     */
    @Test
    fun theFrameMayHangOverTheEdge() {
        val f = CropGeometry.frameOf(-0.5f, -0.5f, 2f, 2f, 100, 100)
        assertEquals(-50f, f.left, 1e-3f)
        assertEquals(150f, f.right, 1e-3f)
    }

    /** ⭐ 0x0 means "the framed region at its own size" — nothing is resampled. */
    @Test
    fun anUndemandedOutputIsTheFramedPixels() {
        val f = CropGeometry.frameOf(0f, 0f, 0.5f, 0.25f, 800, 400)
        assertEquals(400 to 100, CropGeometry.outputSize(0, 0, f, 8192))
    }

    /** ⚠ …and a demanded one is exactly that, whatever the frame covers. */
    @Test
    fun aDemandedOutputWins() {
        val f = CropGeometry.frameOf(0f, 0f, 0.5f, 0.25f, 800, 400)
        assertEquals(512 to 512, CropGeometry.outputSize(512, 512, f, 8192))
    }

    /** ⚠ Capped: a rect a finger drew must not allocate a bitmap nothing can hold. */
    @Test
    fun anAbsurdFrameCannotAllocateTheWorld() {
        val f = CropGeometry.frameOf(0f, 0f, 40f, 40f, 4000, 4000)
        val (w, h) = CropGeometry.outputSize(0, 0, f, 8192)
        assertEquals(8192, w)
        assertEquals(8192, h)
    }
}
