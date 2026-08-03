package com.volttracker.obdpoc.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltType

/**
 * Smooth area sparkline: a soft line over a vertical gradient fill.
 * Values are drawn min→max within the given series (flat series render mid-height).
 */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = VoltColors.energy,
    strokeWidth: Dp = 2.5.dp,
    cursorFraction: Float? = null,
) {
    Canvas(modifier = modifier.fillMaxWidth()) {
        if (values.size < 2) return@Canvas
        val minV = values.min()
        val maxV = values.max()
        val span = (maxV - minV).takeIf { it > 0f } ?: 1f
        val stepX = size.width / (values.size - 1)
        // Leave headroom so the stroke never clips at the extremes.
        val padY = size.height * 0.12f
        val usableH = size.height - padY * 2

        fun yAt(v: Float) = padY + (1f - (v - minV) / span) * usableH

        val line = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = yAt(v)
            if (i == 0) {
                line.moveTo(x, y)
            } else {
                // Midpoint smoothing: quadratic through the midpoint of each segment.
                val prevX = (i - 1) * stepX
                val prevY = yAt(values[i - 1])
                val midX = (prevX + x) / 2f
                line.quadraticTo(prevX, prevY, midX, (prevY + y) / 2f)
                if (i == values.lastIndex) line.lineTo(x, y)
            }
        }

        val fill =
            Path().apply {
                addPath(line)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
        drawPath(
            path = fill,
            brush =
                Brush.verticalGradient(
                    colors = listOf(lineColor.copy(alpha = 0.22f), lineColor.copy(alpha = 0f)),
                ),
        )
        drawPath(
            path = line,
            color = lineColor,
            style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round),
        )
        // Scrubber cursor: vertical hairline + knob at the interpolated value.
        if (cursorFraction != null) {
            val cx = cursorFraction.coerceIn(0f, 1f) * size.width
            val fIndex = cursorFraction.coerceIn(0f, 1f) * (values.size - 1)
            val i0 = fIndex.toInt().coerceIn(0, values.size - 1)
            val i1 = (i0 + 1).coerceAtMost(values.size - 1)
            val v = values[i0] + (values[i1] - values[i0]) * (fIndex - i0)
            drawLine(
                color = VoltColors.textSecondary.copy(alpha = 0.55f),
                start = Offset(cx, 0f),
                end = Offset(cx, size.height),
                strokeWidth = 2f,
            )
            drawCircle(color = VoltColors.textPrimary, radius = 7f, center = Offset(cx, yAt(v)))
            drawCircle(color = lineColor, radius = 4f, center = Offset(cx, yAt(v)))
        }
    }
}

/**
 * Centered-zero power bar: regen extends left of center (blue), drive extends
 * right (orange). [powerKw] is signed (+drive, −regen).
 */
@Composable
fun PowerBar(
    powerKw: Double,
    maxDriveKw: Double,
    maxRegenKw: Double,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
) {
    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height),
    ) {
        val r = size.height / 2f
        val centerX = size.width / 2f
        drawRoundRect(
            color = VoltColors.surfaceElevated,
            cornerRadius =
                androidx.compose.ui.geometry
                    .CornerRadius(r, r),
        )
        // Center tick.
        drawLine(
            color = VoltColors.hairline,
            start = Offset(centerX, 0f),
            end = Offset(centerX, size.height),
            strokeWidth = 2f,
        )
        if (powerKw > 0.05) {
            val frac = (powerKw / maxDriveKw).coerceIn(0.0, 1.0).toFloat()
            drawRoundRect(
                brush =
                    Brush.horizontalGradient(
                        colors = listOf(VoltColors.drive.copy(alpha = 0.55f), VoltColors.drive),
                        startX = centerX,
                        endX = centerX + (size.width / 2f) * frac,
                    ),
                topLeft = Offset(centerX, 0f),
                size =
                    androidx.compose.ui.geometry
                        .Size((size.width / 2f) * frac, size.height),
                cornerRadius =
                    androidx.compose.ui.geometry
                        .CornerRadius(r, r),
            )
        } else if (powerKw < -0.05) {
            val frac = (-powerKw / maxRegenKw).coerceIn(0.0, 1.0).toFloat()
            val w = (size.width / 2f) * frac
            drawRoundRect(
                brush =
                    Brush.horizontalGradient(
                        colors = listOf(VoltColors.regen, VoltColors.regen.copy(alpha = 0.55f)),
                        startX = centerX - w,
                        endX = centerX,
                    ),
                topLeft = Offset(centerX - w, 0f),
                size =
                    androidx.compose.ui.geometry
                        .Size(w, size.height),
                cornerRadius =
                    androidx.compose.ui.geometry
                        .CornerRadius(r, r),
            )
        }
    }
}

/** Slim battery bar with a green gradient fill, Tesla-style. */
@Composable
fun BatteryBar(
    socFraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(50))
                .background(VoltColors.surfaceElevated),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(socFraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(VoltColors.energy, VoltColors.energyBright),
                        ),
                    ),
        )
    }
}

/**
 * Signed bar chart around a horizontal zero line: positive values rise in the
 * drive color, negative values drop in the regen color. Older bars fade so the
 * newest sample reads brightest — the "power over the last minute" strip.
 */
@Composable
fun SignedBars(
    values: List<Float>,
    modifier: Modifier = Modifier,
    positiveColor: Color = VoltColors.drive,
    negativeColor: Color = VoltColors.regen,
) {
    Canvas(modifier = modifier.fillMaxWidth()) {
        if (values.isEmpty()) return@Canvas
        val maxAbs = values.maxOf { kotlin.math.abs(it) }.takeIf { it > 0f } ?: 1f
        val centerY = size.height / 2f
        val slot = size.width / values.size
        val barW = slot * 0.6f
        drawLine(
            color = VoltColors.hairline,
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = 2f,
        )
        values.forEachIndexed { i, v ->
            val h = (kotlin.math.abs(v) / maxAbs) * (centerY - 2f)
            if (h <= 0f) return@forEachIndexed
            // A singleton series IS the newest sample — full brightness.
            val alpha =
                if (values.size == 1) {
                    1f
                } else {
                    0.4f + 0.6f * (i.toFloat() / values.lastIndex)
                }
            drawRoundRect(
                color = (if (v >= 0f) positiveColor else negativeColor).copy(alpha = alpha),
                topLeft = Offset(i * slot + (slot - barW) / 2f, if (v >= 0f) centerY - h else centerY),
                size =
                    androidx.compose.ui.geometry
                        .Size(barW, h),
                cornerRadius =
                    androidx.compose.ui.geometry
                        .CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}

/**
 * Minimal rounded bar chart with labels underneath. The last bar (current
 * period) renders in the accent color; earlier bars are muted.
 */
@Composable
fun MiniBars(
    values: List<Float>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    barHeight: Dp = 96.dp,
    accent: Color = VoltColors.energy,
    highlightIndex: Int? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(barHeight),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            val maxV = values.maxOrNull()?.takeIf { it > 0f } ?: 1f
            val accented = highlightIndex ?: values.lastIndex
            values.forEachIndexed { i, v ->
                val last = i == accented
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(fraction = (v / maxV).coerceIn(0.04f, 1f))
                            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                            .background(if (last) accent else VoltColors.surfaceElevated),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            labels.forEach { label ->
                Text(
                    text = label,
                    style = VoltType.caption,
                    color = VoltColors.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
