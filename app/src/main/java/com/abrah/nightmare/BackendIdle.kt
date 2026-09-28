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
    // ⚠⚠ Volatile flags, NOT the lock, for everything the MAIN thread touches
    // ([hidden], [visible], [exited]). The lock is held across
    // [BackendProcess.stop] — up to 5 s (SIGTERM, then SIGKILL after 3) — and
    // the first on-device test logged `dvm_lock_sample … main … exited …
    // stopNow` at 98 ms: a slower stop there is an ANR (2026-09-28). The lock
    // stays for [users] and for [begin], which SHOULD wait for a stop in progress.
    @Volatile private var hidden = false
    private var users = 0
    /** ⭐ Set by [exited]: no grace period, release as soon as nothing is running. */
    @Volatile private var exiting = false
    private val task = Runnable { releaseIfIdle("out of sight for ${GRACE_MS / 1000} s") }

    /** The app's UI left the screen. */
    fun hidden() {
        hidden = true
        val now = exiting
        handler.removeCallbacks(task)
        if (!now) handler.postDelayed(task, GRACE_MS)
        Log.i(TAG, "hidden: release in ${GRACE_MS / 1000} s unless back or running")
    }

    /** The app is back in front: whatever is resident stays. */
    fun visible() {
        hidden = false
        exiting = false
        handler.removeCallbacks(task)
        Log.i(TAG, "visible: release cancelled")
    }

    /**
     * ⭐⭐ The user CLOSED the app — swiped it from recents, or backed out of it —
     * rather than stepping away: release now, with no grace period.
     *
     * ⚠⚠ The report, 2026-09-28: *"why do I have to force stop the app after I
     * remove it from recent apps for it to offload the model?"* The log showed a
     * swipe at 21:25:35 and a force-stop at 21:25:50 — the backend was alive and
     * waiting out the [GRACE_MS] meant for a trip to another app, with
     * [BackendKeepAliveService] holding the process up. A swipe is not a trip.
     *
     * ⚠⚠ **Mid-render too — the one exception to "never during a render".**
     * First version waited for the run's [end], and the user's next report was
     * *"loaded model, cancelled mid run, removed app from recents, still 10 GB
     * active"*: the log had `Job was cancelled` 31 s after the swipe, because
     * the run sat in a blocking backend read until the render finished by
     * itself (Cancel had not stopped it). A closed app has abandoned its render,
     * so waiting for it only holds the RAM. Stepping away (the [GRACE_MS] path)
     * still never interrupts one. The run itself is cancelled with the
     * ViewModel; its read fails when the process goes, and it ends.
     */
    fun exited(why: String) {
        hidden = true
        exiting = true
        handler.removeCallbacks(task)
        handler.post { stopNow(why) }
        Log.i(TAG, "exited ($why): release now")
    }

    /**
     * ⚠ [exited] only: ignores a run in flight, never ignores what is resident.
     * ⚠ The stop runs OUTSIDE the lock: the abandoned run's [end] lands on the
     * main thread while the process is dying, and nothing here needs to wait.
     */
    private fun stopNow(why: String) {
        val key = BackendProcess.launchedKey
        if (key == null && !BackendProcess.upscalerServer) {
            Log.i(TAG, "not releasing ($why): nothing resident")
            return
        }
        val running = synchronized(this) { users > 0 }
        Log.i(TAG, "releasing ${key?.model ?: "upscaler"} backend: $why" +
            if (running) " — abandoning the run in flight" else "")
        BackendProcess.stop()
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
        val (rearm, now) = synchronized(this) {
            users = maxOf(0, users - 1)
            (hidden && users == 0) to exiting
        }
        if (rearm) {
            handler.removeCallbacks(task)
            // ⚠ After [exited], the run that held the release off was the last
            // reason to keep the model: go now, not a grace period later.
            if (now) handler.post { releaseIfIdle("the app was closed during a run") }
            else handler.postDelayed(task, GRACE_MS)
        }
    }

    /** ⚠ Hidden, nothing running, and something actually resident — all three. */
    private fun releaseIfIdle(why: String) = synchronized(this) {
        // ⚠ Every early return SAYS so: this path shipped in 1.6.049 and was never
        // once seen in a log, and a silent skip is indistinguishable from never
        // being called (2026-09-28).
        if (!hidden || users > 0) {
            Log.i(TAG, "not releasing ($why): hidden=$hidden users=$users")
            return@synchronized
        }
        val key = BackendProcess.launchedKey
        if (key == null && !BackendProcess.upscalerServer) {
            Log.i(TAG, "not releasing ($why): nothing resident")
            return@synchronized
        }
        Log.i(TAG, "releasing ${key?.model ?: "upscaler"} backend: $why")
        BackendProcess.stop()
    }

    /** ⚠ Tests only. */
    internal fun resetForTest() = synchronized(this) { hidden = false; users = 0; exiting = false }
}
