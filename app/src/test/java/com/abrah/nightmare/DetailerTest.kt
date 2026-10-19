package com.abrah.nightmare

import com.abrah.nightmare.detail.Detailer
import com.abrah.nightmare.detail.YoloDecode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐ Fix faces / Fix hands' pure parts: the YOLOv8 head decode (validated against Ultralytics on the PC,
 * 2026-10-17) and which boxes the pass redraws.
 */
class DetailerTest {

    /** Channel-major `[5, n]`: cx…, cy…, w…, h…, score…. */
    private fun head(vararg rows: FloatArray): FloatArray {
        val n = rows.size
        return FloatArray(5 * n) { i -> rows[i % n][i / n] }
    }

    @Test
    fun overlappingBoxesCollapseToTheMostConfident() {
        val out = head(
            floatArrayOf(100f, 100f, 40f, 40f, 0.80f),
            floatArrayOf(102f, 101f, 40f, 42f, 0.90f), // the same face, more sure
            floatArrayOf(400f, 300f, 60f, 60f, 0.50f),
            floatArrayOf(500f, 500f, 50f, 50f, 0.10f), // under the threshold
        )
        val b = YoloDecode.boxes(out, 4, 0.3f, 0.45f)
        assertEquals(listOf(0.90f, 0.50f), b.map { it.score })
        assertEquals(82f, b[0].x1, 1e-4f)
        assertEquals(80f, b[0].y1, 1e-4f)
    }

    /** ⚠ The letterbox is undone exactly: a 1280x720 picture sits at y 140..500 of the 640 square. */
    @Test
    fun theLetterboxMapsBackToThePicture() {
        val lb = YoloDecode.Letterbox.of(1280, 720)
        assertEquals(0.5f, lb.scale, 1e-6f)
        assertEquals(140f, lb.dy, 1e-6f)
        val b = lb.toSource(YoloDecode.Box(100f, 160f, 200f, 260f, 1f))
        assertEquals(200f, b.x1, 1e-4f)
        assertEquals(40f, b.y1, 1e-4f)
        assertEquals(400f, b.x2, 1e-4f)
        assertEquals(240f, b.y2, 1e-4f)
    }

    @Test
    fun specksAreSkippedAndTheLargestFewKept() {
        val boxes = (1..6).map { i -> YoloDecode.Box(0f, 0f, 20f * i + 40f, 20f * i + 40f, 0.9f) } +
            YoloDecode.Box(0f, 0f, 10f, 10f, 0.99f)
        val chosen = Detailer.chosen(boxes, 1024, 1024)
        assertEquals(Detailer.MAX_PER_PART, chosen.size)
        assertEquals(160f, chosen.first().w, 1e-4f)
        assertEquals(true, chosen.none { it.w < 30f })
    }

    /**
     * ⭐ A tester (2026-10-19): fixed faces came back BLURRIER. The redraw was the whole 512 frame (an inpaint never
     * crops below the canvas), so it gained nothing. Now a 75 px face is redrawn zoomed in; a close-up is left alone.
     */
    @Test
    fun aSmallFaceIsRedrawnZoomedInAndACloseUpIsLeftAlone() {
        val r = Detailer.zoomRect(intArrayOf(331, 66, 408, 169), 512, 512, 512, 512)!!
        // 103 px tall plus 25% context each side: about 155 px square, a 3.3x zoom, centred and on the picture.
        assertEquals(155f, r[3].toFloat(), 2f)
        assertEquals(r[2], r[3])
        assertTrue(r[0] >= 0 && r[1] >= 0 && r[0] + r[2] <= 512 && r[1] + r[3] <= 512)
        assertEquals(null, Detailer.zoomRect(intArrayOf(165, 25, 436, 448), 512, 512, 512, 512))
        // ⚠ The canvas's aspect, so the patch is not stretched: a portrait SDXL canvas gets a portrait cut.
        val p = Detailer.zoomRect(intArrayOf(500, 300, 600, 420), 832, 1216, 832, 1216)!!
        assertEquals(832f / 1216f, p[2].toFloat() / p[3], 0.01f)
    }

    @Test
    fun aGrownBoxStaysOnThePicture() {
        assertArrayEquals(intArrayOf(88, 88, 212, 212), Detailer.grown(YoloDecode.Box(100f, 100f, 200f, 200f, 1f), 1024, 1024))
        assertArrayEquals(intArrayOf(0, 0, 512, 512), Detailer.grown(YoloDecode.Box(0f, 0f, 512f, 512f, 1f), 512, 512))
    }

