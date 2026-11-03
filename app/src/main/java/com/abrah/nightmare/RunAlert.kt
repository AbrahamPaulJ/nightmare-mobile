package com.abrah.nightmare

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri

/**
 * ⭐⭐ A sound, and a notification when the app is out of sight, when a run ends (GitHub #79: "constantly checking my
 * phone for long generations is rough"). Opt-in ([Prefs.runSound]); each outcome has its own sound, picked with the
 * system's sound picker (an alarm, a bell, any notification sound), or the phone's default notification sound.
 *
 * ⚠ Only a run of [MIN_MS] or longer: a 3 s SD 1.5 render, run again and again, would chime on every press.
 * ⚠ A cancelled run says nothing: the person who pressed Cancel is looking at it.
 * ⚠ The notification is silent ([CHANNEL] has no sound): the sound is played here, so the chosen one plays whatever
 * the channel says, and a channel's sound cannot be changed after it is made. Without POST_NOTIFICATIONS the
 * notification is dropped and the sound still plays.
 */
object RunAlert {

    /** ⭐ Runs shorter than this end quietly. */
    const val MIN_MS = 15_000L

    private const val CHANNEL = "run_end"
    private const val ID = 4301

    /** ⚠ Pure: whether a run that ended this way chimes. */
    internal fun chimes(on: Boolean, ms: Long, cancelled: Boolean): Boolean = on && !cancelled && ms >= MIN_MS

    /**
     * ⭐ Called once when a canvas run or a batch ends. [failure] is the reason a person reads, null when it worked.
     * Never throws: an alert must not be the thing that breaks the end of a run.
     */
    fun ended(ctx: Context, ms: Long, failure: String?, cancelled: Boolean = false) {
        if (!chimes(Prefs.runSound, ms, cancelled)) return
        runCatching { play(ctx, if (failure == null) Prefs.runSoundDone(ctx) else Prefs.runSoundFailed(ctx)) }
        if (!inFront()) runCatching { notify(ctx, ms, failure) }
    }

    /** ⭐ The sound to play: the chosen one, or the phone's default notification sound when none is chosen. */
    internal fun soundFor(chosen: String?): Uri =
        chosen?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    fun play(ctx: Context, chosen: String?) {
        val r = RingtoneManager.getRingtone(ctx, soundFor(chosen)) ?: return
        r.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        r.play()
    }

    /** ⭐ A sound's name for Settings ("Default" when none is chosen). */
    fun nameOf(ctx: Context, chosen: String?): String =
        if (chosen.isNullOrBlank()) ctx.getString(R.string.run_sound_default)
        else runCatching { RingtoneManager.getRingtone(ctx, Uri.parse(chosen))?.getTitle(ctx) }.getOrNull()
            ?: ctx.getString(R.string.run_sound_default)

    /** ⚠ In front = an activity of ours on screen; the keep-alive service alone ranks lower. */
    private fun inFront(): Boolean = runCatching {
        ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance <=
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(true)

    private fun notify(ctx: Context, ms: Long, failure: String?) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.run_end_channel), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { setSound(null, null) },
        )
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val took = formatTook(ms)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(if (failure == null) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(ctx.getString(if (failure == null) R.string.run_end_done else R.string.run_end_failed, took))
            .apply { if (failure != null) setContentText(failure).setStyle(Notification.BigTextStyle().bigText(failure)) }
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(ID, n)
    }

    /** ⚠ Pure: "45 s", "2 min 13 s", "1 h 4 min". */
    internal fun formatTook(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min ${s % 60} s"
            else -> "${s / 3600} h ${s % 3600 / 60} min"
        }
    }
}
