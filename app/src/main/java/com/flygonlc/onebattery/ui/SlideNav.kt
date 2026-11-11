package com.flygonlc.onebattery.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.floor

/**
 * Two-screen navigation (dashboard ⇄ settings): the second screen slides in from the right and back
 * out again, with the first screen easing in from a slight left offset underneath.
 *
 * System back is handled with predictive back: while the gesture is in progress the transition is
 * seeked with the gesture, and the second screen shrinks a little, rounds its corners and leans
 * toward the swipe edge. Releasing commits ([onBack]); cancelling springs it back. On devices without
 * predictive back this behaves like a plain BackHandler.
 *
 * The pixel theme (Flygon LC) moves in 8 visible steps instead of gliding. With system animations
 * off, it just switches.
 */
@Composable
internal fun SlideNav(
    showSecond: Boolean,
    onBack: () -> Unit,
    second: @Composable () -> Unit,
    first: @Composable () -> Unit,
) {
    val nav = remember { SeekableTransitionState(showSecond) }
    val scope = rememberCoroutineScope()
    val anims = animationsEnabled()
    val pixel = isPixelTheme
    val currentOnBack by rememberUpdatedState(onBack)
    var backProgress by remember { mutableFloatStateOf(0f) }
    var fromLeft by remember { mutableStateOf(true) }

    LaunchedEffect(showSecond) {
        if (showSecond) backProgress = 0f
        if (anims) nav.animateTo(showSecond) else nav.snapTo(showSecond)
    }

    PredictiveBackHandler(enabled = showSecond) { events ->
        try {
            events.collect { e ->
                backProgress = e.progress
                fromLeft = e.swipeEdge == BackEventCompat.EDGE_LEFT
                if (anims) nav.seekTo(e.progress, targetState = false)
            }
            currentOnBack()
        } catch (e: CancellationException) {
            scope.launch {
                launch { if (anims) nav.animateTo(true) else nav.snapTo(true) }
                animate(backProgress, 0f) { v, _ -> backProgress = v }
            }
            throw e
        }
    }

    val easing = if (pixel) StepEasing(8) else FastOutSlowInEasing
    val dur = if (pixel) 280 else 320
    val transition = rememberTransition(nav, label = "nav")
    // Peak refresh rate while the slide (or a predictive-back drag) is in motion.
    FastFrames(active = { transition.isRunning || nav.currentState != nav.targetState || backProgress > 0f }) {
    transition.AnimatedContent(
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally(tween(dur, easing = easing)) { it } + fadeIn(tween(dur))) togetherWith
                    (slideOutHorizontally(tween(dur, easing = easing)) { -it / 4 } + fadeOut(tween(dur)))
            } else {
                (slideInHorizontally(tween(dur, easing = easing)) { -it / 4 } + fadeIn(tween(dur))) togetherWith
                    (slideOutHorizontally(tween(dur, easing = easing)) { it } + fadeOut(tween(dur, easing = easing)))
            }.apply {
                // The second screen always stays on top, both coming and going.
                targetContentZIndex = if (targetState) 1f else -1f
            }
        },
    ) { isSecond ->
        if (isSecond) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val p = backProgress
                    if (p <= 0f) return@graphicsLayer
                    val s = 1f - 0.08f * p
                    scaleX = s; scaleY = s
                    translationX = (if (fromLeft) 1f else -1f) * 12.dp.toPx() * p
                    shape = RoundedCornerShape((Palette.radius.value.coerceAtLeast(12f) * 1.5f * p).dp)
                    clip = true
                }
            ) { second() }
        } else {
            first()
        }
    }
    }
}

/** Pixel-theme easing: [steps] discrete jumps instead of a smooth curve. */
private class StepEasing(private val steps: Int) : Easing {
    override fun transform(fraction: Float): Float =
        if (fraction >= 1f) 1f else floor(fraction * steps) / steps
}
