package com.abrah.nightmare

import com.abrah.nightmare.detail.Detailer
import com.abrah.nightmare.detail.YoloDecode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

    @Test
    fun aGrownBoxStaysOnThePicture() {
        assertArrayEquals(intArrayOf(88, 88, 212, 212), Detailer.grown(YoloDecode.Box(100f, 100f, 200f, 200f, 1f), 1024, 1024))
        assertArrayEquals(intArrayOf(0, 0, 512, 512), Detailer.grown(YoloDecode.Box(0f, 0f, 512f, 512f, 1f), 512, 512))
    }
}
