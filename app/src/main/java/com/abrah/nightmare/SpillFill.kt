package com.abrah.nightmare

import android.content.Context

/**
 * ⭐⭐⭐ **The HTP spill-fill buffer a checkpoint actually needs, learned from the
 * error that says so** — and remembered per model.
 *
 * ⚠⚠⚠ Reported 2026-10-01 from a user's phone, an SDXL checkpoint
 * (`hardcoreAsianCosplay_ilV11`): *"the backend would not start — QnnDsp <E>
 * Shared spill-fill size 964689920 is smaller than required spill-fill size
 * 1104281600"*. `PipelineSdxl.hpp` registers the UNet and both VAEs in one
 * spill-fill group sized from a HARDCODED 920 MiB, measured on one set of
 * binaries; `PipelineAnima.hpp` does the same with 573 MiB (GitHub #2, a v75
 * phone). Upstream local-dream has both constants. A checkpoint converted by
 * another toolchain, or for another arch, can need more, and then it never
 * starts — while QNN has just printed the exact number it wanted.
 *
 * ⇒ Read that number ([required]), store it for the model, and launch with it
 * in the backend's own override (`LOCALDREAM_SDXL_SPILL_FILL_BYTES` /
 * `LOCALDREAM_ANIMA_SPILL_FILL_BYTES`, both read unconditionally by
 * `spillFillGroupBytes()`). No backend change, and a second launch costs ~5 s
 * once per model rather than a user's whole evening.
 *
 * ⚠ Only ever RAISED: the stored size is the max of everything seen, so two
 * contexts reporting different needs settle on the bigger. ⚠ The diagnostic
 * file `nightmare-spillfill.txt` still wins over this, for Anima, because it is
 * read after.
 */
object SpillFill {

    private const val FILE = "spillfill"

    private val NEED = Regex("""required spill-fill size (\d+)""")

    /** ⚠ Pure — the largest size the given log lines say was required, or null. */
    fun required(lines: Iterable<String>): Long? =
        lines.mapNotNull { NEED.find(it)?.groupValues?.get(1)?.toLongOrNull() }.maxOrNull()

    /** The backend's env var for this family's group, or null when it has none. */
    fun envFor(family: Family?): String? = when (family) {
        Family.SDXL -> "LOCALDREAM_SDXL_SPILL_FILL_BYTES"
        Family.ANIMA -> "LOCALDREAM_ANIMA_SPILL_FILL_BYTES"
        else -> null
    }

    fun stored(context: Context, modelId: String): Long? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getLong(modelId, 0L).takeIf { it > 0 }

    /** ⚠ Harness only (`spillfill` op): sets or clears, LOWER included. */
    fun setForTest(context: Context, modelId: String, bytes: Long) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            if (bytes > 0) putLong(modelId, bytes) else remove(modelId)
        }.apply()
    }

    /**
     * ⭐ Learn from the newest backend launch's log: true when it named a size
     * bigger than the one [modelId] already had, which is stored — so a
     * relaunch is worth making. False when there is nothing new to try, which
     * is what stops a retry loop.
     */
    fun learn(context: Context, modelId: String): Boolean {
        val spec = ModelCatalog.byId(modelId)
        if (envFor(spec?.family) == null) return false
        val need = required(BackendProcess.newestLaunch()) ?: return false
        val had = stored(context, modelId) ?: 0L
        if (need <= had) return false
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putLong(modelId, need).apply()
        return true
    }
}
