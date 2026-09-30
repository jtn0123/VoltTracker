package com.volttracker.obdpoc.ui.diag

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.components.VoltLabel
import com.volttracker.obdpoc.ui.theme.LocalVoltPalette
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltType

/**
 * The battery's capacity health over time under Health's battery figures: a thin line of the
 * daily reads (when there are two days or more) and the sentence that says what it means.
 */
@Composable
fun SohTrendView(
    trend: SohTrend,
    modifier: Modifier = Modifier,
) {
    val pal = LocalVoltPalette.current
    Column(
        modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = "Battery health trend. ${trend.sentence}"
        },
    ) {
        VoltLabel("Health over time", Modifier.padding(bottom = 8.dp))
        if (trend.drawable) {
            val days = trend.days
            val lo = days.minOf { it.pct } - PAD_PTS
            val hi = days.maxOf { it.pct } + PAD_PTS
            val t0 = days.first().atMs
            val span = (days.last().atMs - t0).coerceAtLeast(1L).toFloat()
            Canvas(Modifier.fillMaxWidth().height(CHART_DP.dp)) {
                val inset = DOT_R.dp.toPx()
                val w = size.width - 2 * inset
                val h = size.height - 2 * inset
                val at: (SohPoint) -> Offset = { p ->
                    Offset(
                        inset + w * (p.atMs - t0) / span,
                        inset + h * (1f - ((p.pct - lo) / (hi - lo)).toFloat()),
                    )
                }
                val path = Path()
                days.forEachIndexed { i, p ->
                    at(p).let {
                        if (i ==
                            0
                        ) {
                            path.moveTo(it.x, it.y)
                        } else {
                            path.lineTo(it.x, it.y)
                        }
                    }
                }
                drawPath(
                    path,
                    pal.ev,
                    style = Stroke(LINE_DP.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                drawCircle(pal.ev, DOT_R.dp.toPx(), at(days.last()))
            }
        }
        Text(
            trend.sentence,
            style = VoltType.caption,
            color = VoltColors.textSecondary,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private const val CHART_DP = 44
private const val LINE_DP = 2
private const val DOT_R = 3
private const val PAD_PTS = 0.5
