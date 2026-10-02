package com.volttracker.obdpoc.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.roundToInt

/**
 * The app's motion vocabulary: three durations and one easing, so every transition feels like the
 * same hand made it. Everything here stands still when the phone's "remove animations" setting is
 * on ([VoltPrefs.reduceMotion]).
 */
object VoltMotion {
    /** Press feedback and small color changes. */
    const val FAST_MS = 120

    /** Tab and state changes. */
    const val STANDARD_MS = 220

    /** Pushing and popping a screen. */
    const val SCREEN_MS = 300

    /** Quick to start, gentle to land (Material's emphasized-decelerate curve). */
    val Ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** A finite spec of [ms], or no animation at all under reduce-motion. */
    fun <T> spec(
        ms: Int,
        reduceMotion: Boolean,
    ): FiniteAnimationSpec<T> = if (reduceMotion) snap() else tween(ms, easing = Ease)

    /**
     * How a live reading moves to its next value: a critically damped spring, so a needle that is
     * retargeted every poll keeps its momentum and never overshoots into a value the car didn't
     * report.
     */
    fun <T> glide(reduceMotion: Boolean): AnimationSpec<T> =
        if (reduceMotion) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = GLIDE_STIFFNESS)

    private const val GLIDE_STIFFNESS = 90f
}

/**
 * [target], arriving smoothly rather than jumping; the first value is shown as-is. A [State], so a
 * canvas can read it while drawing and redraw without recomposing anything.
 */
@Composable
fun glideState(
    target: Float,
    label: String,
): State<Float> = animateFloatAsState(target, VoltMotion.glide(LocalVoltPrefs.current.reduceMotion), label = label)

/** A whole-number reading counting toward [target]; recomposes only when the shown number changes. */
@Composable
fun glideWhole(
    target: Int,
    label: String,
): Int {
    val value = glideState(target.toFloat(), label)
    val whole by remember { derivedStateOf { value.value.roundToInt() } }
    return whole
}

/** A one-decimal reading moving toward [target]; recomposes only when the shown tenth changes. */
@Composable
fun glideTenths(
    target: Double,
    label: String,
): Double {
    val value = glideState(target.toFloat(), label)
    val tenths by remember { derivedStateOf { (value.value * TENTHS).roundToInt() } }
    return tenths / TENTHS.toDouble()
}

private const val TENTHS = 10f

/** [target], cross-fading from the previous color. */
@Composable
fun glideColor(
    target: Color,
    label: String,
    ms: Int = VoltMotion.STANDARD_MS,
): Color {
    val value by animateColorAsState(target, VoltMotion.spec(ms, LocalVoltPrefs.current.reduceMotion), label = label)
    return value
}

/** Shrinks the control slightly while a finger is on it, so a tap feels like it landed. */
fun Modifier.pressScale(
    interactions: InteractionSource,
    pressed: Float = PRESSED_SCALE,
): Modifier =
    composed {
        val down by interactions.collectIsPressedAsState()
        val scale by animateFloatAsState(
            if (down) pressed else 1f,
            VoltMotion.spec(VoltMotion.FAST_MS, LocalVoltPrefs.current.reduceMotion),
            label = "press",
        )
        graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    }

private const val PRESSED_SCALE = 0.96f

/**
 * A 0 → [period] phase that loops every [durationMs] while [active], for the ring's charge
 * shimmer and the energy-flow dashes. Null while idle or when the phone's "remove animations"
 * setting is on, so nothing redraws every frame for a screen that isn't changing.
 */
@Composable
fun rememberLoopPhase(
    active: Boolean,
    period: Float,
    durationMs: Int,
    label: String,
): Float? {
    if (!active || LocalVoltPrefs.current.reduceMotion) return null
    val phase by rememberInfiniteTransition(label = label).animateFloat(
        initialValue = 0f,
        targetValue = period,
        animationSpec = infiniteRepeatable(tween(durationMs, easing = LinearEasing), RepeatMode.Restart),
        label = label,
    )
    return phase
}
