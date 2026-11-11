package com.flygonlc.onebattery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import kotlin.math.abs

data class BatterySnapshot(
    val time: Long,
    val level: Int,
    val status: Int,
    val plugged: Int,
    val health: Int,
    val tempC: Float,
    val voltageMv: Int,
    /** Instantaneous current in mA, positive = into the battery. Null if unsupported. */
    val currentMa: Int?,
    /** Remaining charge in mAh as reported by the OS; on Samsung this is level x design, not measured. */
    val chargeMah: Int?,
    val cycleCount: Int?,
    val technology: String?,
    val chargeTimeRemainingMs: Long?,
) {
    val isCharging get() = status == BatteryManager.BATTERY_STATUS_CHARGING
    val powerW: Float? get() = currentMa?.let { abs(it) * voltageMv / 1_000_000f }

}

object BatteryReader {
    private const val EXTRA_CYCLE_COUNT = "android.os.extra.CYCLE_COUNT"
    private const val KEY_SIGN = "current_sign"

    fun read(context: Context): BatterySnapshot? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val bm = context.getSystemService(BatteryManager::class.java)

        val levelRaw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val level = if (levelRaw >= 0 && scale > 0) levelRaw * 100 / scale
            else bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)

        // Vendors disagree on units: AOSP says uA / uAh, some report mA / mAh. Normalise by magnitude.
        val currentRaw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        var currentMa = when {
            currentRaw == Int.MIN_VALUE || currentRaw == 0 -> null
            abs(currentRaw) >= 20_000 -> currentRaw / 1000
            else -> currentRaw
        }
        // Sign conventions differ by vendor. Learn it from a clear discharge (unplugged, real
        // draw) so readings during heavy use while plugged in keep their true sign.
        val prefs = context.getSharedPreferences("onebattery", Context.MODE_PRIVATE)
        if (currentMa != null && status == BatteryManager.BATTERY_STATUS_DISCHARGING &&
            intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0 && abs(currentMa) > 50
        ) {
            val sign = if (currentMa < 0) 1 else -1
            if (prefs.getInt(KEY_SIGN, 0) != sign) prefs.edit().putInt(KEY_SIGN, sign).apply()
        }
        val sign = prefs.getInt(KEY_SIGN, 0)
        currentMa = currentMa?.let {
            when {
                sign != 0 -> it * sign
                // Not learned yet: fall back to matching the charging status.
                (status == BatteryManager.BATTERY_STATUS_CHARGING) != (it > 0) -> -it
                else -> it
            }
        }

        val counterRaw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val chargeMah = when {
            counterRaw == Int.MIN_VALUE || counterRaw <= 0 -> null
            counterRaw >= 50_000 -> counterRaw / 1000
            else -> counterRaw
        }

        val cycles = if (Build.VERSION.SDK_INT >= 34) intent.getIntExtra(EXTRA_CYCLE_COUNT, -1) else -1
        val remaining = bm.computeChargeTimeRemaining()

        return BatterySnapshot(
            time = System.currentTimeMillis(),
            level = level,
            status = status,
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN),
            tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f,
            voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
            currentMa = currentMa,
            chargeMah = chargeMah,
            cycleCount = cycles.takeIf { it > 0 },
            technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
            chargeTimeRemainingMs = remaining.takeIf { it > 0 },
        )
    }

    @Volatile private var designCache = 0

    /**
     * Design capacity from the framework power profile, falling back to known devices. Constant per
     * device, so it's computed once (PowerProfile parses an XML resource; MainActivity warms this
     * off the main thread).
     */
    fun designCapacityMah(context: Context): Int {
        designCache.takeIf { it > 0 }?.let { return it }
        return computeDesignCapacity(context).also { designCache = it }
    }

    private fun computeDesignCapacity(context: Context): Int {
        try {
            val cls = Class.forName("com.android.internal.os.PowerProfile")
            val profile = cls.getConstructor(Context::class.java).newInstance(context)
            val cap = cls.getMethod("getBatteryCapacity").invoke(profile) as Double
            if (cap >= 1000) return cap.toInt()
        } catch (_: Throwable) {
        }
        return if (Build.MODEL.startsWith("SM-S928")) 5000 else 4000
    }

    fun statusLabel(s: Int) = when (s) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
        else -> "Unknown"
    }

    fun pluggedLabel(p: Int) = when (p) {
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
        BatteryManager.BATTERY_PLUGGED_DOCK -> "Dock"
        else -> "Unplugged"
    }

    fun healthLabel(h: Int) = when (h) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
        BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
        else -> "Unknown"
    }
}
