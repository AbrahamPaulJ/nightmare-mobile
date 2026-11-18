package com.abrah.nightmare

import com.abrah.nightmare.canvas.LayoutSnap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ⭐ GitHub #132: a dragged box's edges and centre snap to the picture's and to the other boxes'. */
class MossLayoutSnapTest {

    private val other = MossLayout.Box(x1 = 0.1, y1 = 0.1, x2 = 0.3, y2 = 0.4)

    @Test
    fun theLinesAreThePicturesAndTheOtherBoxes() {
        assertEquals(listOf(0.0, 0.5, 1.0, 0.1, 0.2, 0.3), LayoutSnap.lines(listOf(other, other), skip = 1, x = true))
        assertEquals(listOf(0.0, 0.5, 1.0), LayoutSnap.lines(listOf(other), skip = 0, x = false))
    }

    @Test
    fun theNearestEdgeWithinToleranceSnaps() {
        // A box whose left edge sits at 0.304 snaps 0.004 left onto the other box's right edge.
        val (shift, line) = LayoutSnap.snap(listOf(0.304, 0.409, 0.514), listOf(0.0, 0.5, 1.0, 0.3), tol = 0.01)
        assertEquals(-0.004, shift, 1e-9)
        assertEquals(0.3, line!!, 1e-9)
        // Nothing near: no shift.
        val (none, at) = LayoutSnap.snap(listOf(0.42), listOf(0.0, 0.5, 1.0), tol = 0.01)
        assertEquals(0.0, none, 0.0)
        assertNull(at)
    }
}
