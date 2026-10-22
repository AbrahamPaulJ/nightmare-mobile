package com.abrah.nightmare

import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * ⭐⭐ Colour correction (the user's ask, 2026-10-17: "a color correction feature as well as post
 * process, u can decide how"). Two jobs, both on ARGB pixel arrays so the JVM tests reach them:
 *
 *  - [matchColors]: the result takes the source's colours (A1111's img2img colour correction):
 *    Reinhard's transfer, each Lab channel's mean and spread moved to the reference's. What the
 *    sampler's Match colours does to an image-to-image, inpaint or Fix faces patch, where a drifted
 *    tint reads as a seam.
 *  - [autoColor] and [adjust]: the Output node's post-process. Levels stretched per channel at the
 *    0.5 and 99.5 percentiles with a grey-world white balance; then brightness, contrast, saturation
 *    and warmth by hand.
 *
 * ⚠ Alpha is kept as it was in every function.
 */
object ColorTools {

    // ---- Lab ----------------------------------------------------------------------------------

    private fun lin(c: Int): Double = (c / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }

    // ⚠ ROUNDED, here and in [autoColor] / [adjust]: a truncating toInt() pulled most pixels down a
    // level on the Lab round trip, so even an identity match darkened the whole picture slightly.
    private fun gam(v: Double): Int =
        (255.0 * (if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055)).roundToInt().coerceIn(0, 255)

    private fun f(t: Double) = if (t > 216.0 / 24389) cbrt(t) else (24389.0 / 27 * t + 16) / 116

    private fun fi(t: Double) = if (t * t * t > 216.0 / 24389) t * t * t else (116 * t - 16) / (24389.0 / 27)

