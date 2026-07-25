package com.dynocommand.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.dynocommand.app.DynoLink
import com.dynocommand.app.ui.MainActivity

/**
 * Foreground service that keeps the process — and therefore the DynoLink
 * connection + reconnect watchdog — alive while the app is backgrounded or the
 * screen is off. Holds a partial wake lock so the Bluetooth I/O keeps running,
 * and shows an ongoing notification reflecting the link status.
 */
class DynoConnectionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val updater = object : Runnable {
        override fun run() { updateNotification(); handler.postDelayed(this, 2000) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DynoLink.init(this)
        val mac = intent?.getStringExtra(EXTRA_MAC)
        if (!mac.isNullOrEmpty()) DynoLink.connect(mac) else DynoLink.resumeIfActive()

        startForegroundCompat()
        acquireWakeLock()
        handler.removeCallbacks(updater)
        handler.post(updater)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(updater)
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dyno:link").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Dyno link", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val tap = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Dyno Command")
            .setContentText(DynoLink.status)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification())
    }

    companion object {
        const val EXTRA_MAC = "mac"
        private const val CHANNEL = "dyno_link"
        private const val NOTIF_ID = 1001
    }
}
