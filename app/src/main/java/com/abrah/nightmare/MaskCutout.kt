package com.abrah.nightmare

import android.graphics.Bitmap

/**
 * ⭐ A picture with its background removed (Saved #8052, the user 2026-11-14: a button on Tap select): the photo's
 * pixels where the mask is white, transparent where it is black, the mask's soft edge as the alpha's, cropped to what
 * the mask covers. Saved as a PNG, which keeps the transparency.
 */
object MaskCutout {

    /** ⚠ [mask] is [MaskRaster.rasterise]'s black/white at [photo]'s size; null when it covers nothing. */
    fun cut(photo: Bitmap, mask: Bitmap): Bitmap? {
        val w = photo.width
        val h = photo.height
        require(mask.width == w && mask.height == h) { "mask ${mask.width}x${mask.height} for a ${w}x$h photo" }
        val px = IntArray(w * h).also { photo.getPixels(it, 0, w, 0, 0, w, h) }
        val m = IntArray(w * h).also { mask.getPixels(it, 0, w, 0, 0, w, h) }
        var x0 = w
        var y0 = h
        var x1 = -1
        var y1 = -1
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val a = (m[i] shr 16) and 0xFF
            px[i] = (a shl 24) or (px[i] and 0x00FFFFFF)
            if (a > 8) {
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
        }
        if (x1 < 0) return null
        val cw = x1 - x0 + 1
        val ch = y1 - y0 + 1
        val out = IntArray(cw * ch)
        for (y in 0 until ch) System.arraycopy(px, (y0 + y) * w + x0, out, y * cw, cw)
        return Bitmap.createBitmap(out, cw, ch, Bitmap.Config.ARGB_8888)
    }
}
