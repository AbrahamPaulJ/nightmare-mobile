package com.abrah.nightmare.detail

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.abrah.nightmare.ImageStore
import com.abrah.nightmare.InpaintPixels
import com.abrah.nightmare.MaskCropNode

/**
 * ⭐⭐⭐ **ADetailer on the image node** (Alex's ask for SDXL, 2026-10-17; the user's calls: Bingsu's
 * YOLOv8 face and hand models, as SWITCHES on the sampler, no new node). After the render, each face or
 * hand [PartDetector] finds is cut out with room around it, redrawn at the model's own size with this
 * node's prompt and model at [DETAIL_DENOISE], and blended back along a feathered mask.
 *
 * ⚠⚠ No second inpaint: the cut is [MaskCropNode.cut] ("Only masked", `InpaintCrop`'s margins) and the
 * paste [InpaintPixels.composite], the inpaint sampler's own two halves, and the redraw is the sampler's
 * masked render, handed in as [fix]'s `render`. A face fixed here is cut and pasted exactly as one masked
 * by hand would be.
 */
object Detailer {

    const val FIX_FACES = "fix_faces"
    const val FIX_HANDS = "fix_hands"

    /** ⭐ How much of each face or hand is redrawn: A1111 ADetailer's 0.4. */
    const val DETAIL_DENOISE = "detail_denoise"
    const val DEFAULT_DENOISE = "0.4"

    /** ⚠ A crowd is not a portrait: the largest few of each kind, so a run cannot take an hour. */
    const val MAX_PER_PART = 4

    /** ⚠ The box grows this much of its size each side, so the redraw meets hair, ears and wrists. */
    const val GROW = 0.12f

    /** ⚠ Smaller than this share of the picture's short side is a speck, or a face too far to help. */
    const val MIN_SIDE = 0.03f

    /** ⚠ Pure: which [boxes] to fix in a [w] x [h] picture — big enough, the largest [MAX_PER_PART]. */
    fun chosen(boxes: List<YoloDecode.Box>, w: Int, h: Int): List<YoloDecode.Box> {
        val min = MIN_SIDE * minOf(w, h)
        return boxes.filter { minOf(it.w, it.h) >= min }.sortedByDescending { it.w * it.h }.take(MAX_PER_PART)
    }

    /** ⚠ Pure: [b] grown by [GROW] each side and kept on the picture, in whole pixels (l, t, r, b). */
    fun grown(b: YoloDecode.Box, w: Int, h: Int): IntArray {
        val gx = b.w * GROW
        val gy = b.h * GROW
        return intArrayOf(
            (b.x1 - gx).toInt().coerceIn(0, w - 1), (b.y1 - gy).toInt().coerceIn(0, h - 1),
            (b.x2 + gx).toInt().coerceIn(1, w), (b.y2 + gy).toInt().coerceIn(1, h),
        )
    }

    /**
     * ⭐ Every chosen box, one after another, each on the picture the last one left. [render] is the
     * sampler's masked render of a cut (its picture and mask as PNGs, both [patchW] x [patchH]).
     */
    suspend fun fix(
        img: Bitmap,
        boxes: List<YoloDecode.Box>,
        patchW: Int,
        patchH: Int,
        /** ⭐ Match colours: each patch in its cut's colours before it is blended back. */
        match: Boolean,
        render: suspend (imagePng: ByteArray, maskPng: ByteArray) -> Bitmap,
    ): Bitmap {
        var cur = img
        for (b in boxes) {
            val r = grown(b, cur.width, cur.height)
            val mask = Bitmap.createBitmap(cur.width, cur.height, Bitmap.Config.ARGB_8888)
            Canvas(mask).apply {
                drawColor(Color.BLACK)
                // ⭐ An oval in the box, not the box: a face is round, and the corners of its box are
                // background that a redraw would only disturb.
                drawOval(RectF(r[0].toFloat(), r[1].toFloat(), r[2].toFloat(), r[3].toFloat()), Paint().apply { color = Color.WHITE; isAntiAlias = true })
            }
            val cut = MaskCropNode.cut(cur, mask, patchW, patchH, true)
            val drawn = render(ImageStore.encodePng(cut.image), ImageStore.encodePng(cut.mask))
            val patch = if (match) com.abrah.nightmare.ColorTools.matchBitmap(drawn, cut.image) else drawn
            val dst = RectF(
                cut.rect[0].toFloat(), cut.rect[1].toFloat(),
                (cut.rect[0] + cut.rect[2]).toFloat(), (cut.rect[1] + cut.rect[3]).toFloat(),
            )
            cur = InpaintPixels.composite(cur, patch, dst, cut.mask)
            mask.recycle()
        }
        return cur
    }
}
