package com.abrah.nightmare.api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.abrah.nightmare.R
import com.abrah.nightmare.SelectedModel
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * ⭐⭐ Holds the API's server ([MiniHttp] → [NightmareApi]) while Settings → API is on.
 *
 * ⚠ A FOREGROUND service, and its notification is the point as much as the priority: a
 * phone listening on the network says so, with the address and a Stop button
 * (`docs/AGENT-API.md` §5). Started by the Settings switch and, when it was left on, at app
 * start ([syncWith]).
 */
class ApiService : Service() {

    private var server: MiniHttp? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (intent?.action == ACTION_STOP) {
            ApiSettings.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (server == null) {
            SelectedModel.load(this)
            val api = NightmareApi(applicationContext)
            server = MiniHttp(ApiSettings.PORT, api::handle).also {
                try {
                    it.start()
                } catch (e: Exception) {
                    android.util.Log.w("NmApi", "the API could not listen on ${ApiSettings.PORT}: ${e.message}")
                    server = null
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            goForeground()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.api_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ApiService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.api_listening_title))
            .setContentText(addresses().joinToString("  ") { "$it:${ApiSettings.PORT}" }.ifEmpty { getString(R.string.api_no_network) })
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.api_stop), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "api"
        private const val NOTIFICATION_ID = 8820
        private const val ACTION_STOP = "com.abrah.nightmare.api.STOP"

        /** ⭐ Start or stop the service to match the setting. */
        fun syncWith(ctx: Context) {
            val i = Intent(ctx, ApiService::class.java)
            if (ApiSettings.enabled(ctx)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
            } else {
                ctx.stopService(i)
            }
        }

        /** ⭐ The addresses a client on the LAN / tailnet would use — what Settings and the notification show. */
        fun addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { MiniHttp.isLocalPeer(it) }
                .map { it.hostAddress.orEmpty() }
                .distinct()
        }.getOrDefault(emptyList())
    }
}
