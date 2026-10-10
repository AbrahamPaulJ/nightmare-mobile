package com.abrah.nightmare

import java.util.concurrent.atomic.AtomicReference

/**
 * ⭐⭐ One render at a time in this process — the canvas's Run (`HarnessViewModel.run`) and the
 * API's `/run` (`api/NightmareApi.kt`) share one backend with one generation mutex, and a
 * second graph landing mid-render would also relaunch it for another checkpoint.
 *
 * ⚠ A try-lock, never a wait: whoever loses is told who holds it ("the API is rendering"),
 * which beats a Run button that sits there for a minute for no visible reason.
 */
object RunLock {
    private val holder = AtomicReference<String?>(null)

    /** Who holds it ("canvas", "api"), or null. */
    val heldBy: String? get() = holder.get()

    /**
     * ⚠ [reentrant] for the canvas only: its own `busy` flag already stops a second Run, and a
     * view model whose run job never finished (a JVM test) must not lock out the next one.
     * The API and the agent are NOT reentrant — two `/run`s at once must be refused.
     */
    fun tryAcquire(who: String, reentrant: Boolean = false): Boolean =
        holder.compareAndSet(null, who) || (reentrant && holder.get() == who)

    fun release(who: String) {
        holder.compareAndSet(who, null)
    }
}