    /**
     * ⭐ A tester's hand fix (2026-10-17) in miniature: a flat wall, an oval mask, a redraw 8 levels brighter
     * with a "hand" (dark, changed on purpose) crossing the mask's edge. The shift takes the wall back; the
     * hand's own change does not move the median.
     */
    private fun oval(w: Int, h: Int) = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val dx = (x - w / 2f) / (w * 0.4f); val dy = (y - h / 2f) / (h * 0.4f)
        if (dx * dx + dy * dy <= 1f) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    }

    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test
    fun aBrighterRedrawIsTakenBackAlongTheEdge() {
        val w = 128; val h = 128
        val mask = oval(w, h)
        val orig = IntArray(w * h) { grey(220) }
        // The "hand": a dark bar from the centre out through the right edge of the oval.
        val patch = IntArray(w * h) { i -> if (i / w in 60..68 && i % w > 64) grey(90) else grey(228) }
        val shift = Detailer.edgeShift(orig, patch, mask, w, h)!!
        assertArrayEquals(intArrayOf(-8, -8, -8), shift)
        val fixed = Detailer.shifted(patch, shift)
        assertEquals(grey(220), fixed[10 * w + 64]) // the wall, back to the original
        assertEquals(grey(82), fixed[64 * w + 100]) // the hand keeps its own change, moved with the patch
    }

    @Test
    fun aRedrawThatChangedTheEdgeItselfIsLeftAlone() {
        val w = 128; val h = 128
        // Stripes all round the edge: the redraw changed what is there, not its tone.
        val patch = IntArray(w * h) { i -> if ((i % w) / 4 % 2 == 0) grey(60) else grey(240) }
        assertEquals(null, Detailer.edgeShift(IntArray(w * h) { grey(150) }, patch, oval(w, h), w, h))
        // And a redraw already in tone has nothing to take back.
        assertEquals(null, Detailer.edgeShift(IntArray(w * h) { grey(150) }, IntArray(w * h) { grey(150) }, oval(w, h), w, h))
    }

    @Test
    fun detectionSettingsDefaultToWhatEveryRenderUsedAndStayInRange() {
        val face = com.abrah.nightmare.detail.PartDetector.Part.FACE
        val hand = com.abrah.nightmare.detail.PartDetector.Part.HAND
        // ⚠ No params = the old fixed values: a flow saved before 1.6.185 renders the same.
        assertEquals(Detailer.Settings(0.3f, Detailer.GROW, 1f / 24), Detailer.settings(emptyMap(), face))
        val s = Detailer.settings(mapOf("face_conf" to "0.6", "face_pad" to "9", "face_blur" to "x", "hand_conf" to "0.1"), face)
        assertEquals(0.6f, s.conf)
        assertEquals(Detailer.PAD_RANGE.endInclusive, s.pad)
        assertEquals(Detailer.DEFAULT_BLUR, s.blur)
        // Faces and hands are set apart.
        assertEquals(0.1f, Detailer.settings(mapOf("hand_conf" to "0.1"), hand).conf)
        assertEquals(0.3f, Detailer.settings(mapOf("hand_conf" to "0.1"), face).conf)
    }

    /** ⭐ GitHub #46: a part's own strength wins; absent or blank, it follows the node's Fix strength, then 0.4. */
    @Test
    fun eachPartHasItsOwnStrengthFollowingFixStrength() {
        val face = com.abrah.nightmare.detail.PartDetector.Part.FACE
        val hand = com.abrah.nightmare.detail.PartDetector.Part.HAND
        assertEquals(0.4f, Detailer.denoiseOf(emptyMap(), face))
        val node = mapOf(Detailer.DETAIL_DENOISE to "0.55", "hand_denoise" to "0.7", "face_denoise" to "")
        assertEquals(0.55f, Detailer.denoiseOf(node, face))
        assertEquals(0.7f, Detailer.denoiseOf(node, hand))
        assertEquals(1f, Detailer.denoiseOf(mapOf("face_denoise" to "3"), face))
    }

    @Test
    fun paddingGrowsTheBoxByItsShare() {
        val b = YoloDecode.Box(100f, 100f, 200f, 200f, 0.9f)
        assertArrayEquals(intArrayOf(100, 100, 200, 200), Detailer.grown(b, 1000, 1000, 0f))
        assertArrayEquals(intArrayOf(75, 75, 225, 225), Detailer.grown(b, 1000, 1000, 0.25f))
    }
}
