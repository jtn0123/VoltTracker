package com.volttracker.obdpoc.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import com.volttracker.obdpoc.ui.theme.VoltPalette

/**
 * The screen's top glow (mockups `ambient`): CSS `radial-gradient(120% 55% at 50% -8%, glow,
 * transparent 62%)`, drawn as a squashed circle. Transparent draws nothing.
 */
fun Modifier.voltAmbient(glow: Color): Modifier =
    drawBehind {
        if (glow.alpha == 0f) return@drawBehind
        val rx = size.width * AMBIENT_RX
        val ry = size.height * AMBIENT_RY
        val center = Offset(size.width / 2, -size.height * AMBIENT_TOP)
        scale(scaleX = 1f, scaleY = ry / rx, pivot = center) {
            drawCircle(
                brush =
                    Brush.radialGradient(
                        0f to glow,
                        AMBIENT_STOP to Color.Transparent,
                        center = center,
                        radius = rx,
                    ),
                radius = rx,
                center = center,
            )
        }
    }

/** The glow's strength: the mockups use a stronger tint on the dark canvas. */
fun ambientAlpha(pal: VoltPalette): Float = if (pal.isDark) AMBIENT_DARK else AMBIENT_LIGHT

private const val AMBIENT_DARK = 0.16f
private const val AMBIENT_LIGHT = 0.10f
private const val AMBIENT_RX = 1.2f
private const val AMBIENT_RY = 0.55f
private const val AMBIENT_TOP = 0.08f
private const val AMBIENT_STOP = 0.62f
