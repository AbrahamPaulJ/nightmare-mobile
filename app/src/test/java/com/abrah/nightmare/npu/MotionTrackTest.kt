package com.abrah.nightmare.npu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐ Motion transfer's long-video arithmetic (the user's calls, 2026-10-27): the parts a long clip is made of,
 * the crop that follows the person, the picture lined up with the dancer, and the span Keep audio copies.
 */
class MotionTrackTest {

    /**
     * ⭐ Parts overlap by a start frame and [WanFun.OVERLAP] shared frames, are as few as cover the clip, of even
     * length, end exactly on its last frame, and every one is 4n+1.
     */
    @Test
    fun aLongClipIsOverlappingParts() {
        assertEquals(listOf(0 to 121), WanFun.partsOf(121))
        assertEquals(listOf(0 to 49), WanFun.partsOf(49))
        // 10 s at 24 fps: three even parts, each starting 9 frames before the last one ends.
        assertEquals(listOf(0 to 89, 80 to 89, 160 to 81), WanFun.partsOf(241))
        assertEquals(7, WanFun.partsOf(721).size)
        assertEquals(listOf(0 to 69, 60 to 65), WanFun.partsOf(125))
        // ⚠ No overlap: parts share one frame, as the first build did.
        assertEquals(listOf(0 to 121, 120 to 121), WanFun.partsOf(241, overlap = 0))
        for (part in listOf(121, 49, 81)) for (f in 5..721 step 4) {
            val ps = WanFun.partsOf(f, part)
            assertEquals(f, ps.last().first + ps.last().second)
            for ((_, n) in ps) assertTrue(n <= part && (n - 1) % 4 == 0)
            for (i in 1 until ps.size) assertEquals(ps[i - 1].first + ps[i - 1].second - 1 - WanFun.OVERLAP, ps[i].first)
        }
    }

    /** ⭐ A killed run resumes after its last finished part; a finished or unreadable marker starts over. */
    @Test
    fun aKilledRunResumesAfterItsLastPart() {
        assertEquals(2 to 169, WanFun.resumeFrom("2 169", 3))
        assertEquals(1 to 89, WanFun.resumeFrom("1 89\n", 3))
        assertNull(WanFun.resumeFrom("3 241", 3))
        assertNull(WanFun.resumeFrom(null, 3))
        assertNull(WanFun.resumeFrom("garbage", 3))
        assertNull(WanFun.resumeFrom("0 0", 3))
    }

    /** ⭐ A join's cost: how unlike two frames are (the new part takes over where it is lowest). */
    @Test
    fun aJoinCostsTheFramesDifference() {
        assertEquals(0.0, WanVideoNode.seamDiff(ByteArray(21) { 7 }, ByteArray(21) { 7 }), 1e-9)
        assertEquals(10.0, WanVideoNode.seamDiff(ByteArray(21) { 0 }, ByteArray(21) { 10 }), 1e-9)
        assertEquals(245.0, WanVideoNode.seamDiff(ByteArray(8) { 0 }, ByteArray(8) { 245.toByte() }), 1e-9)
    }

    /** ⭐ One pass holds 480x832 x 121 worth of tokens: 704x1280 gets 49 frames (what ran), smaller sizes the full 121. */
    @Test
    fun aPartIsAsLongAsOnePassHolds() {
        assertEquals(121, WanFun.partFramesFor(480, 832))
        assertEquals(121, WanFun.partFramesFor(832, 480))
        assertEquals(49, WanFun.partFramesFor(704, 1280))
        assertEquals(121, WanFun.partFramesFor(320, 320))
        assertEquals(listOf(0 to 49, 40 to 49, 80 to 41), WanFun.partsOf(121, WanFun.partFramesFor(704, 1280)))
    }

    /** ⭐ The follow crop: a fixed size from the person's tall frames, its centre a smoothed path, gaps filled. */
    @Test
    fun theCropFollowsThePerson() {
        val box = { x: Float -> floatArrayOf(x, 100f, x + 100f, 500f) } // 400 px tall
        val boxes = listOf(box(0f), null, box(20f), box(40f))
        val w = MotionTrack.followWindows(boxes, 480, 832, margin = 1.15f, smooth = 1)!!
        assertEquals(4, w.size)
        // 400 x 1.15 tall, at the clip's shape.
        assertEquals(460f, w[0].h, 1e-3f)
        assertEquals(460f * 480 / 832, w[0].w, 1e-3f)
        // Centred on the person; the missing frame holds the one before.
        assertEquals(50f, w[0].x + w[0].w / 2, 1e-3f)
        assertEquals(50f, w[1].x + w[1].w / 2, 1e-3f)
        assertEquals(90f, w[3].x + w[3].w / 2, 1e-3f)
        assertEquals(300f, w[0].y + w[0].h / 2, 1e-3f)
        // Smoothed: a jump is spread over the frames around it.
        val s = MotionTrack.followWindows(listOf(box(0f), box(0f), box(300f), box(300f)), 480, 832, smooth = 3)!!
        assertTrue(s[1].x + s[1].w / 2 in 50f..350f && s[1].x + s[1].w / 2 > 50f)
        // Nobody anywhere: no follow.
        assertNull(MotionTrack.followWindows(listOf(null, null), 480, 832))
    }

