package com.abrah.nightmare.npu

/**
 * ⭐⭐ A Wan clip made in PARTS (the user's ask, 2026-10-27: testers chained Wan 2.2 Turbo nodes by hand through
 * `last_frame` and the clip went darker each time): up to [MAX] parts of the node's Seconds each, every part starting
 * on the last frame of the one before, a prompt per part ([promptsFor]), joined in one file by
 * [WanVideoNode.runClip]. The 5B and the 14B; Motion transfer has its own parts ([WanFun.partsOf]).
 *
 * ⚠⚠ The drift is the MODEL's, not the codec's: the tester's 3-part clip went from a mean of 71 to ~25 (R/G 1.15 to
 * 2.0), while 8 round trips of its first frame through TAEHV moved it 1.2 (PC, 2026-10-27, `docs/VIDEO-GGML.md` §10).
 * Each part drifts a little and the next starts from that, so it compounds. [Lock] holds every frame's per-channel
 * mean and spread to the first frame's, gently ([MAX_GAIN], [MAX_SHIFT]), so the start frame each part continues
 * from is the first part's colour and the compounding stops. A switch, on by default ([KEEP_COLOURS]): a clip meant
 * to get darker turns it off.
 */
object WanParts {
    const val PARTS = "parts"
    const val PART_PROMPTS = "part_prompts"
    const val KEEP_COLOURS = "keep_colours"
    const val MAX = 10

    /** ⚠ Shown only when there is more than one part ([com.abrah.nightmare.canvas.hiddenKnob]). */
    val FOLLOWERS = setOf(PART_PROMPTS, KEEP_COLOURS)
    val KNOBS = setOf(PARTS) + FOLLOWERS

    fun countOf(p: Map<String, String>): Int = (p[PARTS]?.toIntOrNull() ?: 1).coerceIn(1, MAX)

    /**
     * ⭐ Pure: each part's prompt. Part 1 is the wired Prompt node's; part k is line k-1 of [lines], and a blank or
     * missing line repeats the part before, so leaving the box empty runs one prompt throughout.
     */
    fun promptsFor(main: String, lines: String?, n: Int): List<String> {
        val ls = lines.orEmpty().lines()
        val out = ArrayList<String>(n)
        out += main
        for (i in 1 until n) {
            val l = ls.getOrNull(i - 1)?.trim().orEmpty()
            out += if (l.isEmpty()) out[i - 1] else l
        }
        return out
    }

    /** Per-channel mean and standard deviation of an RGB frame, 0..255. */
    class Stats(val mean: FloatArray, val sd: FloatArray)

    /** ⚠ Pure: [Stats] of packed RGB, every [stride]-th pixel (a frame's statistics need far fewer than all). */
    fun stats(rgb: ByteArray, stride: Int = 4): Stats {
        val sum = DoubleArray(3)
        val sq = DoubleArray(3)
        var n = 0
        var i = 0
        val step = 3 * stride
        while (i + 2 < rgb.size) {
            for (c in 0 until 3) {
                val v = (rgb[i + c].toInt() and 0xFF).toDouble()
                sum[c] += v; sq[c] += v * v
            }
            n++
            i += step
        }
        val mean = FloatArray(3) { (sum[it] / n).toFloat() }
        val sd = FloatArray(3) { kotlin.math.sqrt(maxOf(0.0, sq[it] / n - (sum[it] / n) * (sum[it] / n))).toFloat() }
        return Stats(mean, sd)
    }

    /** ⚠ The most a frame's spread is scaled, either way: a frame meant to look different is nudged, never repainted. */
    const val MAX_GAIN = 1.25f

    /** ⚠ The most a channel's mean moves, as a share of the first frame's mean (the tester's worst part drifted 29%). */
    const val MAX_SHIFT = 0.4f

    /**
     * ⭐ In place: each channel of [rgb] moved from [cur] towards [anchor]: `(x - mean) * gain + target`, the gain the
     * ratio of spreads held to 1/[MAX_GAIN]..[MAX_GAIN], the target mean the anchor's within [MAX_SHIFT] of [cur]'s.
     */
    fun match(rgb: ByteArray, cur: Stats, anchor: Stats) {
        val gain = FloatArray(3) { c ->
            if (cur.sd[c] < 1f) 1f else (anchor.sd[c] / cur.sd[c]).coerceIn(1f / MAX_GAIN, MAX_GAIN)
        }
        val target = FloatArray(3) { c ->
            val room = MAX_SHIFT * maxOf(anchor.mean[c], 8f)
            cur.mean[c] + (anchor.mean[c] - cur.mean[c]).coerceIn(-room, room)
        }
        var i = 0
        while (i + 2 < rgb.size) {
            for (c in 0 until 3) {
                val v = (rgb[i + c].toInt() and 0xFF)
                val o = (v - cur.mean[c]) * gain[c] + target[c]
                rgb[i + c] = Math.round(o).coerceIn(0, 255).toByte()
            }
            i += 3
        }
    }

    /**
     * ⭐ The whole clip held to its first frame's colour. The statistics it corrects from are smoothed over frames
     * ([SMOOTHING]): a hand crossing the frame changes them for a moment, and following that would flicker.
     */
    class Lock(private val anchor: Stats) {
        private var seen: Stats? = null

        /**
         * ⚠⚠ At every join: the new part's first frame is corrected by its OWN statistics. Smoothed across the join, a
         * part that came out 17 brighter than the frame it started from flashed and faded over 6 frames (S25, the
         * tester's 3 parts, 2026-10-27: 67.3 → 84.7 → 78.8 → 74.7 … 67.9): the smoothing's lag, not the model's.
         */
        fun restart() {
            seen = null
        }

        fun apply(rgb: ByteArray) {
            val s = stats(rgb)
            val prev = seen
            val sm = if (prev == null) s else Stats(
                FloatArray(3) { prev.mean[it] + SMOOTHING * (s.mean[it] - prev.mean[it]) },
                FloatArray(3) { prev.sd[it] + SMOOTHING * (s.sd[it] - prev.sd[it]) },
            )
            seen = sm
            match(rgb, sm, anchor)
        }
    }

    /** ⚠ How far each frame's statistics move the smoothed ones: 0.3, about a third of a second at 24 fps. */
    const val SMOOTHING = 0.3f
}
