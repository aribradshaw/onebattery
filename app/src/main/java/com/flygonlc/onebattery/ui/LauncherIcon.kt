package com.flygonlc.onebattery.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Makes the home-screen icon follow the selected theme. The manifest declares one
 * <activity-alias> per theme (all targeting MainActivity); exactly one is enabled at a time.
 * Only .LauncherSystem is enabled in the manifest.
 */
object LauncherIcon {
    private const val PREFIX = "com.flygonlc.onebattery.Launcher"
    private const val DEFAULT_ID = "system"

    private val aliases = mapOf(
        "flygon" to "Flygon",
        "othello" to "Othello",
        "system" to "System",
        "night" to "Night",
    )

    fun apply(context: Context, themeId: String) {
        try {
            val target = if (themeId in aliases) themeId else DEFAULT_ID
            val pm = context.packageManager
            // Enable the new alias before disabling the old one, so there is never a moment with no
            // launcher entry (some launchers would drop the icon from the home screen).
            set(context, pm, target, true)
            for (id in aliases.keys) if (id != target) set(context, pm, id, false)
        } catch (_: Exception) {
            // Never crash over an icon.
        }
    }

    private fun set(context: Context, pm: PackageManager, id: String, enabled: Boolean) {
        val component = ComponentName(context.packageName, PREFIX + aliases.getValue(id))
        val current = when (pm.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> id == DEFAULT_ID
            else -> false
        }
        if (current == enabled) return
        pm.setComponentEnabledSetting(
            component,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}
