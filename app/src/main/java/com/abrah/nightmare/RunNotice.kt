package com.abrah.nightmare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * ⭐ A run's progress bar in the notification shade, like a download's ([DownloadNotice]; the user, 2026-11-14): shown
 * once a run has taken [SHOW_AFTER_MS], updated at most once a second, gone when it ends ([RunAlert] then says how it
 * went). ⚠ Cost: one `notify()` (a binder call to the system, well under a millisecond) per second at most, against
 * seconds of NPU or GPU work a step; nothing measurable. A LOW channel: no sound, no pop-up, no buzz on update.
 */
object RunNotice {

    private const val CHANNEL = "run_progress"
    private const val ID = 4301

    /** ⚠ Quick renders never flash a bar: only a run past this shows one. */
    const val SHOW_AFTER_MS = 10_000L

    @Volatile private var shown = false
    private var lastText: String? = null

    /** ⭐ The shade's line for a run: what runs, its step label and the time so far. Pure, for the tests. */
    internal fun textFor(step: String?, elapsedMs: Long): String =
        listOfNotNull(step, RunAlert.formatTook(elapsedMs)).joinToString(" · ")

    /**
     * Show or update the bar. [fraction] 0..1, or null while nothing is counted (loading, a phase with no total): the
     * bar then moves without a number rather than sitting at zero, which reads as stalled.
     */
    fun progress(ctx: Context, title: String, text: String, fraction: Float?) {
        if (shown && text == lastText) return
        val nm = manager(ctx) ?: return
        val pct = fraction?.let { (it * 100).toInt().coerceIn(0, 100) }
        val open = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setProgress(100, pct ?: 0, pct == null)
            .setContentIntent(open)
            .build()
        if (runCatching { nm.notify(ID, n) }.isSuccess) { shown = true; lastText = text }
    }

    /** ⭐ The run ended (or was cancelled): the bar goes. */
    fun clear(ctx: Context) {
        if (!shown) return
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(ID) }
        shown = false
        lastText = null
    }

    private fun manager(ctx: Context): NotificationManager? = runCatching {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return null
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.run_notice_channel), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
            )
        }
        nm
    }.getOrNull()
}
