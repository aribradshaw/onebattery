package com.flygonlc.onebattery

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Process
import kotlin.math.abs

/**
 * Attributes battery drain to apps. Android keeps real per-app battery data private to the system,
 * so this measures it instead:
 *  - Screen on: real current is integrated and charged to whichever app is in the foreground.
 *  - Screen off: the CPU sleeps between readings, so current can't be integrated. The drop in
 *    battery % over that time is charged to a single "screen off" bucket instead.
 * Only unplugged time counts.
 */
object DrainTracker {
    const val SCREEN_OFF = "screen_off"
    const val SCREEN_ON_OTHER = "screen_on_other"
    const val HOUR_MS = 3_600_000L
    private const val MAX_STEP_MS = 90_000L
    private const val MAX_IDLE_STEP_MS = 6 * HOUR_MS
    private const val FLUSH_MS = 5 * 60_000L
    private const val EVENT_LOOKBACK_MS = 3 * HOUR_MS

    private class Acc(var mah: Double = 0.0, var ms: Long = 0L)

    private val buffer = HashMap<Pair<Long, String>, Acc>()
    private var lastTime = 0L
    private var lastCurrent: Int? = null
    private var lastInteractive = false
    private var lastEventTime = 0L
    private var foreground: String? = null
    private var idleLevel: Int? = null
    private var lastFlush = 0L

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    @Synchronized
    fun onReading(context: Context, s: BatterySnapshot, interactive: Boolean) {
        if (s.plugged != 0 || s.status == BatteryManager.BATTERY_STATUS_CHARGING) {
            flush(context)
            lastTime = 0L
            lastCurrent = null
            idleLevel = null
            return
        }
        val prevTime = lastTime
        val prevCurrent = lastCurrent
        val wasInteractive = lastInteractive
        lastTime = s.time
        lastCurrent = s.currentMa
        lastInteractive = interactive
        val dt = if (prevTime > 0) s.time - prevTime else 0L

        val segments = foregroundSegments(context, prevTime, s.time)
        if (interactive) {
            idleLevel = null
            val current = s.currentMa
            if (wasInteractive && dt in 1..MAX_STEP_MS && prevCurrent != null && current != null) {
                val avgMa = (abs(prevCurrent) + abs(current)) / 2.0
                val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
                for ((pkg, ms) in segments) {
                    add(s.time, (if (locked) null else pkg) ?: SCREEN_ON_OTHER, avgMa * ms / HOUR_MS, ms)
                }
            }
        } else {
            val base = idleLevel
            if (base != null && s.level <= base) {
                val mah = if (s.level < base) (base - s.level) * capacity(context) / 100.0 else 0.0
                add(s.time, SCREEN_OFF, mah, dt.takeIf { it in 1..MAX_IDLE_STEP_MS } ?: 0L)
            }
            idleLevel = s.level
        }
        if (s.time - lastFlush > FLUSH_MS || wasInteractive != interactive) flush(context)
    }

