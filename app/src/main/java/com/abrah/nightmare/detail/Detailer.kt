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

    /**
     * ⭐⭐ Per part, the three settings of the Detection sheet (GitHub #46, 1.6.185): how sure the detector must be,
     * how far the redraw reaches past the box, and how soft its pasted edge is. Plain node params with no knob of
     * their own, so a flow saves them; absent = A1111 ADetailer's defaults, what every render used before.
     */
    data class Settings(
        val conf: Float = PartDetector.CONF,
        val pad: Float = GROW,
        val blur: Float = DEFAULT_BLUR,
        /**
         * ⭐ This part's own strength, or null to follow the node's [DETAIL_DENOISE] ([denoiseOf]). ADetailer's
         * per-unit "inpaint denoising strength" (GitHub #46, 1.6.192): a face and a hand rarely want the same.
         */
        val denoise: Float? = null,
    )

    /** ⭐ The paste's feather until 1.6.185, 1/24 of the patch's short side (`InpaintPixels.FEATHER_DIVISOR`). */
    const val DEFAULT_BLUR = 1f / 24

    /** ⚠ Diagnostic: `detail_run` sets it to save each cut, mask and redraw (GitHub #46's displaced face). */
    @Volatile
    internal var debugSink: ((name: String, bmp: Bitmap) -> Unit)? = null

    val CONF_RANGE = 0.05f..0.95f
    val PAD_RANGE = 0f..0.5f
    val BLUR_RANGE = 0f..0.15f

    /** The param names of [part]'s [Settings]: confidence, padding, blur. */
    fun keys(part: PartDetector.Part): Triple<String, String, String> {
        val p = if (part == PartDetector.Part.FACE) "face" else "hand"
        return Triple("${p}_conf", "${p}_pad", "${p}_blur")
    }

    /** The param name of [part]'s own strength ([Settings.denoise]); absent or blank = the node's Fix strength. */
    fun denoiseKey(part: PartDetector.Part): String =
        (if (part == PartDetector.Part.FACE) "face" else "hand") + "_denoise"

    /** ⚠ Pure: [part]'s settings out of a node's [params], each kept in its range. */
    fun settings(params: Map<String, String>, part: PartDetector.Part): Settings {
        val (c, p, b) = keys(part)
        val d = Settings()
        fun f(k: String, def: Float, range: ClosedFloatingPointRange<Float>) =
            (params[k]?.toFloatOrNull() ?: def).coerceIn(range.start, range.endInclusive)
        return Settings(
            f(c, d.conf, CONF_RANGE), f(p, d.pad, PAD_RANGE), f(b, d.blur, BLUR_RANGE),
            params[denoiseKey(part)]?.toFloatOrNull()?.coerceIn(0f, 1f),
        )
    }

    /** ⚠ Pure: the strength [part] is redrawn at, its own ([Settings.denoise]) or else the node's Fix strength. */
    fun denoiseOf(params: Map<String, String>, part: PartDetector.Part): Float =
        settings(params, part).denoise
            ?: (params[DETAIL_DENOISE]?.toFloatOrNull() ?: DEFAULT_DENOISE.toFloat()).coerceIn(0f, 1f)

    /** ⚠ Smaller than this share of the picture's short side is a speck, or a face too far to help. */
    const val MIN_SIDE = 0.03f

    /** ⭐ The band inside the mask's edge the tone is read on, as a share of the patch's short side. */
    const val EDGE_BAND = 0.06f

    /** ⚠ Levels a band pixel may sit from the median and still agree with it. */
    const val EDGE_MAX_SPREAD = 12

    /** ⚠ The share of the band that must agree, or it was redrawn, not re-toned. */
    const val EDGE_AGREE = 0.6f

    /** ⚠ The largest shift taken back, levels per channel. */
    const val EDGE_MAX_SHIFT = 32

    /**
     * ⭐⭐ ⚠ Pure: the per-channel shift (R, G, B levels) that takes [patch] back to [orig]'s tone, read on the band
     * just inside the mask's edge; null when there is nothing to take back or the band disagrees with itself.
     *
     * ⚠⚠ Why (a tester's hand fix, 2026-10-17): the redraw came back about 8 levels brighter over the flat wall
     * inside the oval, ramping to nothing across the feather, so the fixed hand sat in a pale disc. Match colours
     * did not take it out (229.5 to 228.4): it matches the WHOLE cut's colours, and the cut is mostly the hand that
     * was meant to change. The edge band is mostly background the redraw should have left alone, and the MEDIAN of
     * it ignores the fingers that cross it.
     */
    fun edgeShift(orig: IntArray, patch: IntArray, mask: IntArray, w: Int, h: Int): IntArray? {
        // Distance of every masked pixel to the nearest unmasked one (thirds of a pixel).
        val far = Int.MAX_VALUE / 4
        val dist = IntArray(mask.size) { if ((mask[it] and 0xFF) >= 128) far else 0 }
        com.abrah.nightmare.MaskRaster.chamfer(dist, w, h)
        val band = maxOf(2, (minOf(w, h) * EDGE_BAND).toInt()) * com.abrah.nightmare.MaskRaster.CH_STRAIGHT
        val diffs = Array(3) { ArrayList<Int>() }
        for (i in mask.indices) {
            if ((mask[i] and 0xFF) < 128 || dist[i] > band || dist[i] >= far) continue
            for (c in 0 until 3) {
                val sh = 16 - 8 * c
                diffs[c].add(((orig[i] shr sh) and 0xFF) - ((patch[i] shr sh) and 0xFF))
            }
        }
        if (diffs[0].size < 32) return null
        val shift = IntArray(3)
        for (c in 0 until 3) {
            val d = diffs[c].sorted()
            val med = d[d.size / 2]
            // ⚠ Most of the band, not half: a median of absolute deviations passes a band that is half one
            // thing and half another, which is a redraw that changed the edge, not its tone.
            val agree = d.count { kotlin.math.abs(it - med) <= EDGE_MAX_SPREAD }
            if (agree < d.size * EDGE_AGREE) return null
            shift[c] = med.coerceIn(-EDGE_MAX_SHIFT, EDGE_MAX_SHIFT)
        }
        return shift.takeIf { s -> s.any { it != 0 } }
    }

    /** ⚠ Pure: [px] moved by [shift] (R, G, B levels), alpha kept. */
    fun shifted(px: IntArray, shift: IntArray): IntArray = IntArray(px.size) { i ->
        val p = px[i]
        val r = (((p shr 16) and 0xFF) + shift[0]).coerceIn(0, 255)
        val g = (((p shr 8) and 0xFF) + shift[1]).coerceIn(0, 255)
        val b = ((p and 0xFF) + shift[2]).coerceIn(0, 255)
        (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }

    /** ⭐ [drawn] in [cut]'s tone along the mask's edge ([edgeShift]); [drawn] itself when there is nothing to move. */
    private fun toned(drawn: Bitmap, cut: Bitmap, mask: Bitmap): Bitmap {
        val w = drawn.width
        val h = drawn.height
        fun px(b: Bitmap): IntArray {
            val s = if (b.width == w && b.height == h) b else Bitmap.createScaledBitmap(b, w, h, true)
            return IntArray(w * h).also { s.getPixels(it, 0, w, 0, 0, w, h) }
        }
        val patch = px(drawn)
        val shift = edgeShift(px(cut), patch, px(mask), w, h) ?: return drawn
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(shifted(patch, shift), 0, w, 0, 0, w, h) }
    }

    /** ⚠ Pure: which [boxes] to fix in a [w] x [h] picture — big enough, the largest [MAX_PER_PART]. */
    fun chosen(boxes: List<YoloDecode.Box>, w: Int, h: Int): List<YoloDecode.Box> {
        val min = MIN_SIDE * minOf(w, h)
        return boxes.filter { minOf(it.w, it.h) >= min }.sortedByDescending { it.w * it.h }.take(MAX_PER_PART)
    }

    /** ⚠ Pure: [b] grown by [pad] (of its size) each side and kept on the picture, in whole pixels (l, t, r, b). */
    fun grown(b: YoloDecode.Box, w: Int, h: Int, pad: Float = GROW): IntArray {
        val gx = b.w * pad
        val gy = b.h * pad
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
        /** ⭐ Padding and Mask blur ([Settings]); confidence was spent choosing [boxes]. */
        settings: Settings = Settings(),
        /** ⭐ Told how many parts were left as they were ([zoomRect] returned null). */
        onSkipped: (Int) -> Unit = {},
    ): Bitmap {
        var cur = img
        var skipped = 0
        for (b in boxes) {
            val r = grown(b, cur.width, cur.height, settings.pad)
            val mask = Bitmap.createBitmap(cur.width, cur.height, Bitmap.Config.ARGB_8888)
            Canvas(mask).apply {
                drawColor(Color.BLACK)
                // ⭐ An oval in the box, not the box: a face is round, and the corners of its box are
                // background that a redraw would only disturb.
                drawOval(RectF(r[0].toFloat(), r[1].toFloat(), r[2].toFloat(), r[3].toFloat()), Paint().apply { color = Color.WHITE; isAntiAlias = true })
            }
            // ⭐⭐ ZOOMED IN: the part with context, enlarged into the model (A1111 ADetailer's "only masked"). ⚠ Not
            // [MaskCropNode.cut]: an inpaint never crops below the model's canvas, so on a picture rendered at the
            // model's own size every face was redrawn WHOLE-FRAME at 0.4 and came back softer (a tester, 2026-10-19;
            // measured: sharpness 1877 -> 1395 on a 75 px face, SD 1.5 Swap at 512).
            val rect = zoomRect(r, cur.width, cur.height, patchW, patchH)
            if (rect == null) {
                skipped++
                mask.recycle()
                continue
            }
            val cut = MaskCropNode.Cut(InpaintPixels.cut(cur, rect, patchW, patchH), InpaintPixels.cut(mask, rect, patchW, patchH), rect)
            val drawn = render(ImageStore.encodePng(cut.image), ImageStore.encodePng(cut.mask))
            debugSink?.let { it("cut", cut.image); it("mask", cut.mask); it("drawn", drawn) }
            // ⭐ The edge tone always (the user's call, 2026-10-17), after Match colours when that is on.
            val patch = toned(if (match) com.abrah.nightmare.ColorTools.matchBitmap(drawn, cut.image) else drawn, cut.image, cut.mask)
            val dst = RectF(
                cut.rect[0].toFloat(), cut.rect[1].toFloat(),
                (cut.rect[0] + cut.rect[2]).toFloat(), (cut.rect[1] + cut.rect[3]).toFloat(),
            )
            cur = InpaintPixels.composite(cur, patch, dst, cut.mask, featherFrac = settings.blur)
            mask.recycle()
        }
        if (skipped > 0) onSkipped(skipped)
        return cur
    }

    /** ⭐ Context around a part, each side, as a share of its grown box. */
    const val CONTEXT = 0.25f

    /** ⚠ Below this enlargement a redraw only re-noises the part: it is left as it is. */
    const val MIN_ZOOM = 1.25f

    /**
     * ⚠ Pure: the rect (left, top, width, height) to redraw the part [r] (left, top, right, bottom) in: [CONTEXT]
     * around it, at [outW]:[outH]'s aspect, slid inside [w]x[h]; null when it would be enlarged less than [MIN_ZOOM]
     * (a close-up face already has the model's whole canvas).
     */
    fun zoomRect(r: IntArray, w: Int, h: Int, outW: Int, outH: Int): IntArray? {
        val long = maxOf(outW, outH).toFloat()
        val aw = outW / long
        val ah = outH / long
        val bw = (r[2] - r[0]).toFloat()
        val bh = (r[3] - r[1]).toFloat()
        val side = maxOf(bw * (1f + 2f * CONTEXT) / aw, bh * (1f + 2f * CONTEXT) / ah)
            .coerceAtMost(minOf(w / aw, h / ah))
        if (side * MIN_ZOOM > long) return null
        val cw = Math.round(side * aw).coerceIn(1, w)
        val ch = Math.round(side * ah).coerceIn(1, h)
        val left = Math.round((r[0] + r[2]) / 2f - cw / 2f).coerceIn(0, w - cw)
        val top = Math.round((r[1] + r[3]) / 2f - ch / 2f).coerceIn(0, h - ch)
        return intArrayOf(left, top, cw, ch)
    }
}
