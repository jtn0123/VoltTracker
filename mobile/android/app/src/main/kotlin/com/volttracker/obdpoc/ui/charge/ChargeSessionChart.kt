package com.volttracker.obdpoc.ui.charge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.components.LocalVoltPrefs
import com.volttracker.obdpoc.ui.drive.clockLabel
import com.volttracker.obdpoc.ui.theme.LocalVoltPalette
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltType
import kotlin.math.floor

/**
 * The session's SOC curve (mockups `S.charge` chart): the measured line with a soft fill from the
 * charge's start to now, then a dashed projection to the charge limit at the estimated finish.
 * Grid lines every 25 %, labelled in a right-hand gutter the curve never enters (so the
 * projection's end at the limit can't run through the "100%" label); the time axis is labelled
 * start / now / finish.
 */
@Composable
fun ChargeSessionChart(
    state: ChargeUiState,
    modifier: Modifier = Modifier,
) {
    val span = state.chartSpan() ?: return
    val measured = state.measuredPoints()
    val projected = state.projectedPoints()
    val pal = LocalVoltPalette.current
    val measurer = rememberTextMeasurer()
    val axisStyle = VoltType.caption.copy(color = VoltColors.textTertiary)
    val lowest = (measured + projected).minOf { it.soc }
    val yMin = (floor((lowest - Y_PAD) / GRID_STEP) * GRID_STEP).coerceIn(0f, FULL - GRID_STEP)
    val yMax = FULL + Y_TOP_PAD
    val nowFrac = (span.nowMs - span.startMs).toFloat() / (span.endMs - span.startMs)
    val density = LocalDensity.current
    val gutterPx = measurer.measure("100%", axisStyle).size.width + with(density) { GUTTER_GAP.toPx() }
    val gutter = with(density) { gutterPx.toDp() }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription =
                        "Charge session: ${measured.first().soc.toInt()}% to ${measured.last().soc.toInt()}% so far"
                },
    ) {
        Canvas(Modifier.fillMaxWidth().height(CHART_DP.dp)) {
            val plotW = size.width - gutterPx

            fun x(atMs: Long): Float = plotW * (atMs - span.startMs) / (span.endMs - span.startMs)

            fun y(soc: Float): Float = size.height - size.height * (soc - yMin) / (yMax - yMin)
            var grid = yMin + GRID_STEP
            while (grid <= FULL) {
                val gy = y(grid)
                drawLine(pal.line, Offset(0f, gy), Offset(plotW, gy), strokeWidth = 1.dp.toPx())
                val label = measurer.measure("${grid.toInt()}%", axisStyle)
                drawText(
                    label,
                    topLeft = Offset(size.width - label.size.width, (gy - label.size.height / 2f).coerceAtLeast(0f)),
                )
                grid += GRID_STEP
            }
            val line = Path()
            measured.forEachIndexed { i, p ->
                if (i ==
                    0
                ) {
                    line.moveTo(x(p.atMs), y(p.soc))
                } else {
                    line.lineTo(x(p.atMs), y(p.soc))
                }
            }
            val fill =
                Path().apply {
                    addPath(line)
                    lineTo(x(measured.last().atMs), size.height)
                    lineTo(x(measured.first().atMs), size.height)
                    close()
                }
            drawPath(fill, Brush.verticalGradient(listOf(pal.ev.copy(alpha = FILL_ALPHA), pal.ev.copy(alpha = 0f))))
            drawPath(line, pal.ev, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round))
            if (projected.isNotEmpty()) {
                val proj = Path()
                projected.forEachIndexed { i, p ->
                    if (i ==
                        0
                    ) {
                        proj.moveTo(x(p.atMs), y(p.soc))
                    } else {
                        proj.lineTo(x(p.atMs), y(p.soc))
                    }
                }
                drawPath(
                    proj,
                    pal.ev.copy(alpha = PROJECTION_ALPHA),
                    style =
                        Stroke(
                            width = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx())),
                        ),
                )
            }
            val tip = measured.last()
            drawCircle(pal.surface, radius = 6.5.dp.toPx(), center = Offset(x(tip.atMs), y(tip.soc)))
            drawCircle(pal.ev, radius = 5.dp.toPx(), center = Offset(x(tip.atMs), y(tip.soc)))
        }
        Axis(span, nowFrac, axisStyle, Modifier.padding(end = gutter))
    }
}

@Composable
private fun Axis(
    span: ChargeChartSpan,
    nowFrac: Float,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val h24 = LocalVoltPrefs.current.clock24h
    Row(modifier.fillMaxWidth().padding(top = (CHART_DP + 8).dp)) {
        // A charge that only just began has no room for a start label left of "now".
        if (nowFrac >= MIN_FRAC) {
            Text(
                clockLabel(span.startMs, h24 = h24),
                style = style,
                modifier = Modifier.weight(nowFrac.coerceAtMost(1f - MIN_FRAC)),
            )
        }
        Text("now", style = style)
        Text(
            span.finishMs?.let { clockLabel(it, h24 = h24) } ?: "",
            style = style.copy(textAlign = TextAlign.End),
            modifier = Modifier.weight((1f - nowFrac).coerceIn(MIN_FRAC, 1f)),
        )
    }
}

private const val CHART_DP = 112
private val GUTTER_GAP = 6.dp
private const val GRID_STEP = 25f
private const val FULL = 100f
private const val Y_PAD = 6f
private const val Y_TOP_PAD = 4f
private const val FILL_ALPHA = 0.28f
private const val PROJECTION_ALPHA = 0.6f
private const val MIN_FRAC = 0.15f
