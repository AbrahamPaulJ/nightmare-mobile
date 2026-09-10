package com.abrah.nightmare

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The store that makes the cache work.
 *
 * ⚠⚠ These are not storage tests. Content addressing is the load-bearing
 * property of the whole executor: an id that changes when the picture did not
 * turns the cache off, and — far worse — an id that stays the same when the
 * picture DID change serves stale pixels to everything downstream. Both
 * failures are silent, which is why they get direct tests rather than being
 * covered incidentally by the op tests.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageStoreTest {

    private fun solid(w: Int, h: Int, color: Int = Color.WHITE): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    // ------------------------------------------------------------ addressing

    @Test
    fun samePixelsGiveTheSameId() {
        val s = ImageStore()
        assertEquals(s.put(solid(4, 4, Color.RED)), s.put(solid(4, 4, Color.RED)))
    }

    @Test
    fun differentPixelsGiveDifferentIds() {
        val s = ImageStore()
        assertNotEquals(s.put(solid(4, 4, Color.RED)), s.put(solid(4, 4, Color.BLUE)))
    }

    /**
     * ⚠⚠ The regression this file exists for.
     *
     * A 2×8 and an 8×2 solid bitmap hold byte-identical pixel data — same
     * `byteCount`, same values — so a digest over the pixel buffer alone maps
     * them to one id. Solid images are not a contrived case: a mask IS a solid
     * image, and `image.new` makes them at whatever size the graph asks for.
     * The collision would hand a node the wrong-shaped bitmap with no error
     * anywhere.
     */
    @Test
    fun shapeIsPartOfTheAddress() {
        val s = ImageStore()
        assertNotEquals(
            "a 2x8 and an 8x2 image have identical pixel bytes and must still differ",
            s.put(solid(2, 8)), s.put(solid(8, 2)),
        )
        // …and both survive, which is the point: one id would have evicted one.
        assertEquals(2, s.size)
    }

    /** The control for the test above: shape aside, the digest still sees pixels. */
    @Test
    fun sameShapeDifferentPixelsStillDiffer() {
        val s = ImageStore()
        assertNotEquals(s.put(solid(2, 8, Color.RED)), s.put(solid(2, 8, Color.GREEN)))
    }

    /**
     * ⚠ The id must not depend on the compressor. Two encoders — or two
     * releases of Android's own — can emit different bytes for one picture.
     */
    @Test
    fun theSuppliedBytesDoNotChangeTheId() {
        val s = ImageStore()
        val bare = s.put(solid(4, 4, Color.RED))
        val withBytes = s.put(solid(4, 4, Color.RED), png = byteArrayOf(1, 2, 3))
        assertEquals(bare, withBytes)
    }

    /** The backend already hashed the pixels; passing its id skips a re-hash. */
    @Test
    fun anExplicitIdIsUsedVerbatim() {
        val s = ImageStore()
        assertEquals("rgb_deadbeef", s.put(solid(4, 4), id = "rgb_deadbeef"))
        assertTrue("rgb_deadbeef" in s)
    }

    // ------------------------------------------------------------------ png

    @Test
    fun pngIsEncodedOnceAndRemembered() {
        val s = ImageStore()
        val id = s.put(solid(4, 4, Color.RED))
        assertSame("the second ask must not re-encode", s.png(id), s.png(id))
    }

    @Test
    fun pngHandsBackTheBytesItWasGiven() {
        val s = ImageStore()
        val bytes = byteArrayOf(9, 8, 7)
        assertSame(bytes, s.png(s.put(solid(4, 4), png = bytes)))
    }

    @Test
    fun anAbsentIdIsNullEverywhere() {
        val s = ImageStore()
        assertNull(s.get("img_nope"))
        assertNull(s.png("img_nope"))
        assertFalse("img_nope" in s)
    }

    // ------------------------------------------------------------------ LRU

    @Test
    fun theStoreIsBounded() {
        val s = ImageStore(limit = 3)
        val ids = (1..5).map { s.put(solid(2, 2, Color.rgb(it, 0, 0))) }
        assertEquals(3, s.size)
        assertFalse("the oldest should be gone", ids[0] in s)
        assertTrue(ids[4] in s)
    }

    /**
     * ⚠ Access-ordered, not insertion-ordered — the `true` in the
     * LinkedHashMap constructor. A graph re-reads its source image on every
     * pass, so an insertion-ordered store would evict exactly the image being
     * used most.
     */
    @Test
    fun readingAnEntryKeepsItAlive() {
        val s = ImageStore(limit = 3)
        val ids = (1..3).map { s.put(solid(2, 2, Color.rgb(it, 0, 0))) }
        s.get(ids[0])                                  // touch the oldest
        s.put(solid(2, 2, Color.rgb(9, 0, 0)))         // force one eviction

        assertTrue("the touched entry must survive", ids[0] in s)
        assertFalse("the untouched one goes instead", ids[1] in s)
    }

    @Test
    fun clearEmptiesIt() {
        val s = ImageStore()
        s.put(solid(2, 2))
        s.clear()
        assertEquals(0, s.size)
    }
}
