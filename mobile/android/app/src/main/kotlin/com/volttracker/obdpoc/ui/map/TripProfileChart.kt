package com.volttracker.obdpoc.ui.map

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.theme.VoltColors
import java.util.Locale

/**
 * The merged trip profile: speed (efficiency-colored), elevation, power/regen,
 * and battery bands sharing one distance axis with mile ticks, stop markers,
 * min/max callouts, and a scrubber cursor with a readout bubble.
 */
@Composable
fun TripProfileChart(
    state: MapUiState,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(chartHeight),
    ) {
        val stops =
            if (state.route.size > 1) {
                state.route.mapIndexedNotNull { i, p ->
                    if (p.stop) i.toFloat() / (state.route.size - 1) else null
                }
            } else {
                emptyList()
            }
        val cursor = state.scrubberFraction.coerceIn(0f, 1f)

        var top = 30.dp.toPx()
        val speedRect = bandRect(top, 92.dp.toPx())
        top = speedRect.bottom + 34.dp.toPx()
        val elevRect = bandRect(top, 60.dp.toPx())
        top = elevRect.bottom + 34.dp.toPx()
        val powerRect = bandRect(top, 60.dp.toPx())
        top = powerRect.bottom + 34.dp.toPx()
        val socRect = bandRect(top, 36.dp.toPx())
        val axisY = socRect.bottom + 22.dp.toPx()

        // Stop markers: faint vertical ticks through every band + axis dots.
        stops.forEach { f ->
            val x = xFor(f)
            drawLine(
                color = VoltColors.warn.copy(alpha = 0.30f),
                start = Offset(x, speedRect.top),
                end = Offset(x, socRect.bottom),
                strokeWidth = 2f,
            )
            drawCircle(color = VoltColors.warn, radius = 6f, center = Offset(x, axisY))
        }

        drawSpeedBand(speedRect, state)
        drawElevationBand(elevRect, state.elevationProfile)
        drawPowerBand(powerRect, state.powerProfile)
        drawSocBand(socRect, state.socProfile)
        drawDistanceAxis(axisY, state.distanceMiles)
        drawCursor(cursor, speedRect, socRect, state)
    }
}

// Full stack: 30 top + 92 speed + 34 gap + 60 elev + 34 gap + 60 power +
// 34 gap + 36 soc + 22 axis gap + 18 label space = 420dp.
private val chartHeight = 420.dp

private fun DrawScope.bandRect(
    top: Float,
    height: Float,
): Rect = Rect(0f, top, size.width, top + height)

private fun DrawScope.xFor(fraction: Float): Float = fraction * size.width

private fun DrawScope.labelPaint(
    sizeDp: Float,
    color: Color,
    bold: Boolean = false,
): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        textSize = sizeDp.dp.toPx()
        isFakeBoldText = bold
        letterSpacing = 0.09f
    }

private fun DrawScope.drawBandLabel(
    rect: Rect,
    label: String,
    callout: String?,
) {
    val paint = labelPaint(10f, VoltColors.textSecondary, bold = true)
    drawContext.canvas.nativeCanvas.drawText(label.uppercase(Locale.US), 0f, rect.top - 8.dp.toPx(), paint)
    if (callout != null) {
        val calloutPaint = labelPaint(10f, VoltColors.textPrimary, bold = true)
        val w = calloutPaint.measureText(callout)
        drawContext.canvas.nativeCanvas.drawText(callout, size.width - w, rect.top - 8.dp.toPx(), calloutPaint)
    }
}

private fun yInBand(
    rect: Rect,
    v: Float,
    minV: Float,
    maxV: Float,
): Float {
    val span = (maxV - minV).takeIf { it > 0f } ?: 1f
    val padY = rect.height * 0.10f
    return rect.top + padY + (1f - (v - minV) / span) * (rect.height - padY * 2)
}

