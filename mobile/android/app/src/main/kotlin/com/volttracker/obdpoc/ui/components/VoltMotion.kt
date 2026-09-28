package com.volttracker.obdpoc.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

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
