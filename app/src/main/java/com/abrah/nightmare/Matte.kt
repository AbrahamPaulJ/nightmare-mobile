package com.abrah.nightmare

import android.graphics.Bitmap

/**
 * ⭐ RMBG 3.0's two ends ([ModelSpec.matte], GitHub #136): the instruction it was trained on, and its grey matte made
 * into the photo's alpha. White keeps, black removes, grey is the soft edge (hair, fur, glass).
 */
object Matte {

    /** ⚠ Bria's exact text (the model card): a changed or missing one gives a coloured picture instead of a matte. */
    const val INSTRUCTION =
        "Generate a detailed grayscale alpha matte. Map the opaque foreground to white and the background to black. " +
            "Produce soft, anti-aliased grayscale gradients at the edges of the subject to represent fine details and transparency."

    /**
     * ⭐⭐ The size RMBG 3.0 renders a [w]x[h] picture at: the largest on the engine's grid within a megapixel, the
     * size Bria trained it at, in the picture's shape (within 1 %: [cut] stretches the matte onto the photo's own
     * pixels). ⚠ Above a megapixel it breaks: 960x1280 came back near-white with green streaks and the cut removed
     * the wrong parts of a photo of two people (S25, 2026-11-22), while 1024x1024 and 768x1024 gave clean mattes.
     */
    fun renderSize(w: Int, h: Int, max: Int = ModelCatalog.DIT_MAX): Pair<Int, Int> {
        val aspect = w.toDouble() / h
        var best: Pair<Int, Int>? = null
        var nearest: Pair<Int, Int>? = null
        var nearestErr = Double.MAX_VALUE
        for (cw in ModelCatalog.DIT_MIN..max step ModelCatalog.DIT_STEP) {
            val ch = ModelCatalog.ditSnap((cw / aspect).toInt(), max)
            if (cw.toLong() * ch > MAX_PIXELS) continue
            val err = kotlin.math.abs(cw.toDouble() / ch - aspect)
            if (err < nearestErr) { nearestErr = err; nearest = cw to ch }
            if (err < 0.01 && (best == null || cw.toLong() * ch > best.first.toLong() * best.second)) best = cw to ch
        }
        return best ?: nearest ?: (ModelCatalog.DIT_MIN to ModelCatalog.DIT_MIN)
    }
    private const val MAX_PIXELS = 1024L * 1024L

    /** ⭐ Each pixel of [px] with the alpha of [matte]'s brightness at the same place (both ARGB, the same length). */
    fun alphaFrom(px: IntArray, matte: IntArray): IntArray {
        require(px.size == matte.size) { "matte ${matte.size} px for a ${px.size} px photo" }
        return IntArray(px.size) { i ->
            val m = matte[i]
            // ⚠ Luma, not one channel: the engine's VAE returns a near-grey RGB, never exactly equal channels.
            val a = (((m shr 16) and 0xFF) * 77 + ((m shr 8) and 0xFF) * 150 + (m and 0xFF) * 29 + 128) shr 8
            (a.coerceIn(0, 255) shl 24) or (px[i] and 0x00FFFFFF)
        }
    }

    /**
     * ⭐ [photo] with [matte] as its alpha, at the PHOTO's size: a smaller matte is scaled up, filtered, as Bria's own
     * example does (`resize(source.size, LANCZOS)`), so a 12 MP photo keeps its pixels and only the edge is upsampled.
     */
    fun cut(photo: Bitmap, matte: Bitmap): Bitmap {
        val w = photo.width
        val h = photo.height
        val m = if (matte.width == w && matte.height == h) matte else Bitmap.createScaledBitmap(matte, w, h, true)
        val px = IntArray(w * h).also { photo.getPixels(it, 0, w, 0, 0, w, h) }
        val mm = IntArray(w * h).also { m.getPixels(it, 0, w, 0, 0, w, h) }
        return Bitmap.createBitmap(alphaFrom(px, mm), w, h, Bitmap.Config.ARGB_8888)
    }
}
