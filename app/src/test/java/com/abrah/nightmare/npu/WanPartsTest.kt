package com.abrah.nightmare.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ⭐ [WanParts]: each part's prompt, and the colour lock that stops a long clip drifting darker part by part. */
class WanPartsTest {

    @Test fun aBlankOrMissingLineRepeatsThePartBefore() {
        assertEquals(listOf("a", "b", "b", "c"), WanParts.promptsFor("a", "b\n\nc", 4))
        assertEquals(listOf("a", "a", "a"), WanParts.promptsFor("a", "", 3))
        assertEquals(listOf("a", "x"), WanParts.promptsFor("a", "  x  \ny\nz", 2))
        assertEquals(listOf("a"), WanParts.promptsFor("a", "b", 1))
    }

    @Test fun theCountIsOneToTen() {
        assertEquals(1, WanParts.countOf(emptyMap()))
        assertEquals(10, WanParts.countOf(mapOf(WanParts.PARTS to "40")))
        assertEquals(1, WanParts.countOf(mapOf(WanParts.PARTS to "0")))
    }

    private fun frame(r: Int, g: Int, b: Int, n: Int = 4096): ByteArray =
        ByteArray(n * 3) { i -> val v = when (i % 3) { 0 -> r; 1 -> g; else -> b } + ((i / 3) % 17 - 8) * 3; v.coerceIn(0, 255).toByte() }

    private fun mean(rgb: ByteArray) = WanParts.stats(rgb, 1).mean

    /** ⭐ The tester's drift, part by part (mean 71 → 50, red rising against green): held near the first frame's. */
    @Test fun aDarkeningRedderSequenceIsHeldToTheFirstFrame() {
        val first = frame(80, 70, 63)
        val lock = WanParts.Lock(WanParts.stats(first))
        lock.apply(first.copyOf())
        var last = first
        for (k in 1..40) {
            val drift = k / 40.0
            last = frame((80 - 23 * drift).toInt(), (70 - 30 * drift).toInt(), (63 - 26 * drift).toInt())
            lock.apply(last)
        }
        val m = mean(last)
        assertEquals(80f, m[0], 4f)
        assertEquals(70f, m[1], 4f)
        assertEquals(63f, m[2], 4f)
    }

    /**
     * ⚠⚠ A part that comes out brighter than the frame it started from (S25: 67 → 85 at the first join) must be held on
     * its FIRST frame: smoothed across the join it flashed and faded over 6 frames.
     */
    @Test fun aJumpAtAJoinIsHeldOnItsFirstFrame() {
        val first = frame(80, 70, 63)
        val lock = WanParts.Lock(WanParts.stats(first))
        repeat(20) { lock.apply(first.copyOf()) }
        val jumped = frame(100, 90, 80)
        lock.restart()
        lock.apply(jumped)
        assertEquals(70f, mean(jumped)[1], 3f)
    }

    /** ⚠ A frame meant to look very different is nudged, never repainted: the shift and gain are held. */
    @Test fun aBigChangeIsOnlyNudged() {
        val anchor = WanParts.stats(frame(200, 200, 200))
        val dark = frame(40, 40, 40)
        WanParts.match(dark, WanParts.stats(dark), anchor)
        val m = mean(dark)
        assertTrue("moved at most 40% of the anchor's mean: ${m[0]}", m[0] <= 40 + WanParts.MAX_SHIFT * 200 + 1)
        assertTrue("but towards it: ${m[0]}", m[0] > 45)
    }

    @Test fun theFirstFrameIsUnchanged() {
        val f = frame(90, 60, 30)
        val copy = f.copyOf()
        WanParts.Lock(WanParts.stats(f)).apply(copy)
        val a = mean(f); val b = mean(copy)
        for (c in 0 until 3) assertEquals(a[c], b[c], 0.5f)
    }

    /** ⚠ Motion transfer makes its own parts: the knobs stay off its page. */
    @Test fun motionTransferHasNoPartKnobs() {
        assertTrue(WanFunNode.widgets.none { it.name in WanParts.KNOBS })
        assertTrue(WanVideoNode.widgets.map { it.name }.containsAll(WanParts.KNOBS))
        assertTrue(WanA14Node.widgets.map { it.name }.containsAll(WanParts.KNOBS))
    }
}