    /** Splits (from, to] by which app was in front, reading any launches since the last call. */
    private fun foregroundSegments(context: Context, from: Long, to: Long): List<Pair<String?, Long>> {
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val since = lastEventTime.coerceAtLeast(to - EVENT_LOOKBACK_MS)
        val out = ArrayList<Pair<String?, Long>>()
        var cursor = from
        try {
            val events = usage.queryEvents(since, to)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
                if (from > 0 && e.timeStamp > cursor) {
                    out += foreground to e.timeStamp - cursor
                    cursor = e.timeStamp
                }
                foreground = e.packageName
            }
        } catch (_: Exception) {
            // Usage access missing or revoked: everything lands in SCREEN_ON_OTHER.
        }
        lastEventTime = to
        if (from > 0 && to > cursor) out += foreground to to - cursor
        return out
    }

    private fun add(time: Long, pkg: String, mah: Double, ms: Long) {
        if (mah <= 0 && ms <= 0) return
        val acc = buffer.getOrPut(time / HOUR_MS to pkg) { Acc() }
        acc.mah += mah
        acc.ms += ms
    }

    private fun capacity(context: Context): Int {
        val since = System.currentTimeMillis() - 60 * 24 * HOUR_MS
        val measured = Stats.capacity(BatteryDb.get(context).measurements().filter { it.time >= since })?.mah
        if (measured != null) return measured
        val saved = context.getSharedPreferences("onebattery", Context.MODE_PRIVATE).getInt("design_mah", 0)
        return saved.takeIf { it > 0 } ?: BatteryReader.designCapacityMah(context)
    }

    @Synchronized
    fun flush(context: Context) {
        lastFlush = System.currentTimeMillis()
        if (buffer.isEmpty()) return
        BatteryDb.get(context).addDrain(buffer.map { (k, v) -> DrainEntry(k.first, k.second, v.mah, v.ms) })
        buffer.clear()
    }

    /** Foreground-service time and background data per app, from the OS's own records. */
    private fun backgroundUse(context: Context, from: Long, to: Long): List<BackgroundUse> {
        if (!hasUsageAccess(context)) return emptyList()
        val serviceMs = HashMap<String, Long>()
        try {
            context.getSystemService(UsageStatsManager::class.java).queryAndAggregateUsageStats(from, to)
                .forEach { (pkg, stats) -> serviceMs[pkg] = stats.totalTimeForegroundServiceUsed }
        } catch (_: Exception) {
        }
        val bytes = HashMap<String, Long>()
        val pm = context.packageManager
        val net = context.getSystemService(NetworkStatsManager::class.java)
        for (type in listOf(ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_WIFI)) {
            try {
                net.querySummary(type, null, from, to).use { stats ->
                    val b = NetworkStats.Bucket()
                    while (stats.hasNextBucket()) {
                        stats.getNextBucket(b)
                        if (b.state != NetworkStats.Bucket.STATE_DEFAULT || b.uid < 0) continue
                        val pkg = pm.getPackagesForUid(b.uid)?.firstOrNull() ?: continue
                        bytes[pkg] = (bytes[pkg] ?: 0L) + b.rxBytes + b.txBytes
                    }
                }
            } catch (_: Exception) {
            }
        }
        return (serviceMs.keys + bytes.keys)
            .map { BackgroundUse(it, serviceMs[it] ?: 0L, bytes[it] ?: 0L) }
            .filter { it.serviceMs >= 60_000L || it.bytes >= 1_000_000L }
            .sortedWith(compareByDescending<BackgroundUse> { it.serviceMs }.thenByDescending { it.bytes })
    }

    private fun label(context: Context, pkg: String): String = when (pkg) {
        SCREEN_OFF -> "Screen off / idle"
        SCREEN_ON_OTHER -> "Lock screen & unknown"
        else -> try {
            val pm = context.packageManager
            pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    fun report(context: Context, rangeMs: Long, now: Long): DrainReport {
        flush(context)
        val db = BatteryDb.get(context)
        val previous = db.drainTotals(now - 2 * rangeMs, now - rangeMs).associateBy { it.pkg }
        val rows = db.drainTotals(now - rangeMs, now)
            .filter { it.mah >= 0.5 }
            .sortedByDescending { it.mah }
            .map { DrainRow(it.pkg, label(context, it.pkg), it.mah, it.ms, previous[it.pkg]?.mah) }
        val background = backgroundUse(context, now - rangeMs, now)
            .map { it.copy(label = label(context, it.pkg)) }
        return DrainReport(rows, background, capacity(context))
    }
}

data class DrainEntry(val hour: Long, val pkg: String, val mah: Double, val ms: Long)

data class DrainTotal(val pkg: String, val mah: Double, val ms: Long)

data class DrainRow(val pkg: String, val label: String, val mah: Double, val ms: Long, val previousMah: Double?)

data class BackgroundUse(val pkg: String, val serviceMs: Long, val bytes: Long, val label: String = pkg)

data class DrainReport(val rows: List<DrainRow>, val background: List<BackgroundUse>, val capacityMah: Int)
