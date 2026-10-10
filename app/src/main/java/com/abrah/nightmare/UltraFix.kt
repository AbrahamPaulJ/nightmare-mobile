package com.abrah.nightmare

import android.content.Context

/**
 * ⭐⭐⭐ **UltraFix** — Local Dream's tiled img2img repair of an upscaled picture (its v2.7.0,
 * `Pipeline::runUnetTiled` + `ultrafixInvertNoise` — already in our backend; `RequestParser.hpp`'s
 * `ultrafix` / `tile_size`). The whole picture goes in at full size; the UNet runs at its fixed
 * graph size over overlapping tiles, so detail is redrawn without changing the composition.
 *
 * The dialog's numbers and defaults are Local Dream's (`GenerationDefaults.kt`): 10 steps, 4 of
 * them denoised, on neutral quality tags unless the toggle is off. Asked for by a user, 2026-10-09.
 */
object UltraFix {
    data class Params(val steps: Int = 10, val denoiseSteps: Int = 4, val qualityPrompt: Boolean = true)

    const val STEPS_MIN = 1
    const val STEPS_MAX = 20
    const val DENOISE_STEPS_MAX = 10
    const val QUALITY_PROMPT = "masterpiece, best quality, 4k resolution"

    /**
     * ⭐ Local Dream's mapping (`ultrafixDenoiseStrength`): the strength that makes the backend run
     * exactly [denoiseSteps] of [steps] — the half step keeps the rounding on the right side.
     */
    fun strength(denoiseSteps: Int, steps: Int): Double {
        if (steps <= 0) return 0.0
        return ((denoiseSteps.coerceIn(0, steps) - 0.5) / steps).coerceIn(0.0, 1.0)
    }

    /** ⭐ The tile = the family's fixed UNet graph: 512 for SD 1.5, 1024 for SDXL. */
    fun tileFor(family: Family): Int? = when (family) {
        Family.SD15 -> 512
        Family.SDXL -> 1024
        else -> null
    }

    /** ⚠ A size the backend accepts: multiples of 8 (it refuses otherwise), never larger. */
    fun snap(w: Int, h: Int): Pair<Int, Int> = (w / 8 * 8) to (h / 8 * 8)

    private fun prefs(c: Context) = c.getSharedPreferences("ultrafix", Context.MODE_PRIVATE)

    fun load(c: Context): Params = prefs(c).let {
        Params(
            it.getInt("steps", 10).coerceIn(STEPS_MIN, STEPS_MAX),
            it.getInt("denoise_steps", 4),
            it.getBoolean("quality", true),
        )
    }

    fun save(c: Context, p: Params) {
        prefs(c).edit().putInt("steps", p.steps).putInt("denoise_steps", p.denoiseSteps)
            .putBoolean("quality", p.qualityPrompt).apply()
    }
}
