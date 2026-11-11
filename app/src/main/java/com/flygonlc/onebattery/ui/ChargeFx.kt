package com.flygonlc.onebattery.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Small motion helpers shared by the dashboard cards: a frame-driven loop phase whose speed can
 * change without restarting (so the charge shimmer can follow the wattage), pixel stepping for the
 * Flygon LC theme, and a themed progress bar with flowing stripes.
 */

/** False when the user turned animations off (developer options / accessibility "Remove animations"). */
@Composable
internal fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}

/**
 * True while the activity is RESUMED. Every looping effect in ui/ keys on this, so nothing ticks
 * (delay loops included, which the paused frame clock would not stop) while the app is in the
 * background, even though the composition is retained.
 */
@Composable
internal fun isResumed(): Boolean {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return state.isAtLeast(Lifecycle.State.RESUMED)
}

/** Flygon LC is the pixel theme: animations there move in visible steps rather than gliding. */
internal val isPixelTheme: Boolean get() = Palette.theme.hardShadow > 0.dp

/** Quantizes a 0..1 phase into [steps] discrete positions. */
internal fun stepped(phase: Float, steps: Int): Float = floor(phase * steps) / steps

/**
 * A 0..1 phase that loops every [periodMs] while [running]. Period is read every frame, so a
 * changing speed just changes the rate — no jump. Read the returned state inside draw / graphicsLayer
 * lambdas so only drawing (not composition) happens per frame. Resets to 0 when stopped; pauses
 * (keeping its value) while the activity is not resumed.
 */
@Composable
internal fun rememberLoopPhase(running: Boolean, periodMs: Float): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    val period by rememberUpdatedState(periodMs.coerceAtLeast(16f))
    val resumed = isResumed()
    LaunchedEffect(running) { if (!running) phase.floatValue = 0f }
    LaunchedEffect(running, resumed) {
        if (!running || !resumed) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dtMs = (now - last) / 1_000_000f
                last = now
                phase.floatValue = (phase.floatValue + dtMs / period) % 1f
            }
        }
    }
    return phase
}

/** Number that counts up from 0 the first time it shows, then eases between later values. */
@Composable
internal fun animatedCount(target: Float, durationMs: Int = 1100): Float {
    val anim = remember { Animatable(0f) }
    val enabled = animationsEnabled()
    LaunchedEffect(target) {
        if (enabled) anim.animateTo(target, tween(durationMs, easing = FastOutSlowInEasing)) else anim.snapTo(target)
    }
    return anim.value
}

/**
 * Themed linear bar: theme corners and outline, fill that eases to [progress] (from 0 on first
 * show). When [flowing], diagonal stripes drift through the fill — left→right when [forward],
 * the other way otherwise. Callers pass flowing only while charging (a battery app should not run
 * an endless animation on battery); otherwise the bar is static apart from the bounded fill ease.
 */
@Composable
internal fun FxBar(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    flowing: Boolean = false,
    forward: Boolean = true,
    height: Dp = 10.dp,
) {
    val t = Palette.theme
    val enabled = animationsEnabled()
    val pixel = isPixelTheme
    val fill = remember { Animatable(0f) }
    LaunchedEffect(progress) {
        val target = progress.coerceIn(0f, 1f)
        if (enabled) fill.animateTo(target, tween(900, easing = FastOutSlowInEasing)) else fill.snapTo(target)
    }
    val flow = rememberLoopPhase(flowing && enabled, if (pixel) 1400f else 1100f)
    val corner = min(t.chipRadius.value, height.value / 2f).dp
    val shape = RoundedCornerShape(corner)
    val outline = if (t.hardShadow > 0.dp) t.ink else t.line
    val stripe = if (t.dark) Color.Black.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.38f)
    val track = Palette.grid
    val showStripes = flowing

    Canvas(
        modifier.fillMaxWidth().height(height + t.border * 2).clip(shape)
            .then(if (t.border > 0.dp) Modifier.border(t.border, outline, shape) else Modifier)
            // Peak refresh only while something moves: flowing stripes, or the fill easing in.
            .fastFrames((flowing && enabled) || fill.isRunning)
    ) {
        val inset = t.border.toPx()
        drawRect(track)
        var w = (size.width - inset * 2) * fill.value
        // Pixel theme: fill grows in whole "pixels" (blocks of 4dp).
        if (pixel) { val cell = 4.dp.toPx(); w = floor(w / cell) * cell }
        if (w <= 0f) return@Canvas
        val r = (corner.toPx() - inset).coerceAtLeast(0f)
        drawRoundRect(color, Offset(inset, inset), Size(w, size.height - inset * 2), CornerRadius(r))
        if (showStripes) {
            val gap = (if (pixel) 12.dp else 14.dp).toPx()
            var p = flow.value
            if (pixel) p = stepped(p, 6)
            val shift = (if (forward) p else 1f - p) * gap
            clipRect(inset, inset, inset + w, size.height - inset) {
                drawStripes(stripe, gap, shift, pixel)
            }
        }
    }
}

