package com.flygonlc.onebattery

import android.content.Context
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/**
 * Measures real capacity by integrating battery current over time between level changes.
 *
 * Samsung (and others) derive CHARGE_COUNTER from the % level and a fixed capacity, so
 * charge_counter / level always returns ~design capacity. Current, however, is measured, so
 * mAh actually moved / % actually changed * 100 gives the true full-charge capacity.
 *
 * Both ends are anchored on the moment the integer % ticks over to avoid rounding error.
 */
object CoulombCounter {
    const val MIN_LEVEL_SPAN = 15
    private const val MIN_COVERAGE = 0.85
    private const val MAX_STEP_MS = 90_000L

    data class Progress(
        val charging: Boolean,
        /** Level at the first observed tick; null until the % changes once. */
        val anchorLevel: Int?,
        val levelSpan: Int,
        val mah: Double,
        val coverage: Double,
    ) {
        val capacityMah: Int? get() = if (levelSpan > 0) (mah * 100 / levelSpan).toInt() else null
    }

    private class Segment(val charging: Boolean, var lastLevel: Int, var lastTime: Long, var lastCurrentMa: Int) {
        var anchorLevel: Int? = null
        var anchorTime = 0L
        var mah = 0.0
        var coveredMs = 0L
        // Snapshot at the most recent level tick; this is what gets committed.
        var tickLevel = 0
        var tickTime = 0L
        var tickMah = 0.0
        var tickCoveredMs = 0L

        fun span() = anchorLevel?.let { abs(tickLevel - it) } ?: 0
        fun coverage() = if (tickTime > anchorTime) tickCoveredMs.toDouble() / (tickTime - anchorTime) else 0.0
    }

    private var seg: Segment? = null
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    @Synchronized
    fun onReading(context: Context, s: BatterySnapshot) {
        val current = s.currentMa
        val mode = when {
            s.status == BatteryManager.BATTERY_STATUS_CHARGING -> true
            s.status == BatteryManager.BATTERY_STATUS_DISCHARGING && s.plugged == 0 -> false
            else -> null
        }
        if (current == null || mode == null) {
            finish(context); return
        }
        var cur = seg
        if (cur == null || cur.charging != mode || s.time - cur.lastTime > 10 * 60_000L) {
            finish(context)
            cur = Segment(mode, s.level, s.time, current).also { seg = it }
            publish(cur); return
        }

        val dt = s.time - cur.lastTime
        if (dt in 1..MAX_STEP_MS) {
            cur.mah += (abs(cur.lastCurrentMa) + abs(current)) / 2.0 * dt / 3_600_000.0
            cur.coveredMs += dt
        }
        if (s.level != cur.lastLevel) {
            if (cur.anchorLevel == null) {
                cur.anchorLevel = s.level; cur.anchorTime = s.time; cur.mah = 0.0; cur.coveredMs = 0L
            }
            cur.tickLevel = s.level; cur.tickTime = s.time; cur.tickMah = cur.mah; cur.tickCoveredMs = cur.coveredMs
        }
        cur.lastLevel = s.level; cur.lastTime = s.time; cur.lastCurrentMa = current
        publish(cur)
    }

    private fun publish(c: Segment) {
        _progress.value = Progress(c.charging, c.anchorLevel, c.span(), c.tickMah, c.coverage())
    }

    @Synchronized
    fun finish(context: Context) {
        val c = seg ?: return
        seg = null
        _progress.value = null
        val from = c.anchorLevel ?: return
        if (c.span() < MIN_LEVEL_SPAN || c.coverage() < MIN_COVERAGE || c.tickMah <= 0) return
        BatteryDb.get(context).insertMeasurement(
            Measurement(
                time = c.tickTime, charging = c.charging, fromLevel = from, toLevel = c.tickLevel,
                mah = c.tickMah.toInt(), capacityMah = (c.tickMah * 100 / c.span()).toInt(),
            )
        )
    }
}

data class Measurement(
    val time: Long,
    val charging: Boolean,
    val fromLevel: Int,
    val toLevel: Int,
    val mah: Int,
    val capacityMah: Int,
)
