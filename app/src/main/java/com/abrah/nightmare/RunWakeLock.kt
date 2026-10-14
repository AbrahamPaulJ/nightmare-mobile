package com.abrah.nightmare

import android.content.Context
import android.os.PowerManager

/**
 * ⭐ Keeps the CPU awake while a graph runs, so a render does not PAUSE when the screen goes off.
 *
 * ⚠⚠ Why (a tester, 2026-10-13, an Anima DiT render on a tablet): "took over 10 minutes, it says
 * 4 mins". The foreground service keeps the process alive but not the CPU awake, so with the
 * screen off the phone slept and the render waited. A PARTIAL lock: the screen still turns off.
 * ⚠ Counted, so a nested run (a batch calling [HarnessOps.runWorkflow] per item) holds one lock;
 * ⚠ capped at [MAX_MS] so a hung run cannot drain the battery overnight.
 */
object RunWakeLock {
    private const val MAX_MS = 60 * 60 * 1000L
    private var lock: PowerManager.WakeLock? = null
    private var holders = 0

    @Synchronized
    fun begin(context: Context) {
        holders++
        if (holders > 1) return
        runCatching {
            val pm = context.getSystemService(PowerManager::class.java) ?: return
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nightmare:run").apply {
                setReferenceCounted(false)
                acquire(MAX_MS)
            }
        }
    }

    @Synchronized
    fun end() {
        if (holders == 0) return
        holders--
        if (holders > 0) return
        runCatching { lock?.takeIf { it.isHeld }?.release() }
        lock = null
    }
}
