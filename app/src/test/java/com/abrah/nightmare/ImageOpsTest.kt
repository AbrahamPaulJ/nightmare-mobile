package com.abrah.nightmare

import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The Tier 0 image ops, checked on real pixels.
 *
 * ⚠ Pixels, not just sizes. Every op here is a picture operation, and the
 * failure that matters is not "it threw" — it is "it produced a plausible image
 * with the wrong thing in it", which no size assertion can see. The mask tests
 * below exist for exactly that reason.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageOpsTest {

    private val images = ImageStore()
    private val surface = HostSurface(images)

    private val plugin = Plugin.parse(
        """
        {
          "id": "com.example.p", "version": "1", "api": 1,
          "nodes": [{ "type": "T", "outputs": [{ "name": "image", "type": "IMAGE" }] }],
          "permissions": ["image"]
        }
        """.trimIndent(),
        "// none",
    )

    private fun call(op: String, args: JSONObject): String =
        surface.asPlugin(plugin) { surface.call(op, args.toString()) }

    private fun outImage(op: String, args: JSONObject): Bitmap =
        images.get(JSONObject(call(op, args)).getString("image"))!!

    private fun solid(w: Int, h: Int, color: Int): String {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return images.put(bmp)
    }

    private fun args(vararg pairs: Pair<String, Any>) =
        JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }

    @Test
    fun newMakesTheColourAskedFor() {
        val bmp = outImage("image.new", args("width" to 4, "height" to 4, "color" to "#ff8800"))
        assertEquals(4, bmp.width)
        assertEquals(Color.parseColor("#ff8800"), bmp.getPixel(2, 2))
    }

    /** ⚠ Naming the value, because "#ff88" is a typo a plugin author will make. */
    @Test
    fun anUnparseableColourIsRefusedByName() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            call("image.new", args("width" to 2, "height" to 2, "color" to "orange-ish"))
        }
        assertTrue(e.message!!, e.message!!.contains("orange-ish"))
    }

    @Test
    fun grayscaleEqualisesTheChannels() {
        val src = solid(2, 2, Color.parseColor("#ff3366"))
        val p = outImage("image.grayscale", args("image" to src)).getPixel(0, 0)
        assertEquals(Color.red(p), Color.green(p))
        assertEquals(Color.green(p), Color.blue(p))
        assertEquals(255, Color.alpha(p))
    }

    @Test
    fun invertFlipsTheChannels() {
        val src = solid(2, 2, Color.rgb(10, 20, 30))
        val p = outImage("image.invert", args("image" to src)).getPixel(0, 0)
        assertEquals(245, Color.red(p))
        assertEquals(235, Color.green(p))
        assertEquals(225, Color.blue(p))
    }

    /** ⚠ …and leaves alpha alone. Inverting a mask must not erase it. */
    @Test
    fun invertKeepsAlpha() {
        val src = solid(2, 2, Color.argb(255, 10, 20, 30))
        assertEquals(255, Color.alpha(outImage("image.invert", args("image" to src)).getPixel(0, 0)))
    }

    @Test
    fun blendAtZeroIsAllA() {
        val a = solid(2, 2, Color.RED)
        val b = solid(2, 2, Color.BLUE)
        val p = outImage("image.blend", args("a" to a, "b" to b, "alpha" to 0.0)).getPixel(0, 0)
        assertEquals(Color.RED, p)
    }

    @Test
    fun blendAtOneIsAllB() {
        val a = solid(2, 2, Color.RED)
        val b = solid(2, 2, Color.BLUE)
        val p = outImage("image.blend", args("a" to a, "b" to b, "alpha" to 1.0)).getPixel(0, 0)
        assertEquals(Color.BLUE, p)
    }

    @Test
    fun blendAtAHalfIsBetween() {
        val a = solid(2, 2, Color.BLACK)
        val b = solid(2, 2, Color.WHITE)
        val p = outImage("image.blend", args("a" to a, "b" to b, "alpha" to 0.5)).getPixel(0, 0)
        assertTrue("expected a mid grey, got ${Integer.toHexString(p)}", Color.red(p) in 120..136)
    }

    @Test
    fun blendRefusesMismatchedSizes() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            call("image.blend", args("a" to solid(4, 4, 0), "b" to solid(2, 2, 0), "alpha" to 0.5))
        }
        assertTrue(e.message!!, e.message!!.contains("matching sizes"))
    }

    @Test
    fun blendRefusesAnAlphaOutsideTheRange() {
        val a = solid(2, 2, 0)
        val e = assertThrows(IllegalArgumentException::class.java) {
            call("image.blend", args("a" to a, "b" to a, "alpha" to 1.5))
        }
        assertTrue(e.message!!, e.message!!.contains("0..1"))
    }

    @Test
    fun compositePlacesTheOverlayAtTheOffset() {
        val base = solid(8, 8, Color.BLACK)
        val overlay = solid(2, 2, Color.WHITE)
        val out = outImage(
            "image.composite",
            args("base" to base, "overlay" to overlay, "x" to 4, "y" to 4),
        )
        assertEquals("outside the paste", Color.BLACK, out.getPixel(0, 0))
        assertEquals("inside the paste", Color.WHITE, out.getPixel(5, 5))
        assertEquals("just before the paste", Color.BLACK, out.getPixel(3, 3))
    }

    /**
     * ⚠⚠ The semantics test. WHITE KEEPS THE OVERLAY. Backwards, every masked
     * composite in the app produces a plausible picture with the wrong half in
     * it, and nothing throws.
     */
    @Test
    fun aWhiteMaskKeepsTheOverlay() {
        val out = outImage(
            "image.composite",
            args(
                "base" to solid(4, 4, Color.BLACK),
                "overlay" to solid(4, 4, Color.WHITE),
                "mask" to solid(4, 4, Color.WHITE),
            ),
        )
        assertEquals(Color.WHITE, out.getPixel(2, 2))
    }

    @Test
    fun aBlackMaskKeepsTheBase() {
        val out = outImage(
            "image.composite",
            args(
                "base" to solid(4, 4, Color.RED),
                "overlay" to solid(4, 4, Color.BLUE),
                "mask" to solid(4, 4, Color.BLACK),
            ),
        )
        assertEquals(Color.RED, out.getPixel(2, 2))
    }

    /**
     * ⚠ A mask made of a fully OPAQUE grey must still be half-strength. This is
     * the case that fails if the implementation uses the mask's alpha channel
     * instead of its luminance — and every mask in this app is opaque, so that
     * bug would look exactly like "masks do nothing".
     */
    @Test
    fun aGreyMaskBlendsHalfWay() {
        val out = outImage(
            "image.composite",
            args(
                "base" to solid(4, 4, Color.BLACK),
                "overlay" to solid(4, 4, Color.WHITE),
                "mask" to solid(4, 4, Color.rgb(128, 128, 128)),
            ),
        )
        val p = out.getPixel(2, 2)
        assertTrue("expected a mid grey, got ${Integer.toHexString(p)}", Color.red(p) in 110..145)
    }

    @Test
    fun compositeRefusesAMaskOfTheWrongSize() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            call(
                "image.composite",
                args(
                    "base" to solid(8, 8, 0),
                    "overlay" to solid(4, 4, 0),
                    "mask" to solid(2, 2, 0),
                ),
            )
        }
        assertTrue(e.message!!, e.message!!.contains("mask is 2x2"))
    }

    /**
     * ⚠⚠ The store is content-addressed, so an op that mutated its input in
     * place would change the pixels behind an id other nodes have already
     * cached under their own keys — a wrong picture appearing in a node nobody
     * touched.
     */
    @Test
    fun compositeDoesNotMutateItsInputs() {
        val base = solid(4, 4, Color.BLACK)
        call(
            "image.composite",
            args("base" to base, "overlay" to solid(4, 4, Color.WHITE), "x" to 0, "y" to 0),
        )
        assertEquals(Color.BLACK, images.get(base)!!.getPixel(2, 2))
    }
}
