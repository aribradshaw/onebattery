package com.flygonlc.onebattery.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Clock-face picker for the notification update interval.
 *
 * The allowed stops sit on a 270° arc (7:30 → 12 → 4:30, like a knob); the bottom of the face is
 * left free for the readout. Drag anywhere on the face and the hand follows your finger, ticking
 * (haptic) as it passes each stop; let go and it springs onto the nearest stop. Tapping a numeral
 * selects it directly. A small marker runs around the outer rim, moving once a second and lapping
 * once per chosen interval; the rim flashes when it laps, which is when a notification rewrite
 * would happen.
 *
 * Drawn entirely in Compose Canvas. All gestures that start on the dial are consumed, so they don't
 * scroll the settings list.
 */
@Composable
internal fun IntervalDial(
    stops: List<Pair<Long, String>>,
    selectedMs: Long,
    enabled: Boolean,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = Palette.theme
    val pixel = isPixelTheme
    val soft = t.id == "othello"
    val hairline = t.id == "system"
    val anims = animationsEnabled()
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val n = stops.size
    val selectedIndex = stops.indexOfFirst { it.first == selectedMs }.takeIf { it >= 0 }
        ?: stops.indices.minByOrNull { abs(stops[it].first - selectedMs) } ?: 0
    val currentOnSelect by rememberUpdatedState(onSelect)

    fun stopAngle(i: Int) = if (n <= 1) 0f else -SPAN / 2 + i * SPAN / (n - 1)
    fun nearest(angle: Float) = stops.indices.minByOrNull { abs(stopAngle(it) - angle) } ?: 0

    val hand = remember { Animatable(stopAngle(selectedIndex)) }
    var dragAngle by remember { mutableStateOf<Float?>(null) }
    var dragIndex by remember { mutableIntStateOf(-1) }
    val springSpec = spring<Float>(dampingRatio = if (pixel) 0.75f else 0.5f, stiffness = Spring.StiffnessMediumLow)

    LaunchedEffect(selectedIndex) {
        if (dragAngle != null) return@LaunchedEffect
        if (anims) hand.animateTo(stopAngle(selectedIndex), springSpec) else hand.snapTo(stopAngle(selectedIndex))
    }

    fun tick() = view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    fun confirm() = view.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
    )

    fun select(i: Int, from: Float? = null) {
        scope.launch {
            if (from != null) hand.snapTo(from)
            dragAngle = null
            dragIndex = -1
            val target = stopAngle(i)
            if (anims) hand.animateTo(target, springSpec) else hand.snapTo(target)
        }
        confirm()
        currentOnSelect(stops[i].first)
    }

    // Rim ticker: elapsed whole seconds inside the current interval, aligned to the wall clock.
    val intervalSec = (stops[selectedIndex].first / 1000L).coerceAtLeast(1L)
    var elapsedSec by remember { mutableLongStateOf(0L) }
    val lapFlash = remember { Animatable(0f) }
    // Only ticks while the activity is resumed: a retained composition in the background must not
    // keep a 1 s timer alive.
    val resumed = isResumed()
    LaunchedEffect(intervalSec, enabled, anims, resumed) {
        if (!enabled || !anims) { elapsedSec = 0L; return@LaunchedEffect }
        if (!resumed) return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            val e = (now / 1000L) % intervalSec
            elapsedSec = e
            if (e == 0L) launch { lapFlash.snapTo(1f); lapFlash.animateTo(0f, tween(650)) }
            delay(1000L - now % 1000L)
        }
    }

    // Derived, so a drag (which moves dragAngle every frame) doesn't recompose the dial.
    val dialMoving by remember { derivedStateOf { dragAngle != null || hand.isRunning } }
    val shown = if (dragIndex >= 0) dragIndex else selectedIndex
    val accent = Palette.accent
    val ink = Palette.text
    val muted = Palette.muted
    val face = Palette.cardHi
    val line = if (pixel) t.ink else t.line
    val shortLabels = stops.map { it.second.replace(" min", "m").replace(" s", "s") }
    val numeralStyle = TextStyle(
        fontFamily = Palette.heading,
        fontSize = if (pixel) 9.sp else if (hairline) 15.sp else 13.sp,
        fontWeight = if (hairline) FontWeight.Normal else FontWeight.SemiBold,
    )

    Box(
        modifier.fillMaxWidth().widthIn(max = 280.dp).aspectRatio(1f)
            .alpha(if (enabled) 1f else 0.45f)
            .semantics {
                contentDescription = "Notification update interval"
                stateDescription = "every ${stops[shown].second}"
                progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat(), 0f..(n - 1).toFloat(), (n - 2).coerceAtLeast(0))
                if (enabled) setProgress { v -> select(v.roundToInt().coerceIn(0, n - 1)); true }
            }
            .pointerInput(enabled, n, pixel, anims) {
                if (!enabled) return@pointerInput
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val radius = size.width / 2f
                    var dragging = false
                    var finished = false
                    var last = hand.value
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        val pos = ch.position
                        if (!ch.pressed) {
                            ch.consume()
                            finished = true
                            if (dragging) {
                                select(nearest(last), from = last)
                            } else {
                                // Tap: a numeral if one is close, else the nearest stop by direction.
                                val hit = stops.indices.minByOrNull { i ->
                                    (numeralPos(c, radius, stopAngle(i)) - pos).getDistance()
                                }
                                val d = (pos - c).getDistance()
                                if (hit != null && (numeralPos(c, radius, stopAngle(hit)) - pos).getDistance() < 30.dp.toPx()) {
                                    select(hit)
                                } else if (d > radius * 0.3f) {
                                    select(nearest(clampAngle(angleOf(c, pos), last)))
                                }
                            }
                            break
                        }
                        if (!dragging && (pos - down.position).getDistance() > slop) {
                            dragging = true
                        }
                        if (dragging && (pos - c).getDistance() > 14.dp.toPx()) {
                            val a = clampAngle(angleOf(c, pos), last)
                            last = a
                            dragAngle = a
                            val idx = nearest(a)
                            if (idx != dragIndex) {
                                if (dragIndex >= 0) tick()
                                dragIndex = idx
                            }
                        }
                        ch.consume()
                    }
                    // Gesture cancelled (e.g. pointer lost) mid-drag: settle on where it was.
                    if (!finished && dragging) select(nearest(last), from = last)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Peak refresh rate while the hand is dragged or swings to a stop.
        Canvas(Modifier.fillMaxSize().fastFrames(dialMoving)) {
            val r = size.minDimension / 2f
            val c = center
            val angle = dragAngle ?: hand.value
            drawFace(c, r, face, line, pixel, soft, hairline)

            // Outer rim: interval ticker.
            val rimR = r - (if (pixel) 7.dp else 6.dp).toPx()
            val lap = if (intervalSec > 0) elapsedSec.toFloat() / intervalSec else 0f
            val rimW = (if (hairline) 1.5.dp else if (pixel) 3.dp else 2.5.dp).toPx()
            if (enabled && anims) {
                drawArc(
                    accent.copy(alpha = 0.22f), -90f, 360f * lap, false,
                    Offset(c.x - rimR, c.y - rimR), Size(rimR * 2, rimR * 2),
                    style = Stroke(rimW, cap = if (pixel || hairline) StrokeCap.Butt else StrokeCap.Round),
                )
                val m = polar(c, rimR, 360f * lap)
                if (pixel) {
                    val s = 5.dp.toPx()
                    drawRect(accent, Offset(m.x - s / 2, m.y - s / 2), Size(s, s))
                } else drawCircle(accent, rimW * 1.3f, m)
                if (lapFlash.value > 0f) {
                    drawCircle(
                        accent.copy(alpha = 0.5f * lapFlash.value), rimR + (1f - lapFlash.value) * 5.dp.toPx(), c,
                        style = Stroke(rimW),
                    )
                }
            }

            // Ticks: 60 clock minutes around the full face, stops emphasised.
            val tickOuter = r - 14.dp.toPx()
            for (i in 0 until 60) {
                val a = i * 6f
                val signed = if (a > 180f) a - 360f else a
                val inSpan = abs(signed) <= SPAN / 2 + 0.01f
                val major = i % 5 == 0
                val col = when {
                    !inSpan -> muted.copy(alpha = 0.18f)
                    major -> ink.copy(alpha = if (t.dark) 0.55f else 0.7f)
                    else -> muted.copy(alpha = if (t.dark) 0.3f else 0.4f)
                }
                drawTick(c, tickOuter, a, major, col, pixel, soft, hairline)
            }

            // Covered span from the first stop to the hand.
            val arcR = r * 0.58f
            val span0 = -SPAN / 2
            drawArc(
                Palette.grid, span0 - 90f, SPAN, false,
                Offset(c.x - arcR, c.y - arcR), Size(arcR * 2, arcR * 2),
                style = Stroke((if (hairline) 1.dp else 4.dp).toPx(), cap = if (pixel || hairline) StrokeCap.Butt else StrokeCap.Round),
            )
            drawArc(
                accent.copy(alpha = if (t.dark) 0.55f else 0.4f), span0 - 90f, (angle - span0).coerceIn(0f, SPAN), false,
                Offset(c.x - arcR, c.y - arcR), Size(arcR * 2, arcR * 2),
                style = Stroke((if (hairline) 1.5.dp else 4.dp).toPx(), cap = if (pixel || hairline) StrokeCap.Butt else StrokeCap.Round),
            )

            // Numerals.
            stops.indices.forEach { i ->
                val sel = i == shown
                val layout = measurer.measure(
                    shortLabels[i],
                    numeralStyle.copy(color = if (sel) Palette.onAccent else if (t.dark) muted else ink),
                )
                val p = numeralPos(c, r, stopAngle(i))
                val w = layout.size.width.toFloat()
                val h = layout.size.height.toFloat()
                if (sel) {
                    val padX = 7.dp.toPx(); val padY = 4.dp.toPx()
                    val tl = Offset(p.x - w / 2 - padX, p.y - h / 2 - padY)
                    val sz = Size(w + padX * 2, h + padY * 2)
                    val cr = if (soft) sz.height / 2 else if (pixel) 0f else t.chipRadius.toPx().coerceAtMost(sz.height / 2)
                    if (pixel) drawRect(t.ink, tl + Offset(2.dp.toPx(), 2.dp.toPx()), sz)
                    drawRoundRect(accent, tl, sz, CornerRadius(cr))
                }
                drawText(layout, topLeft = Offset(p.x - w / 2, p.y - h / 2))
            }

            drawHand(c, r * 0.66f, angle, accent, ink, pixel, soft, hairline, t.dark)
        }

        // Readout in the free bottom segment of the face.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(BiasAlignment(0f, 0.6f)),
        ) {
            Text("every", color = Palette.muted, fontSize = 11.sp)
            Text(
                stops[shown].second,
                color = Palette.text, fontFamily = Palette.heading,
                fontSize = Palette.headingSize(22.sp), fontWeight = FontWeight.Bold,
            )
        }
    }
}

