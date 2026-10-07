package com.volttracker.obdpoc.ui.car

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.volttracker.obdpoc.ui.components.CappedTextScale
import com.volttracker.obdpoc.ui.components.DASH
import com.volttracker.obdpoc.ui.components.VoltIcons
import com.volttracker.obdpoc.ui.drive.TirePressures
import com.volttracker.obdpoc.ui.drive.tireLow
import com.volttracker.obdpoc.ui.theme.LocalVoltPalette
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltFonts
import com.volttracker.obdpoc.ui.theme.VoltType

/**
 * The top-down 2016–2019 Volt, nose up (mockups `carTop`), with each tyre's pressure beside it
 * and the lock state on the roof. A tyre under the placard reads in the warning color; a missing
 * reading shows a bare dash, never a guess. The labels scale with the drawing, so their text
 * scale is capped to keep them clear of the car at the largest font sizes.
 */
@Composable
fun CarTopView(
    tires: TirePressures?,
    locked: Boolean?,
    placardPsi: Double,
    metric: Boolean,
    modifier: Modifier = Modifier,
) {
    val pal = LocalVoltPalette.current
    val shapes = remember { CarShapes() }
    val description =
        buildString {
            append(
                when (locked) {
                    // The car's lock command, not the latches: what was sent, not that they moved.
                    true -> "Lock sent"
                    false -> "Unlock sent"
                    null -> "Lock not reported"
                },
            )
            append(". ")
            append(tiresDescription(tires, metric))
        }
    CappedTextScale {
        BoxWithConstraints(
            modifier
                .fillMaxWidth()
                .aspectRatio(VIEW_W / VIEW_H)
                .semantics { contentDescription = description },
        ) {
            val unit = maxWidth / VIEW_W
            Canvas(Modifier.fillMaxSize()) {
                val k = size.width / VIEW_W
                scale(k, pivot = Offset.Zero) {
                    drawGlow(pal.volt)
                    translate(CENTER_X, CENTER_Y) {
                        WHEELS.forEach { (x, y) ->
                            drawRoundRect(
                                color = pal.text.copy(alpha = WHEEL_ALPHA),
                                topLeft = Offset(x - WHEEL_W / 2, y - WHEEL_H / 2),
                                size = Size(WHEEL_W, WHEEL_H),
                                cornerRadius = CornerRadius(WHEEL_R),
                            )
                        }
                        drawShape(shapes.body, pal.carBody, pal.carLine, BODY_STROKE)
                        drawShape(shapes.windshield, pal.carGlass, pal.carLine, GLASS_STROKE)
                        val roofGlass = pal.carGlass.copy(alpha = pal.carGlass.alpha * ROOF_ALPHA)
                        drawShape(shapes.roof, roofGlass, pal.carLine, 1f)
                        drawShape(shapes.rearGlass, pal.carGlass, pal.carLine, GLASS_STROKE)
                        shapes.lines.forEach { drawPath(it, pal.carLine, style = Stroke(1f)) }
                        MIRRORS.forEach { x ->
                            drawRoundRect(pal.carBody, Offset(x, MIRROR_Y), Size(MIRROR_W, MIRROR_H), CornerRadius(2f))
                            drawRoundRect(
                                pal.carLine,
                                Offset(x, MIRROR_Y),
                                Size(MIRROR_W, MIRROR_H),
                                CornerRadius(2f),
                                style = Stroke(BODY_STROKE),
                            )
                        }
                    }
                }
            }
            LockBadge(locked, unit)
            val corners = listOf(tires?.fl, tires?.fr, tires?.rl, tires?.rr)
            LABELS.forEachIndexed { i, (x, y) ->
                val psi = corners[i]
                TireLabel(
                    value = psi?.let { pressureValue(it, metric) } ?: DASH,
                    unitLabel = pressureUnit(metric),
                    warn = psi != null && tireLow(psi, placardPsi),
                    missing = psi == null,
                    x = x,
                    y = y,
                    endAnchored = i % 2 == 0,
                    unit = unit,
                    width = maxWidth,
                )
            }
        }
    }
}

@Composable
private fun LockBadge(
    locked: Boolean?,
    unit: Dp,
) {
    val ring =
        when (locked) {
            true -> VoltColors.energy
            false -> VoltColors.textSecondary
            null -> VoltColors.textTertiary
        }
    Box(
        Modifier
            .offset(x = unit * (CENTER_X - LOCK_R), y = unit * (CENTER_Y + LOCK_DY - LOCK_R))
            .size(unit * LOCK_R * 2)
            .background(VoltColors.surface, CircleShape)
            .border(1.5.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (locked == false) VoltIcons.Unlock else VoltIcons.Lock,
            contentDescription = null,
            tint = ring,
            modifier = Modifier.size(unit * LOCK_ICON),
        )
    }
}