/** Speed line, colored per segment by route efficiency, with dashed avg line. */
private fun DrawScope.drawSpeedBand(
    rect: Rect,
    state: MapUiState,
) {
    val values = state.speedProfile
    if (values.size < 2) return
    val minV = values.min()
    val maxV = values.max()
    drawBandLabel(rect, "Speed · mph", String.format(Locale.US, "max %.0f", maxV))
    // Dashed average line.
    if (state.avgMph > 0 && state.avgMph.toFloat() in minV..maxV) {
        val avgY = yInBand(rect, state.avgMph.toFloat(), minV, maxV)
        drawLine(
            color = VoltColors.textTertiary,
            start = Offset(0f, avgY),
            end = Offset(size.width, avgY),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 12f)),
        )
        val paint = labelPaint(9f, VoltColors.textTertiary)
        drawContext.canvas.nativeCanvas.drawText("avg ${state.avgMph}", 0f, avgY - 5.dp.toPx() + 0f, paint)
    }
    val route = state.route
    val stepX = size.width / (values.size - 1)
    for (i in 1 until values.size) {
        val frac = i.toFloat() / (values.size - 1)
        val quality =
            if (route.isEmpty()) {
                RouteQuality.GREAT
            } else {
                route[((route.size - 1) * frac).toInt().coerceIn(0, route.size - 1)].quality
            }
        val color =
            when (quality) {
                RouteQuality.GREAT -> VoltColors.energy
                RouteQuality.AVERAGE -> VoltColors.warn
                RouteQuality.POOR -> VoltColors.alert
            }
        drawLine(
            color = color,
            start = Offset((i - 1) * stepX, yInBand(rect, values[i - 1], minV, maxV)),
            end = Offset(i * stepX, yInBand(rect, values[i], minV, maxV)),
            strokeWidth = 6f,
            cap = StrokeCap.Round,
        )
    }
}

/** Elevation area with peak/valley callouts. */
private fun DrawScope.drawElevationBand(
    rect: Rect,
    values: List<Float>,
) {
    if (values.size < 2) return
    val minV = values.min()
    val maxV = values.max()
    drawBandLabel(
        rect,
        "Elevation · ft",
        String.format(Locale.US, "%.0f–%.0f", minV, maxV),
    )
    val stepX = size.width / (values.size - 1)
    val line = Path()
    values.forEachIndexed { i, v ->
        val x = i * stepX
        val y = yInBand(rect, v, minV, maxV)
        if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
    }
    val fill =
        Path().apply {
            addPath(line)
            lineTo(size.width, rect.bottom)
            lineTo(0f, rect.bottom)
            close()
        }
    drawPath(fill, color = VoltColors.regen.copy(alpha = 0.16f))
    drawPath(line, color = VoltColors.regen, style = Stroke(width = 5f, cap = StrokeCap.Round))
    // Peak marker.
    val peakIndex = values.indexOf(maxV)
    val peak = Offset(peakIndex * stepX, yInBand(rect, maxV, minV, maxV))
    drawCircle(color = VoltColors.regen, radius = 7f, center = peak)
    drawCircle(color = VoltColors.bg, radius = 3.5f, center = peak)
}

/** Centered-zero power band: drive above (orange), regen below (blue). */
private fun DrawScope.drawPowerBand(
    rect: Rect,
    values: List<Float>,
) {
    if (values.size < 2) return
    val extent = values.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1f)
    val regenKwh = values.filter { it < 0f }.sumOf { -it.toDouble() } / values.size
    drawBandLabel(
        rect,
        "Power · kW",
        if (regenKwh > 0.0) "regen shown in blue" else null,
    )
    val zeroY = rect.top + rect.height / 2f
    val stepX = size.width / (values.size - 1)
    // Filled area per segment above/below zero.
    for (i in 1 until values.size) {
        val x0 = (i - 1) * stepX
        val x1 = i * stepX
        val y0 = zeroY - (values[i - 1] / extent) * (rect.height / 2f) * 0.9f
        val y1 = zeroY - (values[i] / extent) * (rect.height / 2f) * 0.9f
        val color = if (values[i] >= 0f) VoltColors.drive else VoltColors.regen
        val fill =
            Path().apply {
                moveTo(x0, zeroY)
                lineTo(x0, y0)
                lineTo(x1, y1)
                lineTo(x1, zeroY)
                close()
            }
        drawPath(fill, color = color.copy(alpha = 0.30f))
        drawLine(color = color, start = Offset(x0, y0), end = Offset(x1, y1), strokeWidth = 5f, cap = StrokeCap.Round)
    }
    drawLine(
        color = VoltColors.hairline,
        start = Offset(0f, zeroY),
        end = Offset(size.width, zeroY),
        strokeWidth = 2f,
    )
}

