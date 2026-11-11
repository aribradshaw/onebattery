package com.flygonlc.onebattery.ui

import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flygonlc.onebattery.BatterySnapshot
import com.flygonlc.onebattery.Measurement
import com.flygonlc.onebattery.Stats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

private const val HOUR_MS = 3_600_000L
private const val MIN_SPAN_MS = HOUR_MS.toFloat()
/** A released window snaps to a preset when its span is within this fraction of it… */
private const val SNAP_SPAN_TOLERANCE = 0.15f
/** …and its right edge is within this fraction of the span from now. */
private const val SNAP_END_TOLERANCE = 0.15f

/**
 * A custom (zoomed / panned) chart window, relative to now: [spanMs] wide, its right edge [endAgoMs]
 * before now. Hoisted into the dashboard with rememberSaveable so it survives rotation and the card
 * scrolling out of the list; null means "exactly the selected preset range".
 */
@Immutable
internal data class ChartWindow(val spanMs: Long, val endAgoMs: Long)

internal val ChartWindowSaver: Saver<ChartWindow?, LongArray> = Saver(
    save = { w -> w?.let { longArrayOf(it.spanMs, it.endAgoMs) } },
    restore = { a -> if (a.size == 2 && a[0] > 0 && a[1] >= 0) ChartWindow(a[0], a[1]) else null },
)

/**
 * The visible time window shared by the battery-level and temperature charts, stored
 * relative to "now" so a preset window keeps following the clock. Starts from [saved] when the
 * dashboard has a custom window on record; reports every settled window through [onWindow].
 */
@Stable
private class ChartViewport(
    private val scope: CoroutineScope,
    initial: Range,
    fullSpan: Float,
    saved: ChartWindow?,
) {
    /** Span from the earliest sample (or now−24h) to now: the "All" window and the zoom-out limit. */
    // Plain field: refreshed every composition, only read from gestures/animations/effects.
    var fullSpan: Float = fullSpan
    /** Plain field, refreshed every composition: where settled windows are reported. */
    var onWindow: (ChartWindow?) -> Unit = {}
    var span by mutableFloatStateOf(saved?.spanMs?.toFloat()?.coerceAtLeast(MIN_SPAN_MS) ?: presetSpan(initial))
        private set
    /** How far the right edge sits before now, ms. */
    var endAgo by mutableFloatStateOf(saved?.endAgoMs?.toFloat() ?: 0f)
        private set
    /** The preset the window shows exactly (or is animating to); null while custom. */
    var selected by mutableStateOf<Range?>(if (saved == null) initial else null)
        private set
    var gesturing = false
        private set
    /** True while a pinch/pan or a glide is moving the window (drives the high refresh rate). */
    var moving by mutableStateOf(false)
        private set
    private var job: Job? = null
    private var gestureMax = fullSpan

    fun presetSpan(r: Range) = if (r == Range.All) fullSpan else r.ms.toFloat()

    val canPan get() = span < gestureLimit() * 0.995f

    private fun gestureLimit() = if (gesturing) gestureMax else max(fullSpan, span + endAgo)

    fun begin() {
        job?.cancel()
        job = null
        gesturing = true
        moving = true
        // A preset wider than the data (e.g. 30d with a week logged) stays reachable while zooming.
        gestureMax = max(fullSpan, span + endAgo)
    }

    /** Zoom by [zoom] around [focus] (0 = left edge, 1 = right edge) and pan by [panFrac] of the width. */
    fun transform(zoom: Float, focus: Float, panFrac: Float) {
        val limit = gestureMax
        val startAgo = endAgo + span
        val focusAgo = startAgo - focus * span
        val newSpan = (span / zoom).coerceIn(MIN_SPAN_MS, limit)
        var newEndAgo = focusAgo + focus * newSpan - newSpan
        newEndAgo += panFrac * newSpan
        span = newSpan
        endAgo = newEndAgo.coerceIn(0f, (limit - newSpan).coerceAtLeast(0f))
        selected = Range.entries.firstOrNull { matches(it, 0.01f, 0.01f) }
    }

    /** Gesture released: settle onto a nearby preset, if any. Returns it so the caller can share it. */
    fun end(): Range? {
        gesturing = false
        val target = Range.entries
            .filter { matches(it, SNAP_SPAN_TOLERANCE, SNAP_END_TOLERANCE) }
            .minByOrNull { abs(ln(span / presetSpan(it))) }
        if (target == null) {
            moving = false
            onWindow(ChartWindow(span.toLong(), endAgo.toLong()))
            return null
        }
        animateTo(target)
        return target
    }

    private fun matches(r: Range, spanTol: Float, endTol: Float): Boolean =
        abs(span / presetSpan(r) - 1f) <= spanTol && endAgo <= endTol * span

    fun animateTo(r: Range) {
        job?.cancel()
        selected = r
        onWindow(null)
        val s0 = span
        val e0 = endAgo
        moving = true
        lateinit var self: Job
        self = scope.launch {
            try {
                animate(0f, 1f, animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)) { p, _ ->
                    // Interpolate span in log space so zooming feels uniform across 1h…1y.
                    val s1 = presetSpan(r)
                    span = exp(ln(s0) + (ln(s1) - ln(s0)) * p)
                    endAgo = e0 * (1f - p)
                }
            } finally {
                // Superseded by a new glide or gesture: that one owns `moving` now.
                if (job === self) { job = null; moving = false }
            }
        }
        job = self
    }

    /** "All" keeps tracking the data's extent as history loads, unless the user is busy with it. */
    fun followAll() {
        if (selected == Range.All && !gesturing && job == null) { span = fullSpan; endAgo = 0f }
    }
}