    /** sRGB → CIE Lab (D65), into [out] at [o]. */
    private fun toLab(p: Int, out: DoubleArray, o: Int) {
        val r = lin(p ushr 16 and 0xFF); val g = lin(p ushr 8 and 0xFF); val b = lin(p and 0xFF)
        val x = f((0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047)
        val y = f(0.2126 * r + 0.7152 * g + 0.0722 * b)
        val z = f((0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883)
        out[o] = 116 * y - 16; out[o + 1] = 500 * (x - y); out[o + 2] = 200 * (y - z)
    }

    private fun fromLab(l: Double, a: Double, bb: Double, alpha: Int): Int {
        val fy = (l + 16) / 116
        val x = 0.95047 * fi(fy + a / 500); val y = fi(fy); val z = 1.08883 * fi(fy - bb / 200)
        val r = 3.2406 * x - 1.5372 * y - 0.4986 * z
        val g = -0.9689 * x + 1.8758 * y + 0.0415 * z
        val b = 0.0557 * x - 0.2040 * y + 1.0570 * z
        return (alpha shl 24) or (gam(r) shl 16) or (gam(g) shl 8) or gam(b)
    }

    private fun stats(lab: DoubleArray): DoubleArray {
        val n = lab.size / 3
        val m = DoubleArray(6)
        for (i in 0 until n) for (c in 0..2) m[c] += lab[3 * i + c]
        for (c in 0..2) m[c] /= n
        for (i in 0 until n) for (c in 0..2) { val d = lab[3 * i + c] - m[c]; m[3 + c] += d * d }
        for (c in 0..2) m[3 + c] = sqrt(m[3 + c] / n)
        return m
    }

    /**
     * ⭐ [target] recoloured to [reference]'s colours (Reinhard, Lab mean and spread), at [strength]
     * 0..1. ⚠ The spread's ratio is capped at 1.5×: a flat patch matched to a busy one would otherwise
     * have its noise multiplied into speckle.
     */
    fun matchColors(target: IntArray, reference: IntArray, strength: Float = 1f): IntArray {
        if (target.isEmpty() || reference.isEmpty()) return target
        val t = DoubleArray(target.size * 3).also { for (i in target.indices) toLab(target[i], it, 3 * i) }
        val r = DoubleArray(reference.size * 3).also { for (i in reference.indices) toLab(reference[i], it, 3 * i) }
        val st = stats(t); val sr = stats(r)
        val k = DoubleArray(3) { c -> if (st[3 + c] < 1e-6) 1.0 else (sr[3 + c] / st[3 + c]).coerceIn(0.0, 1.5) }
        val s = strength.toDouble().coerceIn(0.0, 1.0)
        return IntArray(target.size) { i ->
            val o = 3 * i
            val v = DoubleArray(3) { c ->
                val moved = (t[o + c] - st[c]) * k[c] + sr[c]
                t[o + c] + (moved - t[o + c]) * s
            }
            fromLab(v[0].coerceIn(0.0, 100.0), v[1], v[2], target[i] ushr 24)
        }
    }

    // ---- the Output node's post-process ---------------------------------------------------------

    /** ⚠ Pure: the value at fraction [q] of a 256-bin histogram of [n] samples. */
    private fun percentile(hist: IntArray, n: Int, q: Double): Int {
        val want = (n * q).toLong()
        var acc = 0L
        for (v in 0..255) { acc += hist[v]; if (acc > want) return v }
        return 255
    }

    /**
     * ⭐ Auto colour: each channel's 0.5–99.5 percentile stretched to the full range, then a grey-world
     * balance (the channel means pulled to their common mean) at half strength, so a sunset stays warm.
     * ⚠ A channel with under 16 levels of range is left alone: stretching it is only noise.
     */
    fun autoColor(px: IntArray): IntArray {
        if (px.isEmpty()) return px
        val hist = Array(3) { IntArray(256) }
        for (p in px) { hist[0][p ushr 16 and 0xFF]++; hist[1][p ushr 8 and 0xFF]++; hist[2][p and 0xFF]++ }
        val lut = Array(3) { c ->
            val lo = percentile(hist[c], px.size, 0.005)
            val hi = percentile(hist[c], px.size, 0.995)
            IntArray(256) { v -> if (hi - lo < 16) v else ((v - lo) * 255.0 / (hi - lo)).roundToInt().coerceIn(0, 255) }
        }
        val mean = DoubleArray(3)
        for (p in px) { mean[0] += lut[0][p ushr 16 and 0xFF]; mean[1] += lut[1][p ushr 8 and 0xFF]; mean[2] += lut[2][p and 0xFF] }
        for (c in 0..2) mean[c] /= px.size
        val grey = mean.average()
        val gain = DoubleArray(3) { c -> if (mean[c] < 1) 1.0 else 1 + (grey / mean[c] - 1) * 0.5 }
        return IntArray(px.size) { i ->
            val p = px[i]
            val r = (lut[0][p ushr 16 and 0xFF] * gain[0]).roundToInt().coerceIn(0, 255)
            val g = (lut[1][p ushr 8 and 0xFF] * gain[1]).roundToInt().coerceIn(0, 255)
            val b = (lut[2][p and 0xFF] * gain[2]).roundToInt().coerceIn(0, 255)
            (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
    }

    /**
     * ⭐ By hand, each -1..1 with 0 unchanged: [brightness] adds up to ±25% of white, [contrast] scales
     * about mid-grey (0.5× to 2×), [saturation] scales away from the pixel's own luma (0 to 2×), [warmth]
     * moves red up and blue down (or back) by up to 12%.
     */
    fun adjust(px: IntArray, brightness: Float, contrast: Float, saturation: Float, warmth: Float): IntArray {
        if (brightness == 0f && contrast == 0f && saturation == 0f && warmth == 0f) return px
        val add = brightness.coerceIn(-1f, 1f) * 64f
        val con = 2f.pow(contrast.coerceIn(-1f, 1f))
        val sat = 1f + saturation.coerceIn(-1f, 1f)
        val w = warmth.coerceIn(-1f, 1f) * 0.12f
        return IntArray(px.size) { i ->
            val p = px[i]
            var r = (p ushr 16 and 0xFF).toFloat(); var g = (p ushr 8 and 0xFF).toFloat(); var b = (p and 0xFF).toFloat()
            val y = 0.299f * r + 0.587f * g + 0.114f * b
            r = y + (r - y) * sat; g = y + (g - y) * sat; b = y + (b - y) * sat
            r = (r - 128f) * con + 128f + add; g = (g - 128f) * con + 128f + add; b = (b - 128f) * con + 128f + add
            r *= 1 + w; b *= 1 - w
            (p and 0xFF000000.toInt()) or (r.roundToInt().coerceIn(0, 255) shl 16) or (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)
        }
    }

    // ---- bitmaps ------------------------------------------------------------------------------

    fun pixels(b: android.graphics.Bitmap): IntArray =
        IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    // ⚠ One allocation: `createBitmap(px, …)` is immutable, and copying it made two full-size bitmaps
    // (64 MB each at 4096 px) beside a loaded model, the first never recycled.
    fun bitmap(px: IntArray, w: Int, h: Int): android.graphics.Bitmap =
        android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            .also { it.setPixels(px, 0, w, 0, 0, w, h) }

    /** ⭐ [target] matched to [reference] of any size (sampled down to ~256 px for its statistics). */
    fun matchBitmap(target: android.graphics.Bitmap, reference: android.graphics.Bitmap, strength: Float = 1f): android.graphics.Bitmap {
        val s = (256f / maxOf(reference.width, reference.height)).coerceAtMost(1f)
        val small = android.graphics.Bitmap.createScaledBitmap(
            reference, (reference.width * s).toInt().coerceAtLeast(1), (reference.height * s).toInt().coerceAtLeast(1), true,
        )
        val refPx = pixels(small)
        if (small !== reference) small.recycle()
        return bitmap(matchColors(pixels(target), refPx, strength), target.width, target.height)
    }
}
