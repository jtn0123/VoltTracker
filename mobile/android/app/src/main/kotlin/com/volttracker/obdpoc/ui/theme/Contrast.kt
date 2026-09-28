package com.volttracker.obdpoc.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

/**
 * WCAG 2.x contrast ratio of [foreground] drawn on [background] (1:1 to 21:1). A translucent
 * foreground is first composited over the (opaque) background, as the screen would show it.
 */
fun contrastRatio(
    foreground: Color,
    background: Color,
): Double {
    val bg = background.copy(alpha = 1f)
    val fg = if (foreground.alpha < 1f) foreground.compositeOver(bg) else foreground
    val a = fg.luminance().toDouble()
    val b = bg.luminance().toDouble()
    return (maxOf(a, b) + LUMINANCE_FLARE) / (minOf(a, b) + LUMINANCE_FLARE)
}

/** WCAG AA for body-size text (under 18sp regular / 14sp bold). */
const val WCAG_AA_TEXT = 4.5

private const val LUMINANCE_FLARE = 0.05
