package com.abrah.nightmare.segment

/**
 * ⭐⭐⭐ **From the parser's probabilities to one target's mask** — pure Kotlin,
 * so the JVM suite can hold every rule below without a model.
 *
 * ⚠⚠ Measured 2026-09-23 on a photo the user reported from the phone (a
 * white crop top, long hair over one shoulder, jeans cut off by the frame),
 * int8 against fp32 on the PC, same preprocessing as the app. Each rule here
 * is the fix for one thing the plain argmax got wrong on it:
 *
 * 1. **A target is PRESENT only with a confident core** — some cells where the
 *    group's probability is over [CORE]. The argmax reported Shoes and Bag on
 *    a photo with neither: those cells peaked at 0.85 and 0.65, where every
 *    real part had a core near 1.0. ⚠ fp32 was WORSE here (shoes peaked 0.93),
 *    so a bigger model is not the fix; the user's rule is *"id rather they
 *    detect nothing than try to mask something"*.
 * 2. **An absent target's probability goes to what is left**, renormalised.
 *    The phantom shoes and bag WERE the jeans — a patchwork of Pants, Shoe,
 *    Bag and Leg — so rejecting them gave the jeans back to Clothes whole.
 * 3. **The GROUP's probability, bilinearly upsampled, cut at [CUT]** — not a
 *    nearest-neighbour stretch of a 128² argmax. The old mask was blocky and
 *    left a white rim along every hem and collar, where the model splits a
 *    pixel between the garment and the background.
 * 4. **Face keeps only the TOPMOST region.** ATR has no torso-skin label, so
 *    a bare midriff is labelled Face — the old Head chip repainted a stomach.
 */
object ParseMask {

    /** A cell this sure of a group is evidence the thing is in the picture. */
    const val CORE = 0.9f

    /**
     * ⚠ How many core cells make a target present, as a fraction of the grid:
     * 16 cells of 128², a patch about 1/32 of the edge across. The phantom
     * shoes and bag had ZERO; the smallest real part on the fixture had 654.
     */
    const val MIN_CORE_FRAC = 1f / 1024f

    /** ⚠ Below 0.5 on purpose: it is the garment's EDGE that 0.5 was losing. */
    const val CUT = 0.35f

    /** The mask's edge. The photo is squashed to one square, so is this. */
    const val OUT = 512

    /**
     * ⚠ A face region smaller than this fraction of the largest one is noise,
     * not a second face — it must not win "topmost" by being a speck of hair.
     */
    private const val MIN_REGION_FRAC = 0.1f

    /** Whether the group [labels] has a confident core in [probs]. */
    fun present(probs: FloatArray, classes: Int, plane: Int, labels: IntArray): Boolean {
        val need = maxOf(1, (plane * MIN_CORE_FRAC).toInt())
        var core = 0
        for (i in 0 until plane) {
            var g = 0f
            for (l in labels) if (l < classes) g += probs[l * plane + i]
            if (g > CORE && ++core >= need) return true
        }
        return false
    }

    /**
     * The mask for [target] out of [groups] (every target the chips offer, so
     * an absent one can hand its pixels on), as [out]² bytes, 255 = masked.
     * Null when [target] is not in the picture.
     *
     * [probs] is softmax output, class-major: `probs[c * w * h + y * w + x]`.
     */
    fun mask(
        probs: FloatArray,
        classes: Int,
        w: Int,
        h: Int,
        groups: List<IntArray>,
        target: IntArray,
        topmostOnly: Boolean = false,
        out: Int = OUT,
    ): ByteArray? {
        val plane = w * h
        if (!present(probs, classes, plane, target)) return null
        val absent = groups
            .filter { g -> !g.contentEquals(target) && !present(probs, classes, plane, g) }
            .flatMap { it.toList() }
            .filter { it < classes && it !in target }
            .toIntArray()
        // The group's share of what is left once the absent labels are gone.
        val g = FloatArray(plane)
        for (i in 0 until plane) {
            var mine = 0f
            for (l in target) if (l < classes) mine += probs[l * plane + i]
            var gone = 0f
            for (l in absent) gone += probs[l * plane + i]
            val rest = 1f - gone
            g[i] = if (rest > 1e-6f) mine / rest else 0f
        }
        val mask = ByteArray(out * out)
        var hit = 0
        // ⚠ Pixel CENTRES, as an image resampler maps them — corner-aligned
        // sampling shifts the whole mask half a cell toward the top left.
        val sx = w.toFloat() / out
        val sy = h.toFloat() / out
        for (y in 0 until out) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = minOf(y0 + 1, h - 1)
            val ty = fy - y0
            for (x in 0 until out) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (w - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = minOf(x0 + 1, w - 1)
                val tx = fx - x0
                val top = g[y0 * w + x0] * (1 - tx) + g[y0 * w + x1] * tx
                val bot = g[y1 * w + x0] * (1 - tx) + g[y1 * w + x1] * tx
                if (top * (1 - ty) + bot * ty > CUT) {
                    mask[y * out + x] = 255.toByte()
                    hit++
                }
            }
        }
        if (hit == 0) return null
        return if (topmostOnly) topmost(mask, out) else mask
    }

    /** Keep only the highest sizeable 4-connected region of [mask]. */
    private fun topmost(mask: ByteArray, n: Int): ByteArray? {
        val region = IntArray(n * n) { -1 }
        val sizes = ArrayList<Int>()
        val ySums = ArrayList<Long>()
        val stack = IntArray(n * n)
        for (start in 0 until n * n) {
            if (mask[start].toInt() == 0 || region[start] >= 0) continue
            val id = sizes.size
            var size = 0
            var ySum = 0L
            var sp = 0
            stack[sp++] = start
            region[start] = id
            while (sp > 0) {
                val i = stack[--sp]
                size++
                ySum += i / n
                val x = i % n
                for (j in intArrayOf(i - n, i + n, if (x > 0) i - 1 else -1, if (x < n - 1) i + 1 else -1)) {
                    if (j < 0 || j >= n * n || mask[j].toInt() == 0 || region[j] >= 0) continue
                    region[j] = id
                    stack[sp++] = j
                }
            }
            sizes += size
            ySums += ySum
        }
        if (sizes.isEmpty()) return null
        val biggest = sizes.max()
        val keep = sizes.indices
            .filter { sizes[it] >= biggest * MIN_REGION_FRAC }
            .minBy { ySums[it].toDouble() / sizes[it] }
        return ByteArray(n * n) { if (region[it] == keep) 255.toByte() else 0 }
    }
}
