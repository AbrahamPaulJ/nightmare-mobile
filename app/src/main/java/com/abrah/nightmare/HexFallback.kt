package com.abrah.nightmare

import android.content.Context

/**
 * ⭐⭐ A phone whose NPU ran short of VTCM (its fast on-chip memory) in a DiT render runs the
 * engine with ggml-hexagon's op FUSIONS off from then on.
 *
 * ⚠⚠ Why (a tester, 2026-10-13, 1.6.146, a vivo on an SM8845 — v81, 6 HVX threads, 8 MB VTCM):
 * every Anima DiT render died at its first graph with *"dsp-rsp VTCM-TOO-SMALL"*, then SIGABRT.
 * The S25 (v79) never does. The VTCM checks that can refuse live in the DSP-side kernels, and the
 * fused ones (`GGML_HEXAGON_OPFUSION`, set for Anima by [BackendProcess]) are the newest of them;
 * unfused, every op is one the engine has always run. ⚠ Reasoned, not measured: nothing here has
 * that chip. The cost of being wrong is one relaunch and a slower render, never a wrong picture —
 * fusions off computes the same graph (Anima's black picture came from ONE fusion being ON).
 * ⚠ Per device, not per model: VTCM is the chip's.
 */
object HexFallback {
    private const val PREFS = "hex_fallback"
    private const val KEY = "no_fusion"
    private val TOO_SMALL = Regex("""VTCM-TOO-SMALL""")

    fun noFusion(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    /** ⚠ Pure — the log shows a kernel refusing for want of VTCM. */
    fun vtcmTooSmall(lines: Iterable<String>): Boolean = lines.any { TOO_SMALL.containsMatchIn(it) }

    /** ⭐ True when [lines] taught something new — then the caller relaunches. False stops a loop. */
    fun learn(context: Context, lines: Iterable<String>): Boolean {
        if (noFusion(context) || !vtcmTooSmall(lines)) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
        return true
    }
}
