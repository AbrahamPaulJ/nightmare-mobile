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
 *
 * ⚠⚠ **A group that cannot be made at all** (GitHub #8, 2026-10-08, an SM8850 / v81
 * phone, an npuforge SDXL Swap conversion, SDXL low-RAM off): the UNet, as group head,
 * failed `contextFromBin (submit) … 5005` on the HTP, was created without the group,
 * and the VAE decoder then died on *"Context group 1 does not exist!"*. No size is
 * printed, so there is nothing to raise — the fix is NO group ([unshared]): the env
 * var at `0`, which both pipelines' `spillFillGroupBytes()` read as "sharing off".
 * Each context then owns its buffer (~600 MB more for SDXL). Low-RAM on never
 * groups, which is why it worked there. ⚠ A second head failure ends the same way
 * (2026-10-10, SM8650, plain SDXL, a RELAUNCH of a model that had just run grouped):
 * *"fastrpc memory map … length 2598371328 failed"*. Possibly transient — unshared is
 * kept anyway, ~600 MB rather than a retry chain.
 */
object SpillFill {

    private const val FILE = "spillfill"

    private val NEED = Regex("""required spill-fill size (\d+)""")

    /** The group head's own creation failed, so a member had no group to join. */
    private val GROUP_FAILED = Regex("""Context group \d+ does not exist|Failed to register spill-fill buffer""")

    /** ⚠ Pure — the largest size the given log lines say was required, or null. */
    fun required(lines: Iterable<String>): Long? =
        lines.mapNotNull { NEED.find(it)?.groupValues?.get(1)?.toLongOrNull() }.maxOrNull()

    /** ⚠ Pure — true when the log shows the group itself could not be made. */
    fun groupFailed(lines: Iterable<String>): Boolean = lines.any { GROUP_FAILED.containsMatchIn(it) }

    private fun unsharedKey(modelId: String) = "$modelId#unshared"

    /** ⭐ True when [modelId] launches with sharing off — learned from [groupFailed]. */
    fun unshared(context: Context, modelId: String): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(unsharedKey(modelId), false)

    /** ⭐ The env value [modelId] launches with — `0` (sharing off), a learned size, or null. */
    fun envValue(context: Context, modelId: String): String? =
        if (unshared(context, modelId)) "0" else stored(context, modelId)?.toString()

    /** ⭐ What was learned, for the run log and the message. */
    fun describe(context: Context, modelId: String): String =
        if (unshared(context, modelId)) "no shared spill-fill buffer"
        else "a ${stored(context, modelId)}-byte spill-fill buffer"

    /** The backend's env var for this family's group, or null when it has none. */
    fun envFor(family: Family?): String? = when (family) {
        Family.SDXL, Family.SDXL_SWAP -> "LOCALDREAM_SDXL_SPILL_FILL_BYTES"
        Family.ANIMA -> "LOCALDREAM_ANIMA_SPILL_FILL_BYTES"
        else -> null
    }

    fun stored(context: Context, modelId: String): Long? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getLong(modelId, 0L).takeIf { it > 0 }

    /** ⚠ Harness only (`spillfill` op): sets or clears, LOWER included; clearing also clears [unshared]. */
    fun setForTest(context: Context, modelId: String, bytes: Long) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            if (bytes > 0) putLong(modelId, bytes) else remove(modelId).remove(unsharedKey(modelId))
        }.apply()
    }

    /**
     * ⭐ Learn from the newest backend launch's log: true when it named a size
     * bigger than the one [modelId] already had, or showed the group could not
     * be made while sharing was still on — either is stored, so a relaunch is
     * worth making. False when there is nothing new to try, which is what stops
     * a retry loop.
     */
    fun learn(context: Context, modelId: String): Boolean {
        val spec = ModelCatalog.byId(modelId)
        if (envFor(spec?.family) == null) return false
        val log = BackendProcess.newestLaunch()
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val need = required(log)
        if (need != null && need > (stored(context, modelId) ?: 0L)) {
            prefs.edit().putLong(modelId, need).apply()
            return true
        }
        if (groupFailed(log) && !unshared(context, modelId)) {
            prefs.edit().putBoolean(unsharedKey(modelId), true).apply()
            return true
        }
        return false
    }
}
