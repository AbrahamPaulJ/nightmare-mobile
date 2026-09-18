package com.abrah.nightmare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * ⭐⭐ Exists for ONE reason: while this is running, Android will not kill this
 * app's process to reclaim memory just because it left the foreground.
 *
 * ⚠⚠ **The gap this closes.** `BackendProcess` execs the native backend as a
 * plain child process from inside the ViewModel — it has no Android lifecycle
 * of its own, so to the OS it is just more memory held by an app that is no
 * longer in front. A backgrounded app holding ~1-3 GB for a resident SD/SDXL
 * pipeline is exactly what the low-memory killer reaches for first, and this
 * device's OEM (Samsung One UI) is known to be more aggressive about it than
 * stock Android. DreamUI's own `BackendService.kt` avoids this by running the
 * backend INSIDE a real foreground `Service` — `BackendProcess.kt`'s header
 * comment claims to follow that "almost verbatim", but only the process-launch
 * mechanics made the trip, not the Service/foreground half. User report,
 * 2026-09-18: the process dies in the background roughly 4 times in 10, and
 * the workflow/results are gone on return.
 *
 * ⇒ Rather than moving `BackendProcess`'s launch/relaunch/scheduler logic
 * into a Service body — real surgery on code `docs/ARCHITECTURE.md` §4
 * already measured carefully — this is a SEPARATE, minimal Service whose only
 * job is to hold `startForeground()` up for as long as a backend is resident.
 * Android's OOM adjustment is per PROCESS, not per component: any started
 * foreground service in this process is enough to keep the whole process
 * (and the native child hanging off it) out of the background-kill tier.
 * [BackendProcess] starts and stops it around its own start/stop/monitor.
 *
 * ⚠ Deliberately unimportant, same as [OpService]'s: `IMPORTANCE_LOW`, no
 * sound, a system icon — the price of the process priority, not a thing the
 * user is meant to act on.
 */
class BackendKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ⚠ FIRST, before anything else can throw — same trap OpService's own
        // comment names: a service started with startForegroundService() that
        // does not call startForeground() within ~5 s is killed outright.
        val n: Notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
        // ⚠ STICKY: unlike OpService, this one is not driven by a single
        // command — it exists for as long as a backend is resident, and if
        // the OS restarts it after reclaiming it anyway there is nothing to
        // redo, only the priority to reassert.
        return START_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Model loaded", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Nightmare")
            .setContentText("keeping a loaded model in memory")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "backend-keepalive"
        private const val NOTIFICATION_ID = 2

        /** ⚠ Idempotent: starting an already-started foreground service is a no-op. */
        fun start(context: Context) {
            val intent = Intent(context, BackendKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BackendKeepAliveService::class.java))
        }
    }
}
