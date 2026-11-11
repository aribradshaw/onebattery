package com.flygonlc.onebattery.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/** A time span to highlight behind the line (e.g. charging). */
data class Band(val from: Long, val to: Long)

/**
 * A chart's points as parallel primitive arrays sorted by time: no boxing, cheap to binary-search
 * and decimate every frame of a pinch. Build once per data load (off the main thread for history).
 */
@Immutable
internal class ChartSeries(val times: LongArray, val values: FloatArray) {
    val size get() = times.size

    /** Smallest and largest value with time in [from, to] (both inclusive); null when none. */
    fun minMaxIn(from: Long, to: Long): Pair<Float, Float>? {
        val lo = lowerBound(times, from)
        val hi = lowerBound(times, if (to == Long.MAX_VALUE) to else to + 1)
        if (lo >= hi) return null
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        var i = lo
        // Partial first block, whole blocks from the precomputed index, partial last block.
        while (i < hi && i % BLOCK != 0) { mn = min(mn, values[i]); mx = max(mx, values[i]); i++ }
        while (i + BLOCK <= hi) {
            val b = i / BLOCK
            mn = min(mn, blockMin[b]); mx = max(mx, blockMax[b])
            i += BLOCK
        }
        while (i < hi) { mn = min(mn, values[i]); mx = max(mx, values[i]); i++ }
        return mn to mx
    }

    private val blockMin: FloatArray
    private val blockMax: FloatArray

    init {
        val blocks = (values.size + BLOCK - 1) / BLOCK
        blockMin = FloatArray(blocks) { Float.MAX_VALUE }
        blockMax = FloatArray(blocks) { -Float.MAX_VALUE }
        for (i in values.indices) {
            val b = i / BLOCK
            if (values[i] < blockMin[b]) blockMin[b] = values[i]
            if (values[i] > blockMax[b]) blockMax[b] = values[i]
        }
    }

    private companion object {
        const val BLOCK = 64
    }
}

internal fun List<Pair<Long, Float>>.toSeries(): ChartSeries =
    ChartSeries(LongArray(size) { this[it].first }, FloatArray(size) { this[it].second })

/** Horizontal insets of the plot area inside [TimeLineChart]; gesture code maps touches with these. */
internal val ChartPadLeft = 40.dp
internal val ChartPadRight = 4.dp

private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR

/** Candidate x-axis tick steps, smallest first. */
private val TICK_STEPS = longArrayOf(
    15 * 60_000L, 30 * 60_000L, HOUR, 2 * HOUR, 3 * HOUR, 6 * HOUR, 12 * HOUR,
    DAY, 2 * DAY, 7 * DAY, 14 * DAY, 30 * DAY, 60 * DAY, 90 * DAY, 180 * DAY,
)

/**
 * Line chart over time. [points] must be sorted by time; anything outside [xStart, xEnd] is
 * clipped, so callers can pass a full history and just move the window.
 *
 * [smoothness] blends each segment from a straight line (0) to a monotone cubic (1); the
 * monotone (Steffen) tangents never overshoot the data, so a 0→1 animation is a clean morph.
 */
@Composable
fun TimeLineChart(
    points: List<Pair<Long, Float>>,
    xStart: Long,
    xEnd: Long,
    yMin: Float,
    yMax: Float,
    color: Color,
    yLabel: (Float) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    bands: List<Band> = emptyList(),
    showDots: Boolean = false,
    maxGapMs: Long = 2 * 60 * 60 * 1000L,
    smoothness: Float = 0f,
) {
    val series = remember(points) { points.toSeries() }
    TimeLineChart(
        series = series, xStart = { xStart }, xEnd = { xEnd }, yMin = { yMin }, yMax = { yMax },
        color = color, yLabel = yLabel, modifier = modifier, height = height, bands = bands,
        showDots = showDots, maxGapMs = maxGapMs, smoothness = { smoothness },
    )
}

/**
 * [TimeLineChart] whose window, y-range and smoothness are read in the draw phase: a pinch, a range
 * glide or a curve morph only redraws the canvas, without recomposing anything.
 */
