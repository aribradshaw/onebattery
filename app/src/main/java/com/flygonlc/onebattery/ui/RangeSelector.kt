package com.flygonlc.onebattery.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

private val TrackHeight = 38.dp
private val TrackInset = 3.dp

/**
 * Full-width segmented control for the time range. Segments share the width equally; an accent
 * indicator slides between them on a spring (8 visible steps in the pixel theme, a jump with
 * animations off). [selected] == null means a custom period (pinch-zoomed history): the indicator
 * fades out where it was and all labels go muted, then fades back in and slides when a range is
 * picked again.
 */
@Composable
internal fun RangeChips(selected: Range?, onSelect: (Range) -> Unit, modifier: Modifier = Modifier) {
    val t = Palette.theme
    val ranges = Range.entries
    val anims = animationsEnabled()
    val pixel = isPixelTheme

    // Where the indicator rests while nothing is selected: the last real selection.
    var lastIndex by remember { mutableIntStateOf(selected?.ordinal ?: 0) }
    if (selected != null) lastIndex = selected.ordinal
    val position = remember { Animatable(lastIndex.toFloat()) }
    LaunchedEffect(lastIndex, anims, pixel) {
        val target = lastIndex.toFloat()
        when {
            !anims -> position.snapTo(target)
            pixel -> position.animateTo(target, tween(220, easing = PixelSteps))
            else -> position.animateTo(target, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
        }
    }
    val shown by animateFloatAsState(
        targetValue = if (selected != null) 1f else 0f,
        animationSpec = if (anims) tween(if (pixel) 160 else 220) else tween(0),
        label = "rangeIndicator",
    )

    val trackShape = Palette.chipShape
    val innerShape = RoundedCornerShape((t.chipRadius - TrackInset).coerceAtLeast(2.dp))
    val outline = if (t.hardShadow > 0.dp) t.ink else t.line

    BoxWithConstraints(
        modifier.fillMaxWidth().height(TrackHeight).clip(trackShape).background(Palette.cardHi)
            .then(if (t.border > 0.dp) Modifier.border(t.border, outline, trackShape) else Modifier)
            .padding(TrackInset + t.border),
    ) {
        val segment = maxWidth / ranges.size
        Box(
            Modifier.width(segment).fillMaxHeight()
                .fastFrames(position.isRunning)
                .offset { IntOffset((segment.toPx() * position.value).roundToInt(), 0) }
                .graphicsLayer { alpha = if (pixel) (shown * 4).roundToInt() / 4f else shown }
                .clip(innerShape).background(Palette.accent)
                .then(if (pixel) Modifier.border(t.border, t.ink, innerShape) else Modifier),
        )
        Row(Modifier.fillMaxSize().selectableGroup()) {
            ranges.forEach { r ->
                // How much of the indicator sits on this segment (0..1), faded with it.
                val cover = (1f - abs(position.value - r.ordinal)).coerceIn(0f, 1f) * shown
                val base = lerp(Palette.muted, Palette.text, shown)
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(innerShape)
                        .selectable(selected = r == selected, role = Role.Tab, onClick = { onSelect(r) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        r.label,
                        color = lerp(base, Palette.onAccent, cover),
                        fontSize = 13.sp,
                        fontWeight = if (r == selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Pixel-theme motion: 6 visible jumps instead of a glide. */
private val PixelSteps = Easing { f -> if (f >= 1f) 1f else stepped(f, 6) }
