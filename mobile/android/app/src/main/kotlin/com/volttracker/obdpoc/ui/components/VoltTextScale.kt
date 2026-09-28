package com.volttracker.obdpoc.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Caps the text scale (the phone's font size × Settings → Text size) at [max] inside [content]:
 * for fixed-geometry art — the ring's centre, chart axis labels drawn on a canvas — whose text
 * would otherwise spill out of the shapes it labels. Everything else keeps scaling freely.
 */
@Composable
fun CappedTextScale(
    max: Float = MAX_ART_TEXT_SCALE,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val capped =
        remember(density, max) {
            if (density.fontScale <= max) density else Density(density.density, max)
        }
    CompositionLocalProvider(LocalDensity provides capped, content = content)
}

/** How far text inside fixed-geometry art may grow: 1.3× (the largest Settings → Text size is 1.5×). */
const val MAX_ART_TEXT_SCALE = 1.3f
