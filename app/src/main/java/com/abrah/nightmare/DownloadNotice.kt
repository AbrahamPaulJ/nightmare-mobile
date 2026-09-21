package com.abrah.nightmare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * ⭐⭐ A model download, in the notification shade.
 *
 * ⚠⚠ **Because a 3.7 GB download outlives the screen.** The Models tab draws a
 * progress bar, and that bar is only on screen while the tab is. A user starts a
 * checkpoint, locks the phone, and has no way to tell whether it is still going,
 * finished, or died — which is the state this app has had since the downloader
 * was written. Asked for 2026-09-15.
 *
 * ⚠ Not a foreground service. The download already runs in the ViewModel's
 * scope, and promoting it would be a bigger change than the problem: this is a
 * READOUT, and if the process dies the notification goes with it, which is
 * honest — the download died too.
 *
 * ⚠⚠ Android 13+ needs POST_NOTIFICATIONS. Ungranted, `notify` is a silent
 * no-op — so nothing here may be load-bearing, and nothing is: the tab's own
 * bar is still the source of truth.
 */
object DownloadNotice {

    private const val CHANNEL = "downloads"
    private const val ID = 4201

    /**
     * ⭐⭐⭐ **True while an ONGOING row is in the shade**, so a caller that
     * forgot to end one can be caught rather than trusted.
     *
     * ⚠⚠⚠ The bug, 2026-09-21: seven paths call [progress] and only five
     * called [done] or [clear] — the two that did not were both IMPORTS, so a
     * Z-Image custom import left "importing" pinned in the shade forever.
     * `setOngoing(true)` means the user cannot even swipe it away. Reported from
     * the phone, and it is the N−1-of-N rule again (`docs/ARCHITECTURE.md`
     * §5.6): the missed place is the one someone opens first.
     *
     * ⚠⚠ Fixing the two call sites is not enough, because the next installer
     * added will be an eighth place that has to remember. [HarnessViewModel]'s
     * `refreshModels` clears a live row whenever nothing is installing, which
     * makes the whole class of mistake self-correcting — every one of those
     * paths already calls it on the way out.
     *
     * ⚠ [done] leaves a row in the shade on purpose and is NOT live: it is
     * dismissible, and the outcome is the thing the user asked to see.
     */
    @Volatile
    var live: Boolean = false
        private set

    private fun manager(ctx: Context): NotificationManager? = runCatching {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                // ⚠ LOW: a download that pinged and buzzed on every percent
                // would be the last time anyone left notifications on.
                NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
        nm
    }.getOrNull()

    /**
     * Show or update the bar.
     *
     * @param fraction 0..1, or null for a phase with nothing to count (unpacking
     *   a zip reports no total until it has read the directory).
     */
    fun progress(ctx: Context, label: String, phase: String, fraction: Float?) {
        val nm = manager(ctx) ?: return
        val pct = fraction?.let { (it * 100).toInt().coerceIn(0, 100) }
        val n = Notification.Builder(ctx, CHANNEL)
            .setContentTitle(label)
            // ⚠ The PHASE, not just a percentage: "unpacking" at 100% of the
            // download is not "done", and a bar sitting full for two minutes
            // reads as a hang.
            .setContentText(if (pct != null) "$phase · $pct%" else phase)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            // ⚠ Indeterminate when there is no total, rather than a bar pinned
            // at zero — which reads as stalled.
            .setProgress(100, pct ?: 0, pct == null)
            .build()
        if (runCatching { nm.notify(ID, n) }.isSuccess) live = true
    }

    /**
     * ⭐⭐ Replace the bar with the outcome, and let it be dismissed.
     *
     * ⚠⚠ `setOngoing(false)` and a NEW notification rather than a cancel: a
     * download that simply vanished from the shade is indistinguishable from one
     * that was killed. The user asked for this half specifically — *"handle it
     * correctly when it finishes as well"*.
     */
    fun done(ctx: Context, label: String, ok: Boolean, detail: String? = null) {
        val nm = manager(ctx) ?: return
        val n = Notification.Builder(ctx, CHANNEL)
            .setContentTitle(label)
            .setContentText(detail ?: if (ok) "ready to use" else "download failed")
            .setSmallIcon(
                if (ok) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        // ⚠ Cleared FIRST: this row is dismissible, so it is no longer
        // something the backstop has to tidy up, even if `notify` throws.
        live = false
        runCatching { nm.notify(ID, n) }
    }

    /** ⚠ For a cancel: there is no outcome to report, so the row goes. */
    fun clear(ctx: Context) {
        live = false
        runCatching { manager(ctx)?.cancel(ID) }
    }
}
