package com.abrah.nightmare

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * ⭐⭐⭐ **"Only masked" and "stitch to original" — DreamUI's logic, in a graph.**
 *
 * Copied from `../LocalDream/dreamui/.../InpaintCrop.kt` constant for constant
 * (the user's call, 2026-09-15: *"both are straightforward toggles on dreamui,
 * just use same logic"*). Two things are wrong with inpainting a whole frame at
 * the render size, and this fixes both:
 *
 *  1. **Latent resolution.** A 100 px mask in a 512 px frame is ~12x12 of the
 *     64x64 latent grid. Cropping to it spends the whole grid on the region
 *     being repainted.
 *  2. **Everything else is destroyed.** Generating the frame at 512 hands back
 *     a 512 thumbnail of a photo that had far more pixels. Here only the crop is
 *     regenerated and composited back, so the rest of the picture survives.
 *
 * ⚠⚠ The one deliberate difference from DreamUI: its source is a SQUARE centre
 * crop and every rect is normalised to it. Here the source is whatever the
 * `image.crop` upstream framed, of any aspect, so the arithmetic is done in
 * SOURCE PIXELS — which is the same computation with the square's edge replaced
 * by the two real edges.
 *
 * ⚠ The rect maths is Android-free and JVM-tested (`InpaintCropTest`); the
 * pixel work is in [InpaintPixels].
 */
object InpaintCrop {

    /**
     * Extra margin around the mask bounds, as a fraction of the bounds' edge per
     * side. ⚠ More generous than A1111's 32 px: the model fills FROM the
     * surroundings it is given, so the margin is the mechanism, not padding.
     */
    const val PAD_RATIO = 0.35f

    /** The margin still defended when [PAD_RATIO] cannot be afforded. */
    const val MIN_PAD_RATIO = 0.15f

    /** Largest share of the crop the mask itself may occupy. */
    const val MAX_COVERAGE = 0.55f

    /** Above this share of the largest possible crop, cropping buys nothing. */
    const val NO_CROP_ABOVE = 0.85f

    /**
     * The crop to generate over, in source pixels (left, top, width, height), or
     * null when the whole frame should be used — nothing painted, or the mask is
     * already most of the picture.
     *
     * @param bounds the mask's painted bounds, NORMALISED to the source:
     *   left, top, right, bottom in 0..1.
     *
     * ⚠⚠ The crop always has the OUTPUT's aspect, so the patch is resampled
     * isotropically going in and coming back — a crop of another shape would
     * stretch it on the way back and turn every round brush dab into an ellipse.
     *
     * ⚠ Never smaller than the output in source pixels, so the patch is always
     * downscaled into the model and never blurrily upscaled on the way back.
     */
    fun compute(bounds: FloatArray?, srcW: Int, srcH: Int, outW: Int, outH: Int): IntArray? {
        if (bounds == null || srcW <= 0 || srcH <= 0 || outW <= 0 || outH <= 0) return null
        val bw = (bounds[2] - bounds[0]) * srcW
        val bh = (bounds[3] - bounds[1]) * srcH
        if (bw <= 0f || bh <= 0f) return null

        // Unit aspect: the longer output edge is 1. The crop is (side*aw) by
        // (side*ah), so `side` alone drives sizing — DreamUI's shape exactly.
        val longEdge = max(outW, outH).toFloat()
        val aw = outW / longEdge
        val ah = outH / longEdge

        // ⚠ One source pixel per output pixel: below it the patch is enlarged.
        val oneToOne = longEdge
        val padded = max(bw * (1f + 2f * PAD_RATIO) / aw, bh * (1f + 2f * PAD_RATIO) / ah)
        val desired = max(padded, sqrt(bw * bh / (MAX_COVERAGE * aw * ah)))
        val minimal = max(bw * (1f + 2f * MIN_PAD_RATIO) / aw, bh * (1f + 2f * MIN_PAD_RATIO) / ah)

        var side = if (desired <= oneToOne) oneToOne else max(oneToOne, minimal)

        // ⚠ The largest crop of this aspect that fits the source. DreamUI's cap
        // of 1 (the square's edge) is this, for a square.
        val fit = min(srcW / aw, srcH / ah)
        side = min(side, fit)
        if (side >= NO_CROP_ABOVE * fit) return null

        val cw = (side * aw).roundToInt().coerceIn(1, srcW)
        val ch = (side * ah).roundToInt().coerceIn(1, srcH)
        // Centre on the mask, then SLIDE the rect inside the source. ⚠ Sliding,
        // not clamping an edge: clamping would change the aspect.
        val cx = (bounds[0] + bounds[2]) / 2f * srcW
        val cy = (bounds[1] + bounds[3]) / 2f * srcH
        val left = (cx - cw / 2f).roundToInt().coerceIn(0, srcW - cw)
        val top = (cy - ch / 2f).roundToInt().coerceIn(0, srcH - ch)
        return intArrayOf(left, top, cw, ch)
    }

    /**
     * ⭐ The rect to use when [compute] declines, or "Only masked" is off: the
     * largest centred rect of the output's aspect. DreamUI's source is already
     * that shape, so there it is simply the whole square.
     */
    fun whole(srcW: Int, srcH: Int, outW: Int, outH: Int): IntArray {
        if (outW <= 0 || outH <= 0) return intArrayOf(0, 0, srcW, srcH)
        val k = min(srcW.toFloat() / outW, srcH.toFloat() / outH)
        val cw = (outW * k).roundToInt().coerceIn(1, srcW)
        val ch = (outH * k).roundToInt().coerceIn(1, srcH)
        return intArrayOf((srcW - cw) / 2, (srcH - ch) / 2, cw, ch)
    }

    /**
     * ⭐ [inner] (a rect in [region]'s source pixels) expressed in the pixels of
     * [region]'s PARENT — how a patch cut from the frame lands on the photo the
     * frame was cut from. Left/top/right/bottom, as floats: the frame may be
     * scaled, and may extend past the photo (the padded crop).
     */
    fun toParent(inner: Region, region: Region): FloatArray {
        // ⚠ Parent pixels per FRAME pixel: the frame's rect in the photo over the
        // frame's own size — which is [inner]'s source, not [region]'s.
        val sx = (region.right - region.left) / inner.sourceW
        val sy = (region.bottom - region.top) / inner.sourceH
        return floatArrayOf(
            region.left + inner.left * sx,
            region.top + inner.top * sy,
            region.left + inner.right * sx,
            region.top + inner.bottom * sy,
        )
    }
}

/**
 * ⭐ Where a picture was cut from: a rect in its SOURCE's pixels, and the
 * source's size.
 *
 * ⚠⚠ Rects, never image ids. A paste reads the pixels it composites into from
 * its own INPUT wires, so a region can never point at a bitmap the store has
 * already evicted — a cached crop would otherwise hand a paste a dead id.
 *
 * @param parent where the SOURCE itself was cut from, when it was (a frame cut
 *   from a photo). This chain is what "stitch to original" walks.
 */
data class Region(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val sourceW: Int,
    val sourceH: Int,
    val parent: Region? = null,
)
