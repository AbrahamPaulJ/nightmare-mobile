package com.abrah.nightmare

import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * ⭐⭐ **A resident checkpoint is let go once the app has been out of sight and
 * idle for [GRACE_MS]** — the backend's sibling of `segment/IdleRelease`, which
 * does the same for the segmenter and the parser.
 *
 * ⚠⚠ The report this exists for, 2026-09-28: *"after leaving the app, 10GB of
 * RAM remains occupied and is not cleaned"* (FLUX.2). By design a checkpoint
 * under [Residency]'s 65% stays loaded between Runs — and [BackendKeepAliveService]
 * (2026-09-18, the app being killed in the background 4 times in 10) holds the
 * whole process at foreground priority for as long as one is loaded. Together
 * that meant a FLUX.2 backend held ~6 GB of weights plus NPU buffers for as long
 * as the user stayed away, and Android was not allowed to take it back.
 *
 * ⇒ Keep both halves of what those two decisions bought, and bound them: the
 * keep-alive still covers a render in progress and a quick trip to the gallery,
 * and after [GRACE_MS] out of sight with nothing running, [BackendProcess.stop]
 * gives the memory back — which also stops the keep-alive, so the small app
 * process is left as an ordinary background app. The cost is one reload on the
 * next Run (~3–5 s; the weights are page-cached).
 *
 * ⚠⚠⚠ **Never during a render.** [begin]/[end] bracket every run
 * (`HarnessOps.runWorkflow`, and each headless `OpService` op), and the release
 * takes the same lock, so it either happens before a run starts — and the run
 * relaunches the backend — or waits for it to end. A run that ends while the app
 * is still hidden re-arms the timer.
 *
 * ⚠ Only the app going out of SIGHT arms it (`onTrimMemory(UI_HIDDEN)`), never
 * plain idleness: a model resident while the canvas is open is the point of
 * [Residency]. A process started headless by `OpService` is never marked hidden,
 * so a scripted session keeps its backend between ops exactly as before.
 */
object BackendIdle {
    /**
     * ⚠ Long enough for a trip to the photo picker (an external activity, so it
     * hides this UI) or a glance at another app; short beside the time a person
     * who has left stays away. The segmenters' `IdleRelease.IDLE_MS`, same reasoning.
     */
    const val GRACE_MS = 60_000L

    private const val TAG = "BackendIdle"

    // ⚠ Its OWN thread, not the main looper: [BackendProcess.stop] waits for the
    // process to be gone (bounded, but seconds), and it runs under this object's
    // lock so a run's [begin] waits for the stop instead of racing it.
    private val handler by lazy {
        Handler(HandlerThread("backend-idle").apply { start() }.looper)
    }
    private var hidden = false
    private var users = 0
    private val task = Runnable { releaseIfIdle("out of sight for ${GRACE_MS / 1000} s") }

    /** The app's UI left the screen. */
    fun hidden() {
        synchronized(this) { hidden = true }
        handler.removeCallbacks(task)
        handler.postDelayed(task, GRACE_MS)
    }

    /** The app is back in front: whatever is resident stays. */
    fun visible() {
        synchronized(this) { hidden = false }
        handler.removeCallbacks(task)
    }

    /** ⭐ Memory pressure while hidden: no grace period. */
    fun pressure() {
        handler.removeCallbacks(task)
        handler.post { releaseIfIdle("memory pressure while out of sight") }
    }

    fun begin() {
        synchronized(this) { users++ }
        handler.removeCallbacks(task)
    }

    fun end() {
        val rearm = synchronized(this) {
            users = maxOf(0, users - 1)
            hidden && users == 0
        }
        if (rearm) {
            handler.removeCallbacks(task)
            handler.postDelayed(task, GRACE_MS)
        }
    }

    /** ⚠ Hidden, nothing running, and something actually resident — all three. */
    private fun releaseIfIdle(why: String) = synchronized(this) {
        if (!hidden || users > 0) return@synchronized
        val key = BackendProcess.launchedKey
        if (key == null && !BackendProcess.upscalerServer) return@synchronized
        Log.i(TAG, "releasing ${key?.model ?: "upscaler"} backend: $why")
        BackendProcess.stop()
    }

    /** ⚠ Tests only. */
    internal fun resetForTest() = synchronized(this) { hidden = false; users = 0 }
}
