package com.abrah.nightmare.segment

import android.os.Handler
import android.os.Looper

/**
 * ⭐⭐ **A held model is let go once it sits unused** — the segmenter's and the
 * parser's. Both used to be opened on first use and held for the life of the
 * process, beside a checkpoint that may want 65% of the phone's RAM
 * (`docs/DEVICES.md` §6). The user's ask, 2026-09-24: *"check how ur
 * offloading models for segmenting"*.
 *
 * ⚠⚠ Only the MODEL goes. The cached results — a tap's regions, a photo's
 * label map — stay, so every preview and every render that resolves from the
 * cache is unaffected; only the next NEW tap or pick pays the reopen.
 *
 * ⚠⚠⚠ [begin] and the release take the SAME lock, and a use is counted before
 * its model is fetched. So a release either happens before a use starts — and
 * the use reopens the model — or waits until it ends. It can never close a
 * model a tap is running on.
 */
internal class IdleRelease(private val idleMs: Long, private val release: () -> Unit) {
    // ⚠ LAZY: built at first use, not when Segmenter or Parser loads. Built
    // eagerly it needed Android's main looper to exist just to name a target,
    // and every plain-JVM test of the parser's tables failed to load the class.
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var users = 0
    private val task = Runnable { releaseNow() }

    fun begin() {
        synchronized(this) { users++ }
        handler.removeCallbacks(task)
    }

    fun end() {
        synchronized(this) { users-- }
        handler.removeCallbacks(task)
        handler.postDelayed(task, idleMs)
    }

    /** ⭐ Now, unless something is using it — [android.content.ComponentCallbacks2.onTrimMemory]. */
    fun releaseNow() = synchronized(this) {
        if (users == 0) release()
    }

    // ⚠ The handler is only touched by begin/end; a trim before any use has
    // nothing to cancel.

    companion object {
        /** ⚠ Long enough for a run of taps on one photo; short beside a render. */
        const val IDLE_MS = 60_000L
    }
}
