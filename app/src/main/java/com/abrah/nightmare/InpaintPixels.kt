package com.abrah.nightmare

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The pixel half of [InpaintCrop]: find a mask's bounds, cut a crop, and blend a
 * patch back. ⚠ DreamUI's `InpaintCrop.composite`/`feather`, unchanged in
 * behaviour; the only adaptation is that the mask arrives as a RASTER here (a
 * wire carries a picture, not stroke geometry), so bounds and the seam are
 * measured off pixels.
 */
object InpaintPixels {

    /** Resolution the blend alpha map is computed at, then scaled up bilinear. */
    private const val ALPHA_DIM = 256

    /** Feather band, as a divisor of the patch edge, and its floor in px. */
    private const val FEATHER_DIVISOR = 24
    private const val MIN_FEATHER = 3

    /** ⚠ Bounds are measured on a copy this big — a readout, not the mask. */
    private const val BOUNDS_DIM = 256

    /**
     * The painted bounds of a white-on-black [mask], normalised
     * (left, top, right, bottom), or null when nothing is painted.
     */
    fun bounds01(mask: Bitmap): FloatArray? {
        val k = min(1f, BOUNDS_DIM.toFloat() / max(mask.width, mask.height))
        val w = (mask.width * k).roundToInt().coerceAtLeast(1)
        val h = (mask.height * k).roundToInt().coerceAtLeast(1)
        val small = if (w == mask.width && h == mask.height) mask else Bitmap.createScaledBitmap(mask, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((px[y * w + x] and 0xFF) >= 128) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
            }
        }
        if (r < 0) return null
        return floatArrayOf(l.toFloat() / w, t.toFloat() / h, (r + 1f) / w, (b + 1f) / h)
    }

    /** [rect] (left, top, width, height in [src] pixels), scaled to [outW] x [outH]. */
    fun cut(src: Bitmap, rect: IntArray, outW: Int, outH: Int): Bitmap {
        val region = Bitmap.createBitmap(src, rect[0], rect[1], rect[2], rect[3])
        return if (region.width == outW && region.height == outH) region
        else Bitmap.createScaledBitmap(region, outW, outH, true)
    }

    /**
     * Pastes [patch] into a copy of [target] over [dst] (target pixels).
     *
     * ⚠⚠ Blended along the mask rather than pasted as a rectangle. The patch's
     * UNMASKED pixels have been through a resample and a VAE round trip, so they
     * differ subtly from the original and a straight paste leaves a visible
     * square seam. Two feathers handle the two seams: a ramp out from the painted
     * region, and a ramp to zero at any patch edge that sits inside the target
     * (edges flush with the border keep full strength — nothing to blend into).
     *
     * @param cropMask the mask in the PATCH's frame, white = repainted.
     */
    fun composite(target: Bitmap, patch: Bitmap, dst: RectF, cropMask: Bitmap): Bitmap {
        val out = target.copy(Bitmap.Config.ARGB_8888, true)
        val r = Rect(dst.left.roundToInt(), dst.top.roundToInt(), dst.right.roundToInt(), dst.bottom.roundToInt())
        if (r.width() <= 0 || r.height() <= 0) return out

        val scaled = if (patch.width == r.width() && patch.height == r.height()) patch
        else Bitmap.createScaledBitmap(patch, r.width(), r.height(), true)

        val alpha = feather(
            cropMask,
            fadeLeft = r.left > 0,
            fadeTop = r.top > 0,
            fadeRight = r.right < target.width,
            fadeBottom = r.bottom < target.height,
            dstW = r.width(),
            dstH = r.height(),
        )
        val masked = scaled.copy(Bitmap.Config.ARGB_8888, true)
        // ⚠ copy() carries hasAlpha=false from an opaque decode, and DST_IN then
        // composites to opaque black instead of transparent.
        masked.setHasAlpha(true)
        Canvas(masked).drawBitmap(
            alpha, null, Rect(0, 0, masked.width, masked.height),
            Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) },
        )
        // ⚠ A rect partly outside the target (a padded frame) is simply clipped
        // by the canvas; the edge flags above already treat that side as a border.
        Canvas(out).drawBitmap(masked, r.left.toFloat(), r.top.toFloat(), null)
        alpha.recycle()
        masked.recycle()
        return out
    }

    /**
     * Blend alpha: opaque over the painted region, smoothstep falloff across the
     * band outside it, ramped to zero toward the selected edges. Computed small
     * and stretched by the caller's bilinear draw, which is what makes the ramp
     * smooth rather than banded.
     */
    private fun feather(
        cropMask: Bitmap,
        fadeLeft: Boolean,
        fadeTop: Boolean,
        fadeRight: Boolean,
        fadeBottom: Boolean,
        dstW: Int,
        dstH: Int,
    ): Bitmap {
        // ⚠ The alpha map carries the DESTINATION's aspect, or one axis is
        // stretched more than the other and the seam goes lopsided.
        val longEdge = max(dstW, dstH).coerceAtLeast(1)
        val dw = (ALPHA_DIM * dstW / longEdge).coerceAtLeast(1)
        val dh = (ALPHA_DIM * dstH / longEdge).coerceAtLeast(1)

        val small = Bitmap.createScaledBitmap(cropMask, dw, dh, true)
        val px = IntArray(dw * dh)
        small.getPixels(px, 0, dw, 0, 0, dw, dh)

        val dist = MaskRaster.seed(px, dw, dh)
        MaskRaster.chamfer(dist, dw, dh)
        // Band off the shorter edge so the feather is the same physical width on
        // both axes.
        val bandPx = max(MIN_FEATHER, min(dw, dh) / FEATHER_DIVISOR).toFloat()
        val band = bandPx * MaskRaster.CH_STRAIGHT

        for (y in 0 until dh) {
            for (x in 0 until dw) {
                val i = y * dw + x
                val inside = 1f - MaskRaster.smoothstep(min(dist[i].toFloat() / band, 1f))
                var e = Int.MAX_VALUE
                if (fadeLeft) e = min(e, x)
                if (fadeTop) e = min(e, y)
                if (fadeRight) e = min(e, dw - 1 - x)
                if (fadeBottom) e = min(e, dh - 1 - y)
                val edge = if (e == Int.MAX_VALUE) 1f else MaskRaster.smoothstep(min(e / bandPx, 1f))
                val a = (inside * edge * 255f).roundToInt().coerceIn(0, 255)
                px[i] = (a shl 24) or 0xFFFFFF
            }
        }
        return Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888).apply {
            setPixels(px, 0, dw, 0, 0, dw, dh)
        }
    }
}
