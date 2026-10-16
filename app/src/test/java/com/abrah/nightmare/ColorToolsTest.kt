package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ⭐ Colour correction's maths ([ColorTools], the user's ask 2026-10-17). */
class ColorToolsTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun r(p: Int) = p ushr 16 and 0xFF
    private fun g(p: Int) = p ushr 8 and 0xFF
    private fun b(p: Int) = p and 0xFF

    /** A gradient from [a] to [b], so every channel has a spread. */
    private fun ramp(n: Int, a: IntArray, z: IntArray) = IntArray(n) { i ->
        argb(a[0] + (z[0] - a[0]) * i / (n - 1), a[1] + (z[1] - a[1]) * i / (n - 1), a[2] + (z[2] - a[2]) * i / (n - 1))
    }

    @Test
    fun aTintedPatchTakesTheReferencesColours() {
        val reference = ramp(256, intArrayOf(40, 60, 90), intArrayOf(200, 210, 230)) // cool
        val tinted = ramp(256, intArrayOf(90, 60, 30), intArrayOf(240, 200, 150)) // warm
        val out = ColorTools.matchColors(tinted, reference)
        fun meanR(px: IntArray) = px.map { r(it) }.average()
        fun meanB(px: IntArray) = px.map { b(it) }.average()
        assertEquals(meanR(reference), meanR(out), 6.0)
        assertEquals(meanB(reference), meanB(out), 6.0)
    }

    @Test
    fun matchingAPictureToItselfChangesNothing() {
        val px = ramp(256, intArrayOf(10, 80, 150), intArrayOf(250, 180, 90))
        val out = ColorTools.matchColors(px, px)
        for (i in px.indices) {
            assertEquals(r(px[i]).toDouble(), r(out[i]).toDouble(), 2.0)
            assertEquals(b(px[i]).toDouble(), b(out[i]).toDouble(), 2.0)
        }
    }

    @Test
    fun autoColourStretchesAFlatGreyishPicture() {
        val dull = ramp(1000, intArrayOf(90, 90, 100), intArrayOf(160, 160, 170))
        val out = ColorTools.autoColor(dull)
        assertTrue(out.minOf { g(it) } < 10)
        assertTrue(out.maxOf { g(it) } > 245)
    }

    @Test
    fun theSlidersAtZeroAreTheIdentityAndWarmthWarms() {
        val px = ramp(64, intArrayOf(20, 40, 60), intArrayOf(200, 180, 160))
        assertTrue(ColorTools.adjust(px, 0f, 0f, 0f, 0f) === px)
        val warm = ColorTools.adjust(px, 0f, 0f, 0f, 1f)
        assertTrue(warm.indices.all { r(warm[it]) >= r(px[it]) && b(warm[it]) <= b(px[it]) })
        val grey = ColorTools.adjust(intArrayOf(argb(200, 50, 50)), 0f, 0f, -1f, 0f)[0]
        assertEquals(r(grey), g(grey))
    }
}
