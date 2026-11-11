package com.flygonlc.onebattery

import kotlin.math.abs
import java.util.concurrent.TimeUnit

data class CapacityEstimate(val mah: Int, val basis: String)

data class ChargeSession(val start: BatterySnapshot, val end: BatterySnapshot)

object Stats {
    private const val MAX_GAP_MS = 2 * 60 * 60 * 1000L

    fun median(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    fun chargeSessions(samples: List<BatterySnapshot>): List<ChargeSession> {
        val out = ArrayList<ChargeSession>()
        var start: BatterySnapshot? = null
        var last: BatterySnapshot? = null
        for (s in samples) {
            val gap = last != null && s.time - last.time > MAX_GAP_MS
            if (start != null && (!s.isCharging || gap)) {
                if (last !== start) out += ChargeSession(start, last!!)
                start = null
            }
            if (s.isCharging && start == null) start = s
            last = s
        }
        if (start != null && last != null && last !== start) out += ChargeSession(start, last)
        return out
    }

    /** Median of measured capacities, weighting larger % spans more (more precise). */
    fun capacity(measurements: List<Measurement>): CapacityEstimate? {
        if (measurements.isEmpty()) return null
        val weighted = measurements.flatMap { m -> List(abs(m.toLevel - m.fromLevel) / 5) { m.capacityMah } }
        val n = measurements.size
        val charges = measurements.count { it.charging }
        val kinds = when (charges) {
            n -> "charge${if (n == 1) "" else "s"}"
            0 -> "discharge${if (n == 1) "" else "s"}"
            else -> "charges/discharges"
        }
        return CapacityEstimate(median(weighted)!!, "from $n measured $kinds")
    }

    /** Average battery drain in %/hour across discharging stretches. */
    fun dischargeRate(samples: List<BatterySnapshot>): Float? {
        var drop = 0
        var ms = 0L
        for (i in 1 until samples.size) {
            val a = samples[i - 1]
            val b = samples[i]
            val dt = b.time - a.time
            if (a.isCharging || b.isCharging || a.plugged != 0 || b.plugged != 0) continue
            if (dt <= 0 || dt > MAX_GAP_MS || b.level > a.level) continue
            drop += a.level - b.level
            ms += dt
        }
        if (ms < TimeUnit.HOURS.toMillis(1)) return null
        return drop / (ms / 3_600_000f)
    }

    fun formatDuration(ms: Long): String {
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
