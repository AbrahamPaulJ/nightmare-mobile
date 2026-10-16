package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ⭐ A one-colour picture from the DiT engine is a failed render (8s Gen 4 testers, 2026-10-17:
 * solid grey, "done", no report). `Ops.uniformColour` is the test that turns it into an error.
 */
class BlankPictureTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun solidGreyIsBlank() {
        val grey = argb(128, 128, 128)
        assertEquals(grey, Ops.uniformColour(IntArray(128 * 128) { grey }))
    }

    @Test
    fun nearlyFlatIsStillBlank() {
        val px = IntArray(4096) { i -> argb(127 + i % 3, 128, 129 - i % 2) }
        assertEquals(px[0], Ops.uniformColour(px))
    }

    @Test
    fun aRealPictureIsNot() {
        // ⚠ One pixel three levels off is enough: a render is never this flat.
        val px = IntArray(4096) { argb(128, 128, 128) }
        px[4000] = argb(128, 131, 128)
        assertNull(Ops.uniformColour(px))
    }

    @Test
    fun emptyIsNotBlank() = assertNull(Ops.uniformColour(IntArray(0)))
}