@Composable
internal fun TimeLineChart(
    series: ChartSeries,
    xStart: () -> Long,
    xEnd: () -> Long,
    yMin: () -> Float,
    yMax: () -> Float,
    color: Color,
    yLabel: (Float) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    bands: List<Band> = emptyList(),
    showDots: Boolean = false,
    maxGapMs: Long = 2 * 60 * 60 * 1000L,
    smoothness: () -> Float = { 0f },
) {
    if (series.size < 2 && !showDots || series.size == 0) {
        Box(modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Text("Collecting data… check back soon", color = Palette.muted, fontSize = 13.sp)
        }
        return
    }
    // Room for every y label plus the tick labels of a few zoom levels, so panning reuses layouts.
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val labelStyle = TextStyle(color = Palette.muted, fontSize = 10.sp)
    val square = Palette.theme.hardShadow > 0.dp
    val gridColor = Palette.grid
    val bandColor = Palette.band
    val scratch = remember { ChartScratch() }

    Canvas(modifier.fillMaxWidth().height(height)) {
        val xStart = xStart()
        val xEnd = xEnd()
        val yMin = yMin()
        val yMax = yMax()
        val spanMs = (xEnd - xStart).coerceAtLeast(1L)
        val left = ChartPadLeft.toPx()
        val bottom = size.height - 18.dp.toPx()
        val top = 6.dp.toPx()
        val right = size.width - ChartPadRight.toPx()
        val w = right - left
        val h = bottom - top
        val range = (yMax - yMin).takeIf { it > 0f } ?: 1f
        fun x(t: Long) = left + (t - xStart).toFloat() / spanMs * w
        fun y(v: Float) = bottom - (v.coerceIn(yMin, yMax) - yMin) / range * h

        // Horizontal gridlines + y labels.
        for (i in 0..4) {
            val v = yMin + range * i / 4
            val yy = y(v)
            drawLine(gridColor, Offset(left, yy), Offset(right, yy), 1f)
            val layout = measurer.measure(yLabel(v), labelStyle)
            drawText(layout, topLeft = Offset(left - layout.size.width - 6.dp.toPx(), yy - layout.size.height / 2))
        }

        // Time ticks: pick a step that gives labels at least ~56dp apart, aligned to local time.
        val minStep = spanMs * (56.dp.toPx() / w)
        val step = TICK_STEPS.firstOrNull { it >= minStep } ?: TICK_STEPS.last()
        val cal = scratch.calendar()
        val ticks = ticks(cal, xStart, xEnd, step)
        val tickFmt = scratch.format(if (step < DAY) "HH:mm" else "MMM d")
        val dayFmt = scratch.format(if (spanMs > 3 * DAY) "MMM d" else "EEE")
        val tickColor = gridColor.copy(alpha = gridColor.alpha * 0.55f)
        var lastLabelEnd = Float.NEGATIVE_INFINITY
        for (t in ticks) {
            val xx = x(t)
            drawLine(tickColor, Offset(xx, top), Offset(xx, bottom), 1f)
            // On hour-scale axes, midnight ticks name the day instead of saying 00:00.
            val date = scratch.date(t)
            val label = if (step < DAY && isLocalMidnight(cal, t)) dayFmt.format(date) else tickFmt.format(date)
            val layout = measurer.measure(label, labelStyle)
            val lx = (xx - layout.size.width / 2).coerceIn(left, right - layout.size.width)
            if (lx < lastLabelEnd + 4.dp.toPx()) continue
            drawText(layout, topLeft = Offset(lx, bottom + 4.dp.toPx()))
            lastLabelEnd = lx + layout.size.width
        }

        // Clip to the plot so a zoomed window doesn't spill into the axes; dots get a little slack.
        val slack = if (showDots) 4.dp.toPx() else 0f
        clipRect(left - slack, top - 4.dp.toPx(), right + slack, bottom + 2.dp.toPx()) {
            for (b in bands) {
                if (b.to < xStart || b.from > xEnd) continue
                val x1 = x(b.from.coerceAtLeast(xStart))
                val x2 = x(b.to.coerceAtMost(xEnd))
                if (x2 > x1) drawRect(bandColor, Offset(x1, top), Size(x2 - x1, h))
            }

            // Visible slice (+1 point either side so lines run off the edges), decimated to
            // ~one point per 2px so a year of 5-minute samples still draws quickly.
            val from = (lowerBound(series.times, xStart) - 1).coerceAtLeast(0)
            val to = min(series.size, lowerBound(series.times, xEnd + 1) + 1)
            if (from >= to) return@clipRect
            val buckets = (w / 2f).toInt().coerceAtLeast(16)
            val n = scratch.decimate(series, from, to, xStart, spanMs, buckets)
            val bucketMs = if (n < to - from) spanMs / buckets else 0L
            // Merged buckets sit ~bucketMs apart; only break where whole buckets are empty.
            val gap = max(maxGapMs, bucketMs * 3 / 2)
            val ts = scratch.t
            val xs = scratch.xs
            val ys = scratch.ys
            for (i in 0 until n) { xs[i] = x(ts[i]); ys[i] = y(scratch.v[i]) }

            val s = smoothness()
            val line = scratch.line.apply { rewind() }
            val fill = scratch.fill.apply { rewind() }
            var segStart = 0
            for (i in 1..n) {
                if (i == n || ts[i] - ts[i - 1] > gap) {
                    appendSmooth(line, xs, ys, segStart, i, s, scratch, moveFirst = true)
                    appendSmooth(fill, xs, ys, segStart, i, s, scratch, moveFirst = true)
                    fill.lineTo(xs[i - 1], bottom); fill.lineTo(xs[segStart], bottom); fill.close()
                    segStart = i
                }
            }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0f)), top, bottom))
            drawPath(
                line, color,
                style = Stroke(
                    2.dp.toPx(),
                    cap = if (square) StrokeCap.Square else StrokeCap.Round,
                    join = if (square) StrokeJoin.Miter else StrokeJoin.Round,
                ),
            )
            if (showDots) {
                val r = 3.dp.toPx()
                for (i in 0 until n) drawCircle(color, r, Offset(xs[i], ys[i]))
            }
        }
    }
}