@Composable
private fun TireLabel(
    value: String,
    unitLabel: String,
    warn: Boolean,
    missing: Boolean,
    x: Float,
    y: Float,
    endAnchored: Boolean,
    unit: Dp,
    width: Dp,
) {
    val box =
        if (endAnchored) {
            Modifier.width(unit * x)
        } else {
            Modifier.offset(x = unit * x).width(width - unit * x)
        }
    val scale = unit.value
    Column(
        modifier = box.offset(y = unit * (y - LABEL_RISE)),
        horizontalAlignment = if (endAnchored) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = value,
            style = VoltType.value.copy(fontSize = (TP_VALUE_SP * scale).sp, lineHeight = (TP_VALUE_SP * scale).sp),
            color =
                when {
                    warn -> VoltColors.warn
                    missing -> VoltColors.textTertiary
                    else -> VoltColors.textPrimary
                },
        )
        if (!missing) {
            Text(
                text = unitLabel,
                style =
                    VoltType.label.copy(
                        fontFamily = VoltFonts.barlowSemiCondensed,
                        fontSize = (TP_UNIT_SP * scale).sp,
                        letterSpacing = 0.1.em,
                    ),
                color = VoltColors.textTertiary,
            )
        }
    }
}

/** "Tires front left 38 psi, front right 38 psi, …" or "Tires not reported" for TalkBack. */
internal fun tiresDescription(
    tires: TirePressures?,
    metric: Boolean,
): String {
    val all = tires?.all ?: return "Tires not reported"
    val unit = pressureUnit(metric)
    return "Tires " + all.indices.joinToString(", ") { "${TIRE_CORNERS[it]} ${pressureValue(all[it], metric)} $unit" }
}

private val TIRE_CORNERS = listOf("front left", "front right", "rear left", "rear right")

private fun DrawScope.drawGlow(volt: Color) {
    val center = Offset(CENTER_X, CENTER_Y)
    scale(scaleX = 1f, scaleY = GLOW_RY / GLOW_RX, pivot = center) {
        drawCircle(
            brush =
                Brush.radialGradient(
                    colors = listOf(volt.copy(alpha = GLOW_ALPHA), volt.copy(alpha = 0f)),
                    center = center,
                    radius = GLOW_RX,
                ),
            radius = GLOW_RX,
            center = center,
        )
    }
}

private fun DrawScope.drawShape(
    path: Path,
    fill: Color,
    line: Color,
    stroke: Float,
) {
    drawPath(path, fill)
    drawPath(path, line, style = Stroke(stroke))
}

/** The mockup's SVG paths, in its viewBox units around the car's center. */
private class CarShapes {
    val body = path(BODY)
    val windshield = path(WINDSHIELD)
    val roof = path(ROOF)
    val rearGlass = path(REAR_GLASS)
    val lines = listOf(path(HOOD_LINE), path(DOOR_LINES))

    private fun path(d: String): Path = PathParser().parsePathString(d).toPath()
}

private const val VIEW_W = 380f
private const val VIEW_H = 270f
private const val CENTER_X = 190f
private const val CENTER_Y = 135f
private const val GLOW_RX = 150f
private const val GLOW_RY = 128f
private const val GLOW_ALPHA = 0.12f
private const val WHEEL_W = 18f
private const val WHEEL_H = 40f
private const val WHEEL_R = 6f
private const val WHEEL_ALPHA = 0.75f
private const val BODY_STROKE = 1.2f
private const val GLASS_STROKE = 1f
private const val ROOF_ALPHA = 0.55f
private const val MIRROR_Y = -44f
private const val MIRROR_W = 10f
private const val MIRROR_H = 5f
private const val LOCK_R = 13f
private const val LOCK_DY = 6f
private const val LOCK_ICON = 14f
private const val LABEL_RISE = 19f
private const val TP_VALUE_SP = 22f
private const val TP_UNIT_SP = 10.5f

private val WHEELS = listOf(-58f to -72f, 58f to -72f, -58f to 70f, 58f to 70f)
private val MIRRORS = listOf(-70f, 60f)

/** Where each pressure sits (FL, FR, RL, RR): the left ones end at x, the right ones start at it. */
private val LABELS = listOf(98f to 70f, 282f to 70f, 98f to 212f, 282f to 212f)

private const val BODY =
    "M-44 -118 C -30 -126, 30 -126, 44 -118 C 58 -110, 60 -92, 60 -70 L 62 60 C 62 92, 58 110, 44 118 " +
        "C 26 126, -26 126, -44 118 C -58 110, -62 92, -62 60 L -60 -70 C -60 -92, -58 -110, -44 -118 Z"
private const val WINDSHIELD = "M-46 -54 C -30 -64, 30 -64, 46 -54 L 40 -22 C 20 -26, -20 -26, -40 -22 Z"
private const val ROOF = "M-40 -18 C -20 -22, 20 -22, 40 -18 L 42 46 C 20 50, -20 50, -42 46 Z"
private const val REAR_GLASS = "M-42 58 C -20 62, 20 62, 42 58 L 38 92 C 20 98, -20 98, -38 92 Z"
private const val HOOD_LINE = "M-50 -100 C -20 -106, 20 -106, 50 -100"
private const val DOOR_LINES = "M-62 12 L -52 12 M62 12 L 52 12"