private const val SPAN = 270f

/** Clockwise degrees from 12 o'clock, in (-180, 180]. */
private fun angleOf(c: Offset, p: Offset): Float =
    Math.toDegrees(atan2((p.x - c.x).toDouble(), (c.y - p.y).toDouble())).toFloat()

/** Keeps the finger angle on the 270° span; inside the bottom gap, sticks to the end it came from. */
private fun clampAngle(a: Float, previous: Float): Float {
    val half = SPAN / 2
    if (abs(a) <= half) return a
    return if (previous >= 0f) half else -half
}

private fun polar(c: Offset, r: Float, deg: Float): Offset {
    val rad = Math.toRadians(deg.toDouble())
    return Offset(c.x + r * sin(rad).toFloat(), c.y - r * cos(rad).toFloat())
}

private fun numeralPos(c: Offset, r: Float, deg: Float) = polar(c, r * 0.8f, deg)

private fun DrawScope.drawFace(c: Offset, r: Float, face: Color, line: Color, pixel: Boolean, soft: Boolean, hairline: Boolean) {
    val inner = r - 1.dp.toPx()
    when {
        pixel -> {
            val o = 3.dp.toPx()
            drawCircle(line, inner - o, c + Offset(o, o))
            drawCircle(face, inner - o, c)
            drawCircle(line, inner - o, c, style = Stroke(2.dp.toPx()))
        }
        soft -> drawCircle(face, inner, c)
        hairline -> {
            drawCircle(face, inner, c)
            drawCircle(line, inner, c, style = Stroke(1.dp.toPx()))
        }
        else -> {
            drawCircle(face, inner, c)
            drawCircle(line.copy(alpha = 0.6f), inner, c, style = Stroke(1.dp.toPx()))
        }
    }
}

