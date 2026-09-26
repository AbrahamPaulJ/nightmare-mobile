package com.abrah.nightmare

/**
 * ⭐ Kept like `NmPreview`: how long each piece of picture work takes, and on
 * which thread, so a lag report is answered by `adb logcat -s NmPerf` rather
 * than by reading the code a fourth time (`CLAUDE.md`: instrument the phone).
 */
object Perf {
    const val TAG = "NmPerf"

    /** ⭐ When the Models tab was asked for — [markModelsOpened]; 0 when not pending. */
    @Volatile var modelsOpenAt = 0L

    fun markModelsOpened() { modelsOpenAt = System.nanoTime() }

    /** ⚠ Called once the Models screen has drawn a frame: logs tap → frame. */
    fun modelsDrawn() {
        val t = modelsOpenAt
        if (t == 0L) return
        modelsOpenAt = 0L
        android.util.Log.i(TAG, "Models tab first frame ${(System.nanoTime() - t) / 1_000_000} ms after the tap")
    }
}