/**
 * Per-chart buffers reused across draws (paths, decimation output, slopes, date formatting), so a
 * pinch doesn't allocate a fresh set every frame. Only touched from the draw pass.
 */
private class ChartScratch {
    val line = Path()
    val fill = Path()
    var t = LongArray(0)
    var v = FloatArray(0)
    var xs = FloatArray(0)
    var ys = FloatArray(0)
    var slopes = FloatArray(0)
    private val formats = HashMap<String, SimpleDateFormat>()
    private var locale: Locale? = null
    private var tz: TimeZone? = null
    private val cal: Calendar = Calendar.getInstance()
    private val dateObj = Date()

    fun ensure(n: Int) {
        if (t.size >= n) return
        val cap = max(n, t.size * 2)
        t = LongArray(cap); v = FloatArray(cap); xs = FloatArray(cap); ys = FloatArray(cap)
    }

    fun slopes(n: Int): FloatArray {
        if (slopes.size < n) slopes = FloatArray(max(n, slopes.size * 2))
        return slopes
    }

    /** Calendar in the current default time zone (re-synced each draw, in case it changed). */
    fun calendar(): Calendar {
        val zone = TimeZone.getDefault()
        if (zone != tz) { tz = zone; cal.timeZone = zone; formats.clear() }
        return cal
    }

    fun format(pattern: String): SimpleDateFormat {
        val l = Locale.getDefault()
        if (l != locale) { locale = l; formats.clear() }
        return formats.getOrPut(pattern) { SimpleDateFormat(pattern, l).also { f -> tz?.let { f.timeZone = it } } }
    }

    fun date(t: Long): Date = dateObj.apply { time = t }

    /**
     * Averages points sharing a pixel-ish bucket into [t]/[v]; copies the slice unchanged when it's
     * already sparse. Returns the number of points written.
     */
    fun decimate(series: ChartSeries, from: Int, to: Int, xStart: Long, spanMs: Long, buckets: Int): Int {
        val times = series.times
        val values = series.values
        if (to - from <= buckets * 2) {
            ensure(to - from)
            System.arraycopy(times, from, t, 0, to - from)
            System.arraycopy(values, from, v, 0, to - from)
            return to - from
        }
        ensure(buckets + 4)
        var n = 0
        var curBucket = Long.MIN_VALUE
        var sumT = 0.0
        var sumV = 0.0
        var count = 0
        for (i in from until to) {
            val tt = times[i]
            val b = Math.floorDiv((tt - xStart) * buckets, spanMs)
            if (b != curBucket) {
                if (count > 0) {
                    if (n == t.size) grow()
                    t[n] = (sumT / count).toLong(); v[n] = (sumV / count).toFloat(); n++
                }
                curBucket = b; sumT = 0.0; sumV = 0.0; count = 0
            }
            sumT += tt; sumV += values[i]; count++
        }
        if (count > 0) {
            if (n == t.size) grow()
            t[n] = (sumT / count).toLong(); v[n] = (sumV / count).toFloat(); n++
        }
        return n
    }

    private fun grow() {
        val cap = t.size * 2
        t = t.copyOf(cap); v = v.copyOf(cap); xs = FloatArray(cap); ys = FloatArray(cap)
    }
}

/**
 * Adds points [from, to) of [xs]/[ys] to [path] as cubic segments. Control points are lerped between
 * the straight-line thirds (smoothness 0) and monotone-cubic tangents (smoothness 1).
 */
