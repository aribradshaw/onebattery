package com.flygonlc.onebattery

import android.content.Context

/**
 * User-tunable monitor options, stored in the same SharedPreferences file the
 * dashboard uses for its own view settings.
 *
 * These only control how often the ongoing notification is rewritten — the
 * sampling loop keeps running at full rate because coulomb counting needs it.
 * Notification writes are what pull the CPU out of idle (each one takes a short
 * partial wakelock), so this is the knob that actually matters overnight.
 */
object MonitorSettings {
    const val PREFS = "onebattery"

    private const val KEY_NOTIFY_INTERVAL = "notify_interval_ms"
    private const val KEY_NOTIFY_LIVE = "notify_live"
    private const val KEY_NOTIFY_SCREEN_ONLY = "notify_screen_on_only"

    const val DEFAULT_INTERVAL = 30_000L

    /** Cadences offered in the settings screen. */
    val CADENCES = listOf(
        10_000L to "10 s",
        30_000L to "30 s",
        60_000L to "1 min",
        300_000L to "5 min",
        900_000L to "15 min",
    )

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Minimum gap between notification rewrites. */
    fun intervalMs(c: Context): Long = prefs(c).getLong(KEY_NOTIFY_INTERVAL, DEFAULT_INTERVAL)

    /** Whether the notification text tracks the battery at all. */
    fun liveNotification(c: Context): Boolean = prefs(c).getBoolean(KEY_NOTIFY_LIVE, true)

    /** Skip rewrites entirely while the screen is off. */
    fun screenOnOnly(c: Context): Boolean = prefs(c).getBoolean(KEY_NOTIFY_SCREEN_ONLY, true)

    fun setIntervalMs(c: Context, ms: Long) = prefs(c).edit().putLong(KEY_NOTIFY_INTERVAL, ms).apply()

    fun setLiveNotification(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_NOTIFY_LIVE, on).apply()

    fun setScreenOnOnly(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_NOTIFY_SCREEN_ONLY, on).apply()
}