private fun DrawScope.drawTick(c: Offset, outer: Float, deg: Float, major: Boolean, color: Color, pixel: Boolean, soft: Boolean, hairline: Boolean) {
    if (pixel) {
        // Axis-aligned blocks: chunky "pixels" rather than rotated lines.
        val s = (if (major) 5.dp else 2.5.dp).toPx()
        val p = polar(c, outer - s, deg)
        drawRect(color, Offset(p.x - s / 2, p.y - s / 2), Size(s, s))
        return
    }
    val len = (if (major) (if (hairline) 9.dp else 7.dp) else (if (hairline) 5.dp else 3.dp)).toPx()
    val w = when {
        hairline -> if (major) 1.5.dp.toPx() else 1f
        soft -> if (major) 3.dp.toPx() else 2.dp.toPx()
        else -> if (major) 2.dp.toPx() else 1.5.dp.toPx()
    }
    rotate(deg, c) {
        drawLine(color, Offset(c.x, c.y - outer), Offset(c.x, c.y - outer + len), w, cap = if (soft) StrokeCap.Round else StrokeCap.Butt)
    }
}

private fun DrawScope.drawHand(c: Offset, len: Float, deg: Float, accent: Color, ink: Color, pixel: Boolean, soft: Boolean, hairline: Boolean, dark: Boolean) {
    rotate(deg, c) {
        when {
            pixel -> {
                val w = 5.dp.toPx()
                val o = 2.dp.toPx()
                // Hard ink shadow, then the hand as stacked blocks.
                drawRect(ink, Offset(c.x - w / 2 + o, c.y - len + o), Size(w, len))
                drawRect(accent, Offset(c.x - w / 2, c.y - len), Size(w, len))
                val hub = 12.dp.toPx()
                drawRect(ink, Offset(c.x - hub / 2 + o, c.y - hub / 2 + o), Size(hub, hub))
                drawRect(accent, Offset(c.x - hub / 2, c.y - hub / 2), Size(hub, hub))
                drawRect(ink, Offset(c.x - 2.dp.toPx(), c.y - 2.dp.toPx()), Size(4.dp.toPx(), 4.dp.toPx()))
            }
            hairline -> {
                drawLine(accent, Offset(c.x, c.y + len * 0.16f), Offset(c.x, c.y - len), 1.5.dp.toPx())
                drawCircle(accent, 4.dp.toPx(), c)
                drawCircle(Color.White, 1.5.dp.toPx(), c)
            }
            else -> {
                val w = if (soft) 6.dp.toPx() else 4.dp.toPx()
                drawLine(accent, c, Offset(c.x, c.y - len), w, cap = StrokeCap.Round)
                drawCircle(accent, if (soft) 9.dp.toPx() else 7.dp.toPx(), c)
                drawCircle(if (dark) Color.Black.copy(alpha = 0.5f) else Color.White, 3.dp.toPx(), c)
            }
        }
    }
}