/**
 * Pinch to zoom around the fingers' centroid, drag horizontally to pan when zoomed in.
 * Leaves single-finger vertical drags (and horizontal ones when nothing to pan) to the parent list.
 */
private fun Modifier.viewportGestures(vp: ChartViewport, onSettled: (Range) -> Unit): Modifier =
    pointerInput(vp) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var owned = false // we're transforming; consume everything
            var yielded = false // a single-finger drag went to the parent
            var dx = 0f
            var dy = 0f
            fun plotWidth() = (size.width - ChartPadLeft.toPx() - ChartPadRight.toPx()).coerceAtLeast(1f)
            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) {
                    if (!owned) { owned = true; vp.begin() }
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    val c = event.calculateCentroid(useCurrent = true)
                    val focus = ((c.x - ChartPadLeft.toPx()) / plotWidth()).coerceIn(0f, 1f)
                    vp.transform(zoom, focus, pan.x / plotWidth())
                    event.changes.forEach { it.consume() }
                } else if (pressed == 1 && !yielded) {
                    val ch = event.changes.first { it.pressed }
                    if (!owned) {
                        if (ch.isConsumed) { yielded = true; continue }
                        val d = ch.positionChange()
                        dx += d.x; dy += d.y
                        if (abs(dx) > slop || abs(dy) > slop) {
                            if (abs(dx) > abs(dy) * 1.2f && vp.canPan) { owned = true; vp.begin() } else yielded = true
                        }
                    }
                    if (owned) {
                        val d = ch.positionChange()
                        if (d.x != 0f) vp.transform(1f, 0.5f, d.x / plotWidth())
                        ch.consume()
                    }
                }
            } while (event.changes.any { it.pressed })
            if (owned) vp.end()?.let(onSettled)
        }
    }

/**
 * History in chart-ready form, built off the main thread each time samples reload (see Dashboard):
 * the history card can scroll in and out of the list without redoing any of this on the UI thread.
 */