/** Thin battery drain line with start/end percentages. */
private fun DrawScope.drawSocBand(
    rect: Rect,
    values: List<Float>,
) {
    if (values.size < 2) return
    val minV = values.min()
    val maxV = values.max()
    drawBandLabel(
        rect,
        "Battery",
        String.format(Locale.US, "%.0f%% → %.0f%%", values.first(), values.last()),
    )
    val stepX = size.width / (values.size - 1)
    for (i in 1 until values.size) {
        drawLine(
            color = VoltColors.energy,
            start = Offset((i - 1) * stepX, yInBand(rect, values[i - 1], minV, maxV)),
            end = Offset(i * stepX, yInBand(rect, values[i], minV, maxV)),
            strokeWidth = 5f,
            cap = StrokeCap.Round,
        )
    }
}

/** Mile-tick distance axis. */
private fun DrawScope.drawDistanceAxis(
    axisY: Float,
    distanceMiles: Double,
) {
    drawLine(
        color = VoltColors.hairline,
        start = Offset(0f, axisY),
        end = Offset(size.width, axisY),
        strokeWidth = 2f,
    )
    if (distanceMiles <= 0) return
    val paint = labelPaint(9f, VoltColors.textTertiary)
    val tickCount = 4
    for (t in 0..tickCount) {
        val frac = t.toFloat() / tickCount
        val x = xFor(frac)
        drawLine(
            color = VoltColors.hairline,
            start = Offset(x, axisY - 5.dp.toPx()),
            end = Offset(x, axisY),
            strokeWidth = 2f,
        )
        val label =
            if (t == tickCount) {
                String.format(Locale.US, "%.0f mi", distanceMiles)
            } else {
                String.format(Locale.US, "%.0f", distanceMiles * frac)
            }
        val w = paint.measureText(label)
        val labelX = (x - w / 2f).coerceIn(0f, size.width - w)
        drawContext.canvas.nativeCanvas.drawText(label, labelX, axisY + 14.dp.toPx(), paint)
    }
}

/** Scrubber cursor through all bands + readout bubble. */
private fun DrawScope.drawCursor(
    cursor: Float,
    topRect: Rect,
    bottomRect: Rect,
    state: MapUiState,
) {
    val x = xFor(cursor)
    drawLine(
        color = VoltColors.textPrimary.copy(alpha = 0.55f),
        start = Offset(x, topRect.top),
        end = Offset(x, bottomRect.bottom),
        strokeWidth = 3f,
    )
    val stats = state.scrubberStats ?: return
    val text =
        String.format(
            Locale.US,
            "%.1f mi · %d mph · %d ft",
            stats.distanceMiles,
            stats.speedMph,
            stats.elevationFt,
        )
    val paint = labelPaint(10f, VoltColors.textPrimary, bold = true)
    val textW = paint.measureText(text)
    val padH = 9.dp.toPx()
    val bubbleW = textW + padH * 2
    val bubbleH = 20.dp.toPx()
    val bubbleX = (x - bubbleW / 2f).coerceIn(0f, size.width - bubbleW)
    val bubbleY = topRect.top - 26.dp.toPx()
    drawRoundRect(
        color = VoltColors.surfaceElevated,
        topLeft = Offset(bubbleX, bubbleY),
        size =
            androidx.compose.ui.geometry
                .Size(bubbleW, bubbleH),
        cornerRadius =
            androidx.compose.ui.geometry
                .CornerRadius(bubbleH / 2f, bubbleH / 2f),
    )
    drawContext.canvas.nativeCanvas.drawText(
        text,
        bubbleX + padH,
        bubbleY + bubbleH - 6.dp.toPx(),
        paint,
    )
}
