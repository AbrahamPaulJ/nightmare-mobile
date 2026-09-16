package com.abrah.nightmare.npu

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * ⭐⭐ Can this phone run video at all — asked ONCE, at first launch, and
 * remembered. The user's call, 2026-09-17: *hide it everywhere* on a phone that
 * cannot — no Video node in Add node, no video flows, no Video tab in Models.
 *
 * ⚠ The answer is the CANARY's ([NpuCanary]), a real 58 KB context binary run
 * in-process, never a chip allowlist (`docs/NEODRAGON.md` §4). Permissive like
 * the canary: only a real refusal hides video; "could not tell" shows it.
 *
 * ⚠⚠ **A crash during the probe counts as a refusal.** The probe loads QNN into
 * THIS process at launch; if that kills the app, a probe run again next launch
 * kills it again, forever. So "probing" is written BEFORE it runs and replaced
 * after — a launch that finds "probing" left behind knows the last one died in
 * the probe and records "no" without running it again.
 *
 * ⚠ Keyed by `versionCode`, so an app update that fixes the runtime gets to ask
 * again. A phone's chip does not change; the code judging it does.
 */
object VideoGate {

    private const val FILE = "nightmare.prefs"
    private const val PROBING = "probing"

    /** ⚠ The ONE type and the recipe ids that hidden-video removes. */
    const val VIDEO_TYPE = "nd.sample"
    val VIDEO_RECIPES = setOf("t2v", "i2v")

    /** Null until known; the UI shows video while null (permissive). */
    var supported by mutableStateOf<Boolean?>(null)
        private set

    /** ⚠ True only on a definite NO. Every hide asks this, never `supported == false` by hand. */
    val hidden: Boolean get() = supported == false

    private fun key(versionCode: Int) = "video_gate_$versionCode"

    fun load(context: Context, versionCode: Int) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        supported = when (prefs.getString(key(versionCode), null)) {
            "yes" -> true
            "no" -> false
            PROBING -> {
                // The last launch died inside the probe — see the class note.
                prefs.edit().putString(key(versionCode), "no").apply()
                false
            }
            else -> null
        }
    }

    /**
     * Run the canary if this version has no answer. ⚠ Blocking, seconds — off
     * the main thread.
     */
    fun probeIfNeeded(context: Context, versionCode: Int, runner: () -> NpuCanary.Result?) {
        if (supported != null) return
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.edit().putString(key(versionCode), PROBING).commit()
        val ok = runner()?.canDownload ?: true
        prefs.edit().putString(key(versionCode), if (ok) "yes" else "no").commit()
        supported = ok
    }
}