@Immutable
internal class HistoryData(
    val samples: List<BatterySnapshot>,
    val levels: ChartSeries,
    val temps: ChartSeries,
    val bands: List<Band>,
) {
    companion object {
        val Empty = from(emptyList())

        fun from(samples: List<BatterySnapshot>) = HistoryData(
            samples = samples,
            levels = ChartSeries(LongArray(samples.size) { samples[it].time }, FloatArray(samples.size) { samples[it].level.toFloat() }),
            temps = ChartSeries(LongArray(samples.size) { samples[it].time }, FloatArray(samples.size) { samples[it].tempC }),
            bands = Stats.chargeSessions(samples).map { Band(it.start.time, it.end.time) },
        )
    }
}

@Composable
internal fun HistoryCard(
    history: HistoryData,
    range: Range,
    window: ChartWindow?,
    now: Long,
    onRange: (Range) -> Unit,
    onWindow: (ChartWindow?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val earliest = history.samples.firstOrNull()?.time ?: now
    val fullSpan = (now - minOf(earliest, now - Range.Day.ms)).toFloat()
    // Animations and gestures stay local; the starting window comes from the hoisted saved state.
    val vp = remember { ChartViewport(scope, range, fullSpan, window) }
    vp.fullSpan = fullSpan
    vp.onWindow = onWindow
    LaunchedEffect(fullSpan) { vp.followAll() }
    // Another card (e.g. the drain card) changed the shared range: glide there. Only on an actual
    // change, so a restored custom window isn't animated away on first composition.
    val seenRange = remember { mutableStateOf(range) }
    LaunchedEffect(range) {
        if (range == seenRange.value) return@LaunchedEffect
        seenRange.value = range
        if (vp.selected != range) vp.animateTo(range)
    }
    val onRangeState = rememberUpdatedState(onRange)
    // The window is read only inside these lambdas, i.e. in the charts' draw phase: a pinch or a
    // glide redraws the two canvases each frame but recomposes nothing.
    val xEnd = remember(vp, now) { { now - vp.endAgo.toLong() } }
    val xStart = remember(vp, now) { { now - vp.endAgo.toLong() - vp.span.toLong() } }
    val gestures = Modifier.viewportGestures(vp) { onRangeState.value(it) }.fastFrames(vp.moving)

    CardBox {
        SectionTitle("Battery level")
        RangeChips(selected = vp.selected, onSelect = { r -> onRange(r); vp.animateTo(r) })
        Spacer(Modifier.height(8.dp))
        TimeLineChart(
            series = history.levels, xStart = xStart, xEnd = xEnd, yMin = { 0f }, yMax = { 100f },
            color = Palette.green, yLabel = PercentLabel, bands = history.bands,
            modifier = gestures,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(Palette.band))
            Text("  charging", color = Palette.muted, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text("pinch to zoom", color = Palette.muted.copy(alpha = 0.7f), fontSize = 11.sp)
        }
        Spacer(Modifier.height(16.dp))
        SectionTitle("Temperature")
        // Fit the y-axis to what's on screen, easing so it doesn't jitter mid-pinch. The derived
        // targets only change when the visible extremes do, so the card recomposes at most then.
        val bounds = remember(history, xStart, xEnd) {
            derivedStateOf(structuralEqualityPolicy()) {
                val (lo, hi) = history.temps.minMaxIn(xStart(), xEnd()) ?: (20f to 40f)
                (lo - 2f).coerceAtMost(20f) to (hi + 2f).coerceAtLeast(40f)
            }
        }
        val tMin = animateFloatAsState(bounds.value.first, tween(250), label = "tMin")
        val tMax = animateFloatAsState(bounds.value.second, tween(250), label = "tMax")
        TimeLineChart(
            series = history.temps, xStart = xStart, xEnd = xEnd, yMin = { tMin.value }, yMax = { tMax.value },
            height = 120.dp, color = Palette.amber, yLabel = DegreeLabel,
            modifier = gestures,
        )
    }
}

private val PercentLabel: (Float) -> String = { "${it.roundToInt()}%" }
private val DegreeLabel: (Float) -> String = { "${it.roundToInt()}°" }
private val PlainLabel: (Float) -> String = { "${it.roundToInt()}" }

private const val PREFS = "onebattery"
private const val KEY_TREND_CURVED = "trend_curved"

@Composable
internal fun CapacityTrendCard(measurements: List<Measurement>, design: Int, now: Long) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var curved by remember { mutableStateOf(prefs.getBoolean(KEY_TREND_CURVED, false)) }
    val smoothState = animateFloatAsState(
        if (curved) 1f else 0f, tween(400, easing = FastOutSlowInEasing), label = "trendSmooth",
    )
    val points = remember(measurements) { measurements.map { it.time to it.capacityMah.toFloat() } }
    val series = remember(points) { points.toSeries() }
    CardBox {
        SectionTitle("Capacity trend") {
            CurveToggle(curved, { smoothState.value }) {
                curved = it
                prefs.edit().putBoolean(KEY_TREND_CURVED, it).apply()
            }
        }
        Text("Each dot is one measured charge or discharge", color = Palette.muted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        val start = (points.firstOrNull()?.first ?: now).coerceAtMost(now - 7 * 24 * 3_600_000L)
        val vals = points.map { it.second }
        val yMin = ((vals.minOrNull() ?: design * 0.7f).coerceAtMost(design * 0.7f) / 100f).toInt() * 100f
        val yMax = ((vals.maxOrNull() ?: design.toFloat()).coerceAtLeast(design.toFloat()) / 100f + 1).toInt() * 100f
        // The curve morph is read in the draw phase only (no recomposition per frame).
        val morphing by remember(curved) { derivedStateOf { smoothState.value != (if (curved) 1f else 0f) } }
        TimeLineChart(
            series = series, xStart = { start }, xEnd = { now }, yMin = { yMin }, yMax = { yMax },
            color = Palette.blue, yLabel = PlainLabel, showDots = true,
            maxGapMs = 14 * 24 * 3_600_000L, smoothness = { smoothState.value },
            modifier = Modifier.fastFrames(morphing),
        )
        measurements.lastOrNull()?.let { m ->
            Spacer(Modifier.height(6.dp))
            Text(
                "Last: ${m.fromLevel}% → ${m.toLevel}%, ${m.mah} mAh ${if (m.charging) "in" else "out"} = ~${m.capacityMah} mAh full",
                color = Palette.muted, fontSize = 12.sp,
            )
        }
    }
}

/** Glyph points (in a 24×24 box) shared by both states; only the tangents change. */
private val GLYPH = listOf(Offset(3f, 15f), Offset(8.5f, 8f), Offset(15.5f, 16f), Offset(21f, 9f))

/** Small toggle drawing a zig-zag that morphs into a wave as [progress] goes 0 → 1. */
@Composable
private fun CurveToggle(curved: Boolean, progress: () -> Float, onChange: (Boolean) -> Unit) {
    val t = Palette.theme
    val shape = Palette.chipShape
    val square = t.hardShadow > 0.dp
    val ink = Palette.text
    Box(
        Modifier
            .size(34.dp)
            .clip(shape)
            .background(Palette.cardHi)
            .then(if (t.border > 0.dp) Modifier.border(t.border, if (square) t.ink else t.line, shape) else Modifier)
            .toggleable(value = curved, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = if (curved) "Curved line, tap for straight" else "Straight line, tap for curved" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val k = size.width / 24f
            val path = Path()
            // Glyph extremes are the "data", so the monotone cubic flattens at each peak: a wave.
            appendSmooth(path, GLYPH.map { Offset(it.x * k, it.y * k) }, progress(), moveFirst = true)
            drawPath(
                path, ink,
                style = Stroke(
                    2.dp.toPx(),
                    cap = if (square) StrokeCap.Square else StrokeCap.Round,
                    join = if (square) StrokeJoin.Miter else StrokeJoin.Round,
                ),
            )
        }
    }
}
