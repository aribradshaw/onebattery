package com.flygonlc.onebattery.ui

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.preferredFrameRate

/**
 * High refresh rate while something moves, and only then.
 *
 * The S24 Ultra (and most LTPO phones) runs third-party apps at 60 Hz unless they ask for more.
 * OneBattery asks for the display's peak rate (120 Hz there) while a list scrolls, a chart is pinched
 * or animating, a screen slides, the dial is dragged, a short transition runs, or the charging
 * effects play — and stops asking as soon as that motion ends, so an idle dashboard (including the
 * 2 s live readings) falls back to the normal low rate. A battery app must not pin 120 Hz.
 *
 * - API 35+: Compose's adaptive-refresh voting ([preferredFrameRate]). A vote only counts on frames
 *   that actually draw, and is cleared (rate 0) when the motion stops.
 * - API 29–34: the window's preferred display mode (the peak-rate mode at the current resolution),
 *   held while anything is active and dropped shortly after the last motion ends.
 *
 * System caps still win: Power saving mode or a "Standard" motion-smoothness setting keep the
 * display at 60 Hz whatever the app asks.
 */
internal object RefreshRate {
    /** Peak refresh rate at the current resolution, or 0 when it isn't above 60 Hz (nothing to ask for). */
    var peakHz = 0f
        private set
    private var peakModeId = 0
    private var window: Window? = null
    private var active = 0
    private val handler = Handler(Looper.getMainLooper())
    private val drop = Runnable { setWindowMode(0) }

    /** How long the window keeps the peak mode after the last motion ends (API 29–34 only). */
    private const val RELEASE_DELAY_MS = 600L

    fun attach(activity: Activity) {
        val display: Display? = try {
            if (Build.VERSION.SDK_INT >= 30) activity.display else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay
        } catch (_: Exception) {
            null
        }
        val current = display?.mode
        val peak = current?.let { cur ->
            display.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate }
        }
        if (peak != null && peak.refreshRate > 61f) {
            peakHz = peak.refreshRate
            peakModeId = peak.modeId
        } else {
            peakHz = 0f
            peakModeId = 0
        }
        if (Build.VERSION.SDK_INT < 35) {
            window = activity.window
            active = 0
        }
    }

    fun detach(activity: Activity) {
        if (window === activity.window) {
            handler.removeCallbacks(drop)
            window = null
            active = 0
        }
    }

    /** API 29–34: one more surface is moving. */
    fun acquire() {
        if (peakModeId == 0) return
        active++
        handler.removeCallbacks(drop)
        setWindowMode(peakModeId)
    }

    /** API 29–34: a moving surface settled; drop the peak mode once nothing else moves. */
    fun release() {
        if (peakModeId == 0 || active == 0) return
        active--
        if (active == 0) handler.postDelayed(drop, RELEASE_DELAY_MS)
    }

    private fun setWindowMode(modeId: Int) {
        val w = window ?: return
        val attrs = w.attributes
        if (attrs.preferredDisplayModeId == modeId) return
        attrs.preferredDisplayModeId = modeId
        w.attributes = attrs
    }
}

/**
 * Requests the display's peak refresh rate for this element while [active]. On API 35+ this is a
 * per-frame vote on the element's layer (rate 0 = no vote); on API 29–34 it holds the window's
 * peak mode for as long as [active] stays true.
 */
@Composable
internal fun Modifier.fastFrames(active: Boolean): Modifier {
    val peak = RefreshRate.peakHz
    if (peak <= 0f) return this
    if (Build.VERSION.SDK_INT >= 35) return preferredFrameRate(if (active) peak else 0f)
    DisposableEffect(active) {
        if (active) RefreshRate.acquire()
        onDispose { if (active) RefreshRate.release() }
    }
    return this
}

/**
 * Wraps [content] in a box that asks for the peak refresh rate while [active] returns true. The state
 * is read here, so only this wrapper recomposes when motion starts or stops, not the content.
 */
@Composable
internal fun FastFrames(active: () -> Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fastFrames(active())) { content() }
}