private fun appendSmooth(
    path: Path, xs: FloatArray, ys: FloatArray, from: Int, to: Int, smoothness: Float,
    scratch: ChartScratch, moveFirst: Boolean,
) {
    val n = to - from
    if (n <= 0) return
    if (moveFirst) path.moveTo(xs[from], ys[from]) else path.lineTo(xs[from], ys[from])
    if (n == 1) return
    val s = smoothness.coerceIn(0f, 1f)
    val m = if (s > 0f) monotoneSlopes(xs, ys, from, n, scratch.slopes(n)) else null
    for (k in 0 until n - 1) {
        val i = from + k
        val ax = xs[i]; val ay = ys[i]
        val bx = xs[i + 1]; val by = ys[i + 1]
        val dx = bx - ax
        if (m == null || dx <= 0f) { path.lineTo(bx, by); continue }
        val chord = (by - ay) / dx
        val m0 = chord + (m[k] - chord) * s
        val m1 = chord + (m[k + 1] - chord) * s
        path.cubicTo(ax + dx / 3f, ay + m0 * dx / 3f, bx - dx / 3f, by - m1 * dx / 3f, bx, by)
    }
}

/** List form, for small fixed shapes (the curve toggle glyph). */
internal fun appendSmooth(path: Path, pts: List<Offset>, smoothness: Float, moveFirst: Boolean) {
    val xs = FloatArray(pts.size) { pts[it].x }
    val ys = FloatArray(pts.size) { pts[it].y }
    appendSmooth(path, xs, ys, 0, pts.size, smoothness, ChartScratch(), moveFirst)
}

/** Steffen / d3 "monotoneX" tangents for points [from, from + n): zero at local extrema, never overshooting. */
private fun monotoneSlopes(xs: FloatArray, ys: FloatArray, from: Int, n: Int, m: FloatArray): FloatArray {
    fun secant(k: Int): Float {
        val i = from + k
        val dx = xs[i + 1] - xs[i]
        return if (dx > 0f) (ys[i + 1] - ys[i]) / dx else 0f
    }
    if (n == 2) { m[0] = secant(0); m[1] = m[0]; return m }
    for (k in 1 until n - 1) {
        val i = from + k
        val h0 = xs[i] - xs[i - 1]
        val h1 = xs[i + 1] - xs[i]
        val s0 = secant(k - 1)
        val s1 = secant(k)
        val pp = if (h0 + h1 > 0f) (s0 * h1 + s1 * h0) / (h0 + h1) else 0f
        m[k] = (sign(s0) + sign(s1)) * min(min(abs(s0), abs(s1)), 0.5f * abs(pp))
    }
    fun end(s: Float, inner: Float): Float {
        val e = (3f * s - inner) / 2f
        return when {
            sign(e) != sign(s) -> 0f
            abs(e) > 3f * abs(s) -> 3f * s
            else -> e
        }
    }
    m[0] = end(secant(0), m[1])
    m[n - 1] = end(secant(n - 2), m[n - 2])
    return m
}

/** First index whose time is >= [t]. */
private fun lowerBound(times: LongArray, t: Long): Int {
    var lo = 0
    var hi = times.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (times[mid] < t) lo = mid + 1 else hi = mid
    }
    return lo
}

private fun ticks(cal: Calendar, start: Long, end: Long, step: Long): List<Long> {
    cal.timeInMillis = start
    if (step < DAY) {
        // Align to the step within the local day (e.g. 00:00, 06:00, 12:00…).
        val stepMin = (step / 60_000L).toInt()
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.MINUTE, (minuteOfDay / stepMin) * stepMin)
        val out = ArrayList<Long>()
        while (cal.timeInMillis <= end && out.size < 64) {
            if (cal.timeInMillis >= start) out += cal.timeInMillis
            cal.add(Calendar.MINUTE, stepMin)
        }
        return out
    }
    // Day steps: local midnights, aligned to a fixed epoch-day grid so ticks don't shuffle while panning.
    val stepDays = (step / DAY).toInt()
    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    val tz = cal.timeZone
    val epochDay = Math.floorDiv(cal.timeInMillis + tz.getOffset(cal.timeInMillis), DAY)
    cal.add(Calendar.DAY_OF_YEAR, -Math.floorMod(epochDay, stepDays.toLong()).toInt())
    val out = ArrayList<Long>()
    while (cal.timeInMillis <= end && out.size < 64) {
        if (cal.timeInMillis >= start) out += cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, stepDays)
    }
    return out
}

private fun isLocalMidnight(cal: Calendar, t: Long): Boolean {
    cal.timeInMillis = t
    return cal.get(Calendar.HOUR_OF_DAY) == 0 && cal.get(Calendar.MINUTE) == 0
}
