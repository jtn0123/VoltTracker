package com.volttracker.obdpoc.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.volttracker.obdpoc.ui.theme.LocalVoltPalette

/** Fewest per-cell readings worth drawing as a histogram. */
const val MIN_CELLS_FOR_HISTOGRAM = 8

private const val HIST_FLOOR = 0.25
private const val HIST_BAR = 0.62f
private const val HIST_ALPHA = 0.7f

/**
 * One bar per HV cell group (mockups `VT.cellHist`), height = voltage relative to the pack's
 * min…max, the weakest group in warn. Draw it only with [MIN_CELLS_FOR_HISTOGRAM] or more readings.
 */
@Composable
fun CellHistogram(
    cells: List<Double?>,
    weakest: Int?,
    modifier: Modifier = Modifier,
) {
    val pal = LocalVoltPalette.current
    Canvas(modifier.semantics { contentDescription = "Cell voltages" }) {
        val known = cells.filterNotNull()
        if (known.isEmpty()) return@Canvas
        val lo = known.min()
        val hi = known.max()
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val bw = size.width / cells.size
        cells.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val isWeak = weakest != null && i + 1 == weakest
            val h = size.height * (HIST_FLOOR + (1 - HIST_FLOOR) * ((v - lo) / span)).toFloat()
            drawRect(
                color = if (isWeak) pal.warn else pal.ev.copy(alpha = HIST_ALPHA),
                topLeft = Offset(i * bw, size.height - h),
                size = Size(bw * HIST_BAR, h),
            )
        }
    }
}
