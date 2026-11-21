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
 *
 * ⚠⚠ **A chip KNOWN to be below v79 is a NO without the canary** ([belowFloor]), the rule every flow already
 * follows ([com.abrah.nightmare.canvas.Recipe.runsOnDevice]): the arch is a floor, an unknown chip still asks the
 * canary. GitHub #122: a POCO F7 (SM8735, v73 in the table) was offered Neodragon, installed 8 GB and failed at
 * `load cliplp_v79.bin`; its canary could not tell, and "could not tell" shows video.
 */
object VideoGate {

    private const val FILE = "nightmare.prefs"
    private const val PROBING = "probing"

    /** ⚠ The ONE type and the recipe ids that hidden-video removes. */
    const val VIDEO_TYPE = "nd.sample"
    // ⚠ None since 2026-10-15: the one Video flow opens on Wan, and Neodragon is a choice on its node
    // (hidden there when this gate hides it).
    val VIDEO_RECIPES = emptySet<String>()

    /** Null until known; the UI shows video while null (permissive). */
    var supported by mutableStateOf<Boolean?>(null)
        private set

    /** ⚠ True only on a definite NO. Every hide asks this, never `supported == false` by hand. */
    val hidden: Boolean get() = supported == false || floorNo

    /** Set by [load] from [com.abrah.nightmare.DeviceProbe.caps], once: a getter Compose reads must not read /proc. */
    private var floorNo = false

    /** ⭐ Pure, for the test: a chip known to be below the video contexts' arch ([ModelCatalog.DIT_MIN_ARCH]). */
    fun belowFloor(caps: com.abrah.nightmare.DeviceProbe.Caps): Boolean =
        caps.known && !caps.supportsArch(com.abrah.nightmare.ModelCatalog.DIT_MIN_ARCH)

    private fun key(versionCode: Int) = "video_gate_$versionCode"

    fun load(context: Context, versionCode: Int) {
        floorNo = runCatching { belowFloor(com.abrah.nightmare.DeviceProbe.caps()) }.getOrDefault(false)
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
     * the main thread. ⚠ Synchronized: launch and the Models screen both ask, and two
     * canaries in-process at once is the crash the gate exists to avoid.
     */
    @Synchronized
    fun probeIfNeeded(context: Context, versionCode: Int, runner: () -> NpuCanary.Result?) {
        if (supported != null) return
        // ⭐ No canary on a chip known to be below the floor: QNN would load into this process for a known answer.
        if (floorNo) { supported = false; return }
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.edit().putString(key(versionCode), PROBING).commit()
        val ok = runner()?.canDownload ?: true
        prefs.edit().putString(key(versionCode), if (ok) "yes" else "no").commit()
        supported = ok
    }
}