private fun DrawScope.drawStripes(color: Color, gap: Float, shift: Float, pixel: Boolean) {
    val h = size.height
    val band = gap / 2f
    var x = -h - gap + shift
    while (x < size.width + gap) {
        if (pixel) {
            // Chunky stair-step stripes instead of smooth diagonals.
            val step = h / 3f
            for (i in 0 until 3) {
                drawRect(color, Offset(x + i * step, h - (i + 1) * step), Size(band, step))
            }
        } else {
            val path = Path().apply {
                moveTo(x, h); lineTo(x + h, 0f); lineTo(x + h + band, 0f); lineTo(x + band, h); close()
            }
            drawPath(path, color)
        }
        x += gap
    }
}

/**
 * Charging overlay for the hero arc gauge: a highlight that runs along the filled arc from the start
 * to the current level, and a soft ripple at the arc's leading edge. [sweepPhase]/[pulsePhase] are
 * 0..1 loop phases. Geometry matches the gauge (start 135°, 270° total).
 */
internal fun DrawScope.drawChargeFx(
    level: Int,
    stroke: Float,
    sweepPhase: Float,
    pulsePhase: Float,
    dark: Boolean,
    pixel: Boolean,
    edgeColor: Color,
) {
    val start = 135f
    val sweep = 270f * level.coerceIn(0, 100) / 100f
    if (sweep <= 0f) return
    val inset = stroke / 2
    val arc = Size(size.width - stroke, size.height - stroke)
    val topLeft = Offset(inset, inset)
    val hi = Color.White
    val baseAlpha = if (dark) 0.42f else 0.6f

    // Highlight streak: a head travelling past the fill end with a fading tail behind it.
    val tail = 48f.coerceAtMost(sweep)
    val phase = if (pixel) stepped(sweepPhase, 14) else sweepPhase
    val head = phase * (sweep + tail)
    val slices = if (pixel) 4 else 10
    val sliceLen = tail / slices
    val thin = Stroke(stroke * if (pixel) 0.5f else 0.42f, cap = StrokeCap.Butt)
    for (i in 0 until slices) {
        val a0 = head - tail + i * sliceLen
        val a1 = a0 + sliceLen
        val from = a0.coerceIn(0f, sweep)
        val to = a1.coerceIn(0f, sweep)
        if (to - from <= 0.1f) continue
        val alpha = baseAlpha * (i + 1) / slices
        drawArc(hi.copy(alpha = alpha), start + from, to - from, false, topLeft, arc, style = thin)
    }

    // Leading-edge pulse: an expanding, fading ring (a square in the pixel theme).
    val rad = Math.toRadians((start + sweep).toDouble())
    val cx = size.width / 2 + (arc.width / 2) * cos(rad).toFloat()
    val cy = size.height / 2 + (arc.height / 2) * sin(rad).toFloat()
    val p = if (pixel) stepped(pulsePhase, 5) else pulsePhase
    val grow = stroke * (0.5f + 0.55f * p)
    val a = 0.55f * (1f - p)
    if (pixel) {
        val s = grow * 2
        drawRect(edgeColor.copy(alpha = a), Offset(cx - s / 2, cy - s / 2), Size(s, s), style = Stroke(2.dp.toPx()))
        val dot = stroke * 0.36f
        drawRect(hi.copy(alpha = baseAlpha), Offset(cx - dot / 2, cy - dot / 2), Size(dot, dot))
    } else {
        drawCircle(edgeColor.copy(alpha = a), grow, Offset(cx, cy), style = Stroke(1.5.dp.toPx()))
        drawCircle(hi.copy(alpha = baseAlpha * (0.6f + 0.4f * (1f - p))), stroke * 0.2f, Offset(cx, cy))
    }
}
