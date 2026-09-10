package com.abrah.nightmare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ⭐ Runs a harness op with NOTHING ON SCREEN.
 *
 * ```
 * adb shell am start-foreground-service -n com.abrah.nightmare/.OpService --es op graph
 * adb logcat -s Harness:I Harness:W
 * ```
 *
 * ⚠ start-FOREGROUND-service, and the "foreground" is about process priority,
 * NOT about the screen: it posts a notification and takes no focus, no window
 * and no attention. A plain `am startservice` is refused outright with
 * "Error: app is in background uid null" (measured 2026-09-08) -- Android 12+
 * forbids background service starts, and the harness is only ever started while
 * the app IS in the background, which is the entire point of it.
 *
 * ⚠⚠ Why this exists. `am start … --es op …` works, but it FOREGROUNDS the app
 * over whatever the person holding the phone was doing -- and this is somebody's
 * actual phone, not a lab device. A 30 s executor check is not worth taking
 * their screen, and "ask first, then take the screen anyway" does not scale to a
 * loop that runs all day. This path costs them nothing: no window, no focus
 * change, no interruption.
 *
 * ⚠ The ops are [HarnessOps], shared verbatim with the buttons. A service with
 * its own copy would drift, and two front ends that disagree make a device
 * report unattributable.
 *
 * ⚠ The output goes to LOGCAT only, so `adb logcat -s Harness:I` is the whole
 * result. There is no in-app log to read afterwards -- the view model's list
 * belongs to the activity, which may not even be running.
 */
class OpService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * ⚠ Guards against a second op landing on top of a running one. Ops share
     * one backend with one generation mutex, so overlapping them would queue
     * inside the C++ server and report times that mean nothing.
     */
    private val busy = AtomicBoolean(false)

    private val ops = HarnessOps(this, object : HarnessOps.Sink {
        override fun say(text: String, bad: Boolean) {
            // ⚠ Same tag as the view model's, on purpose: a headless run and a
            // tapped run must produce comparable logs, or the two front ends
            // cannot be checked against each other.
            if (bad) Log.w(TAG, text) else Log.i(TAG, text)
        }
    })

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ⚠ The headless path never goes through MainActivity, so it must load
        // the selection itself -- otherwise a scripted op renders against
        // V1_MODEL while the UI and the backend use whatever the user picked.
        SelectedModel.load(this)
        // ⚠⚠ FIRST, before any early return. A service started with
        // startForegroundService() that does not call startForeground() within
        // ~5 s is killed with ForegroundServiceDidNotStartInTimeException --
        // including on the paths that reject the command, which would turn a
        // typo in an op name into a crash report about foreground services.
        goForeground()

        val op = intent?.getStringExtra(MainActivity.EXTRA_OP)
        if (op == null) {
            Log.w(TAG, "OpService started with no \"op\" extra -- nothing to run")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!busy.compareAndSet(false, true)) {
            Log.w(TAG, "op $op ignored -- already running")
            // ⚠ Does NOT stopSelf here: the op already in flight owns the
            // service's life, and stopping on the rejected command would kill
            // it mid-render.
            return START_NOT_STICKY
        }
        scope.launch {
            try {
                ops.run(op, intent.getStringExtra(EXTRA_ARG))
            } catch (e: Exception) {
                Log.w(TAG, "$op threw ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                busy.set(false)
                // ⚠ stopSelf(startId), not stopSelf(): the id form is a no-op
                // if another command has arrived since, which is what keeps a
                // queued op from being killed by the previous one finishing.
                stopSelf(startId)
            }
        }
        // ⚠ NOT_STICKY: a re-delivered op after a process death would re-render
        // for nobody, and this service exists to be driven explicitly.
        return START_NOT_STICKY
    }

    /**
     * The notification the platform requires in exchange for not killing us
     * while the screen belongs to someone else.
     *
     * ⚠ Deliberately unimportant: IMPORTANCE_LOW, no sound, no vibration. It is
     * the price of the process priority, not a thing the user is meant to act
     * on -- and on Android 13+ without POST_NOTIFICATIONS granted it is not
     * even shown, which does not affect the service at all.
     */
    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Harness ops", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Nightmare harness")
            .setContentText("running an op")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        /**
         * An op's argument, e.g. which model to install:
         * `--es op model_install --es arg qteamix`.
         *
         * ⚠ Optional, and ops that need one say so by name when it is absent.
         */
        const val EXTRA_ARG = "arg"

        private const val TAG = "Harness"
        private const val CHANNEL = "harness-ops"
        private const val NOTIFICATION_ID = 1
    }
}