    @Test
    fun pointsAndBoxesMoveIntoTheCrop() {
        val win = MotionTrack.Window(100f, 50f, 240f, 416f) // half the clip's 480x832
        assertArrayEquals(floatArrayOf(0f, 0f, 0.9f, 480f, 832f, 0.2f), MotionTrack.inWindow(floatArrayOf(100f, 50f, 0.9f, 340f, 466f, 0.2f), win, 480, 832), 1e-3f)
        assertArrayEquals(floatArrayOf(20f, 100f, 220f, 900f), MotionTrack.boxInWindow(floatArrayOf(110f, 100f, 210f, 500f), win, 480, 832), 1e-3f)
    }

    /**
     * ⭐ Line up my picture: the picture's person comes out the dancer's height, centred on where the dancer starts,
     * feet on the dancer's feet; a picture too tight for that is used whole rather than padded.
     */
    @Test
    fun thePicturesPersonStandsWhereTheDancerStarts() {
        // A 1000x2000 picture, its person 200..400 x 500..1500 (1000 px tall).
        val photoBox = floatArrayOf(200f, 500f, 400f, 1500f)
        // The dancer starts 100..200 x 300..800 in a 480x832 clip, usually 500 px tall.
        val r = MotionTrack.alignRect(1000, 2000, photoBox, floatArrayOf(100f, 300f, 200f, 800f), 500f, 480, 832)
        // 0.5 clip px per picture px: a 960x1664 crop, the person's centre x (300) at the clip's 150, feet (1500) at 800.
        assertArrayEquals(floatArrayOf((300f - 300f) / 1000, (1500f - 1600f).coerceAtLeast(0f) / 2000, 960f / 1000, 1664f / 2000), r, 1e-4f)
        // The crop maps the person to the dancer's height.
        assertEquals(500f, (photoBox[3] - photoBox[1]) * 480 / (r[2] * 1000), 1e-2f)
        // Too tight: the crop is the most of the picture the clip's shape allows, inside it.
        val tight = MotionTrack.alignRect(400, 600, floatArrayOf(50f, 0f, 350f, 600f), floatArrayOf(200f, 400f, 280f, 800f), 300f, 480, 832)
        assertTrue(tight[0] >= 0f && tight[1] >= 0f && tight[0] + tight[2] <= 1.0001f && tight[1] + tight[3] <= 1.0001f)
    }

    @Test
    fun theHelpersHoldTheirEdges() {
        assertEquals(0f, MotionTrack.quantile(emptyList(), 0.95f))
        assertEquals(8f, MotionTrack.quantile((0..9).map { it.toFloat() }, 0.95f))
        assertNull(MotionTrack.filled(listOf(null, null)))
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), MotionTrack.smoothed(floatArrayOf(1f, 1f, 1f), 5), 1e-6f)
    }

    /** ⭐ Keep audio copies the sound that starts inside the clip's span. */
    @Test
    fun keepAudioCopiesTheClipsSpan() {
        assertTrue(AudioMux.inSpan(2_000_000, 2_000_000, 5_000_000))
        assertTrue(AudioMux.inSpan(6_999_999, 2_000_000, 5_000_000))
        assertFalse(AudioMux.inSpan(7_000_000, 2_000_000, 5_000_000))
        assertFalse(AudioMux.inSpan(1_999_999, 2_000_000, 5_000_000))
    }

    /** ⭐ The hand lines' colours: matplotlib's hsv_to_rgb at full saturation and value, truncated. */
    @Test
    fun handColoursFollowTheHueWheel() {
        assertEquals(0xFFFF0000.toInt(), com.abrah.nightmare.pose.OpenPoseRender.hue(0f))
        assertEquals(0xFF00FF00.toInt(), com.abrah.nightmare.pose.OpenPoseRender.hue(1f / 3))
        assertEquals(0xFFFF4C00.toInt(), com.abrah.nightmare.pose.OpenPoseRender.hue(1f / 20))
    }
}
