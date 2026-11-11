package com.flygonlc.onebattery

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
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps sampling current often enough to coulomb-count, and shows a live status notification. */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var lastText = ""
    private var lastNotifyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Starting…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_STICKY
    }

    private suspend fun run() {
        val db = BatteryDb.get(this)
        val pm = getSystemService(PowerManager::class.java)
        // Charging power is free, so stay awake while plugged in; otherwise CPU sleep leaves
        // gaps in the current integration and the measurement gets rejected.
        val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OneBattery:measure").apply { setReferenceCounted(false) }
        while (scope.isActive) {
            val s = BatteryReader.read(this)
            if (s?.plugged != 0) wake.acquire(15 * 60_000L) else if (wake.isHeld) wake.release()
            if (s != null) {
                CoulombCounter.onReading(this, s)
                DrainTracker.onReading(this, s, pm.isInteractive)
                if (s.time - db.lastSampleTime() > 5 * 60_000L) db.insert(s)
                updateNotification(s, pm.isInteractive)
            }
            // Fast while plugged in or in use (when it matters); slower otherwise to save power.
            delay(if (s?.plugged != 0 || pm.isInteractive) 10_000L else 30_000L)
        }
    }

    private fun updateNotification(s: BatterySnapshot, screenOn: Boolean) {
        if (!MonitorSettings.liveNotification(this)) {
            // Keep the FGS notification alive but stop rewriting it. One final static write
            // so the shade does not keep showing a stale "Starting…".
            if (lastText != STATIC_TEXT) {
                lastText = STATIC_TEXT
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(STATIC_TEXT))
            }
            return
        }
        if (!screenOn && MonitorSettings.screenOnOnly(this)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifyAt < MonitorSettings.intervalMs(this)) return
        val power = s.powerW?.let { " · ${if (s.isCharging) "+" else "−"}${"%.1f".format(it)} W" } ?: ""
        val text = "${s.level}% · ${BatteryReader.statusLabel(s.status)}$power · ${"%.0f".format(s.tempC)}°C"
        if (text == lastText) return
        lastText = text
        lastNotifyAt = now
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Battery monitor", NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle("OneBattery")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        CoulombCounter.finish(this)
        DrainTracker.flush(this)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "monitor"
        private const val NOTIF_ID = 1
        private const val STATIC_TEXT = "Monitoring"

        /** Safe to call from anywhere; background starts may be refused by the OS. */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, MonitorService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
