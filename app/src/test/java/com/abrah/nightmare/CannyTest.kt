package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ⭐ The canny hint: edges on a step, one pixel thin, nothing on flat areas. */
class CannyTest {

    private fun square(w: Int, lo: Int, hi: Int) = FloatArray(w * w) { i ->
        val x = i % w; val y = i / w
        if (x in lo until hi && y in lo until hi) 255f else 0f
    }

    @Test
    fun aSquareGivesItsOutlineAndNothingElse() {
        val w = 64
        val e = Canny.edges(square(w, 20, 44), w, w)
        fun on(x: Int, y: Int) = e[y * w + x].toInt() != 0
        // Flat inside and outside: no edges.
        for (y in 26 until 38) for (x in 26 until 38) assertTrue("inside $x,$y", !on(x, y))
        for (y in 0 until 12) for (x in 0 until w) assertTrue("outside $x,$y", !on(x, y))
        // Every side of the square is found along its middle.
        for (t in 24 until 40) {
            assertTrue("left $t", on(19, t) || on(20, t))
            assertTrue("right $t", on(43, t) || on(44, t))
            assertTrue("top $t", on(t, 19) || on(t, 20))
            assertTrue("bottom $t", on(t, 43) || on(t, 44))
        }
        // Thin: non-maximum suppression leaves one pixel across each side.
        for (t in 24 until 40) assertEquals("left row $t", 1, (16 until 24).count { on(it, t) })
    }

    @Test
    fun aFaintStepIsBelowTheLowThreshold() {
        val w = 32
        val g = FloatArray(w * w) { i -> if (i % w < 16) 100f else 110f }   // |gx| = 40 < 100
        assertTrue(Canny.edges(g, w, w).all { it.toInt() == 0 })
    }

    @Test
    fun grayIsBt601() {
        val g = Canny.gray(intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()))
        assertEquals(0.299f * 255, g[0], 1e-3f)
        assertEquals(0.587f * 255, g[1], 1e-3f)
        assertEquals(0.114f * 255, g[2], 1e-3f)
    }
}
