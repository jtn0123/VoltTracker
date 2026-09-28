package com.volttracker.obdpoc.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volttracker.obdpoc.ui.HistoryLoad
import com.volttracker.obdpoc.ui.components.CappedTextScale
import com.volttracker.obdpoc.ui.components.DASH
import com.volttracker.obdpoc.ui.components.EmptyAction
import com.volttracker.obdpoc.ui.components.EmptyLink
import com.volttracker.obdpoc.ui.components.IconSquare
import com.volttracker.obdpoc.ui.components.LocalVoltNav
import com.volttracker.obdpoc.ui.components.PillTone
import com.volttracker.obdpoc.ui.components.VoltEmptyState
import com.volttracker.obdpoc.ui.components.VoltIcons
import com.volttracker.obdpoc.ui.components.VoltLabel
import com.volttracker.obdpoc.ui.components.VoltPanel
import com.volttracker.obdpoc.ui.components.VoltScreen
import com.volttracker.obdpoc.ui.components.VoltSegmented
import com.volttracker.obdpoc.ui.components.connectionDot
import com.volttracker.obdpoc.ui.components.unitStyle
import com.volttracker.obdpoc.ui.drive.oneDecimal
import com.volttracker.obdpoc.ui.theme.LocalVoltPalette
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltTheme
import com.volttracker.obdpoc.ui.theme.VoltType
import com.volttracker.obdpoc.ui.units.VoltUnits
import java.util.Locale
import kotlin.math.ceil

/**
 * The Insights tab (mockups `S.insights`): the share of driving done on electricity over the
 * chosen week / month / year / all time, with electric and gas miles stacked per week (or day,
 * month, year); what that saved against gas; the energy it used; efficiency by speed; and a note
 * when a cell of the pack is drifting low.
 */
@Composable
fun InsightsScreen(
    state: InsightsUiState,
    modifier: Modifier = Modifier,
    onPeriod: (InsightsPeriod) -> Unit = {},
) {
    val summary = state.summary()
    VoltScreen(
        title = "Insights",
        subtitle = summary.window.title,
        dot = connectionDot(state.connected),
        modifier = modifier,
    ) {
        VoltSegmented(
            options = InsightsPeriod.entries.map { it.label },
            selectedIndex = state.period.ordinal,
            onSelect = { onPeriod(InsightsPeriod.entries[it]) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        ElectricHero(summary, state.units, state.history)
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SavedTile(state, summary, Modifier.weight(1f).fillMaxHeight())
            EnergyTile(state, summary, Modifier.weight(1f).fillMaxHeight())
        }
        Spacer(Modifier.height(10.dp))
        SpeedCard(state.speedEfficiency, state.units, loading = !state.speedsLoaded)
        state.cellDrift?.let {
            Spacer(Modifier.height(10.dp))
            CellNote(it)
        }
    }
}

@Composable
private fun ElectricHero(
    summary: PeriodSummary,
    units: VoltUnits,
    history: HistoryLoad,
) {
    VoltPanel(padding = PaddingValues(16.dp, 16.dp, 16.dp, 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                VoltLabel("Driven on electricity")
                Text(
                    text =
                        buildAnnotatedString {
                            append(summary.electricPct?.toString() ?: DASH)
                            if (summary.electricPct != null) withStyle(unitStyle(HERO_UNIT_SP)) { append("%") }
                        },
                    style = VoltType.display.copy(fontSize = HERO_SP.sp, lineHeight = HERO_SP.sp),
                    color = VoltColors.textPrimary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                summary.deltaText()?.let { DeltaLine(it, (summary.deltaPts ?: 0) >= 0) }
            }
            if (summary.trips.isNotEmpty()) Legend(summary, units)
        }
        Spacer(Modifier.height(14.dp))
        if (summary.trips.isEmpty()) {
            when (history) {
                HistoryLoad.LOADING -> VoltEmptyState("Loading drives…", inCard = false)
                HistoryLoad.FAILED ->
                    VoltEmptyState(
                        "Drives couldn't be read",
                        body = "They'll load the next time you open Insights.",
                        inCard = false,
                    )
                HistoryLoad.LOADED ->
                    VoltEmptyState(
                        "No drives in this period yet",
                        body = "Pick a longer period, or drive with the adapter connected.",
                        inCard = false,
                    )
            }
        } else {
            ModeBars(summary.buckets, units)
        }
    }
}

@Composable
private fun Legend(
    summary: PeriodSummary,
    units: VoltUnits,
) {
    val unit = units.distanceUnit
    Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        LegendLine(VoltColors.energy, "${wholeMiles(summary.evMiles, units)} $unit electric")
        LegendLine(VoltColors.gas, "${wholeMiles(summary.gasMiles, units)} $unit gas")
        Text(
            "${wholeMiles(summary.totalMiles, units)} $unit total",
            style = VoltType.caption.copy(fontWeight = FontWeight.Medium),
            color = VoltColors.textSecondary,
        )
    }
}

@Composable
private fun DeltaLine(
    text: String,
    up: Boolean,
) {
    val (change, rest) = text.substringBefore(" vs ") to text.substringAfter(" vs ", "")
    Text(
        text =
            buildAnnotatedString {
                withStyle(
                    SpanStyle(color = if (up) VoltColors.energy else VoltColors.gas),
                ) { append(change) }
                if (rest.isNotEmpty()) append(" vs $rest")
            },
        style = VoltType.body.copy(fontWeight = FontWeight.Medium),
        color = VoltColors.textSecondary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun LegendLine(
    color: Color,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Canvas(Modifier.size(9.dp)) { drawRoundRect(color, cornerRadius = CornerRadius(3.dp.toPx())) }
        Text(text, style = VoltType.caption.copy(fontWeight = FontWeight.Medium), color = VoltColors.textPrimary)
    }
}

/** Electric over gas miles, one rounded column per bar with the gas at its foot. */
@Composable
private fun ModeBars(
    buckets: List<ModeBucket>,
    units: VoltUnits,
) {
    val pal = LocalVoltPalette.current
    val max = buckets.maxOfOrNull { it.evMiles + it.gasMiles }?.takeIf { it > 0.0 } ?: 1.0
    val unit = units.distanceUnit
    val described =
        buckets.joinToString("; ") {
            "${it.label}: ${wholeMiles(it.evMiles, units)} $unit electric, ${wholeMiles(it.gasMiles, units)} $unit gas"
        }
    Row(Modifier.fillMaxWidth().semantics { contentDescription = described }) {
        buckets.forEach { b ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.fillMaxWidth().height(BARS_DP.dp)) {
                    val width = minOf(size.width * BAR_FILL, BAR_MAX_DP.dp.toPx())
                    val left = (size.width - width) / 2
                    val gap = 4.dp.toPx()
                    val radius = CornerRadius(minOf(RADIUS_DP.dp.toPx(), width / 2))
                    val gasH = (b.gasMiles / max * size.height).toFloat()
                    val evH = (b.evMiles / max * size.height).toFloat()
                    val minH = MIN_SEGMENT_DP.dp.toPx()
                    var bottom = size.height
                    if (b.gasMiles > 0.0) {
                        val h = maxOf(gasH, minH)
                        drawRoundRect(pal.gas, Offset(left, bottom - h), Size(width, h), radius)
                        bottom -= h + gap
                    }
                    if (b.evMiles > 0.0) {
                        val h = maxOf(evH - if (b.gasMiles > 0.0) gap else 0f, minH)
                        drawRoundRect(pal.ev, Offset(left, bottom - h), Size(width, h), radius)
                    }
                }
                Text(
                    b.label,
                    style = VoltType.label.copy(letterSpacing = 0.04.sp, fontSize = 12.sp),
                    color = VoltColors.textTertiary,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SavedTile(
    state: InsightsUiState,
    summary: PeriodSummary,
    modifier: Modifier,
) {
    val saved = summary.saved
    val units = state.units
    Tile("Saved vs gas", modifier) {
        if (saved == null) {
            TileValue(DASH, null)
            TileNote(if (units.metric) "Needs a gas price and fuel economy" else "Needs a gas price and mpg")
            EmptyLink(EmptyAction("Open Settings", LocalVoltNav.current.openSettings))
        } else {
            val (dollars, cents) = dollarsAndCents(saved)
            TileValue(dollars, cents, unitLeadingSpace = false)
            val price = dollarsAndCents(units.gasPrice(state.gasPrice)).let { it.first + it.second }
            val economy =
                state.gasMpg?.let { mpg ->
                    if (units.metric) units.economyText(mpg) else "${wholeMpg(mpg)} mpg"
                }
            TileNote(listOfNotNull("at $price/${units.gasVolumeUnit}", economy).joinToString(" · "))
        }
    }
}

@Composable
private fun EnergyTile(
    state: InsightsUiState,
    summary: PeriodSummary,
    modifier: Modifier,
) {
    Tile("Energy", modifier) {
        if (summary.kwh <= 0.0) {
            TileValue(DASH, null)
            TileNote("No energy logged")
        } else {
            TileValue(String.format(Locale.US, "%,d", Math.round(summary.kwh)), "kWh")
            val cost =
                state.homeRate.takeIf { it > 0.0 }?.let { rate ->
                    dollarsAndCents(summary.kwh * rate).let { it.first + it.second }
                }
            TileNote(listOfNotNull(cost, summary.miPerKwh?.let(state.units::efficiencyText)).joinToString(" · "))
        }
    }
}

@Composable
private fun Tile(
    label: String,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    VoltPanel(modifier = modifier, padding = PaddingValues(14.dp)) {
        VoltLabel(label)
        Spacer(Modifier.height(5.dp))
        content()
    }
}

@Composable
private fun TileValue(
    value: String,
    small: String?,
    unitLeadingSpace: Boolean = true,
) {
    Text(
        text =
            buildAnnotatedString {
                append(value)
                if (small !=
                    null
                ) {
                    withStyle(unitStyle(TILE_UNIT_SP)) { append(if (unitLeadingSpace) " $small" else small) }
                }
            },
        style = VoltType.value.copy(fontSize = TILE_SP.sp),
        color = VoltColors.textPrimary,
    )
}

@Composable
private fun TileNote(text: String) {
    if (text.isEmpty()) return
    Text(text, style = VoltType.caption, color = VoltColors.textSecondary, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun SpeedCard(
    bands: List<SpeedEfficiency>,
    units: VoltUnits,
    loading: Boolean,
) {
    VoltPanel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            VoltLabel("Efficiency by speed", Modifier.weight(1f))
            Text(
                "${units.efficiencyUnit} · ${units.speedUnit}",
                style = VoltType.caption,
                color = VoltColors.textSecondary,
            )
        }
        val best = bands.best()
        if (best == null) {
            if (loading) {
                VoltEmptyState("Loading…", inCard = false)
            } else {
                VoltEmptyState(
                    "Not enough electric driving yet",
                    body = "Efficiency by speed appears once this period has a few electric drives.",
                    inCard = false,
                )
            }
            return@VoltPanel
        }
        Text(
            text =
                buildAnnotatedString {
                    append("Most efficient around ")
                    withStyle(SpanStyle(color = VoltColors.energy)) {
                        append(units.speedText(best.midMph.toDouble()))
                    }
                },
            style = VoltType.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
            color = VoltColors.textPrimary,
            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
        )
        CappedTextScale { SpeedBars(bands, best, units) }
    }
}

@Composable
private fun SpeedBars(
    bands: List<SpeedEfficiency>,
    best: SpeedEfficiency,
    units: VoltUnits,
) {
    val pal = LocalVoltPalette.current
    val measurer = rememberTextMeasurer()
    val axis = VoltType.label.copy(color = pal.faint, fontSize = 12.sp, letterSpacing = 0.04.sp)
    val bestStyle = axis.copy(color = pal.ev, fontWeight = FontWeight.SemiBold)
    // Bars are drawn in the shown unit. In kWh/100 km lower is better, so the best band is the
    // shortest bar there, still picked by mi/kWh and still the one highlighted.
    val value = { band: SpeedEfficiency -> units.efficiency(band.miPerKwh) ?: 0.0 }
    val scale = if (units.metric) METRIC_SCALE else 1.0
    val top = maxOf(SPEED_MIN_TOP * scale, ceil(bands.maxOf(value) + VALUE_HEADROOM * scale))
    val step = GRID_STEP * scale
    val described =
        bands.joinToString("; ") {
            "${units.speedText(it.midMph.toDouble())}: ${oneDecimal(value(it))} ${units.efficiencyUnit}"
        }
    Canvas(Modifier.fillMaxWidth().height(SPEED_DP.dp).semantics { contentDescription = described }) {
        val labelRoom = LABEL_ROOM_DP.dp.toPx()
        val chartH = size.height - labelRoom
        // Room above the tallest bar for the best band's value.
        val headroom = VALUE_ROOM_DP.dp.toPx()
        val y = { v: Double -> (chartH - v / top * (chartH - headroom)).toFloat() }
        // The grid figures sit in a gutter left of the bars, centred on their lines, so a tall bar
        // never runs through its own scale.
        val gridLabels = generateSequence(step) { it + step }.takeWhile { it < top }.toList()
        val measured = gridLabels.map { measurer.measure(oneDecimalOrWhole(it), axis) }
        val gutter = (measured.maxOfOrNull { it.size.width } ?: 0) + GUTTER_GAP_DP.dp.toPx()
        gridLabels.forEachIndexed { i, line ->
            drawLine(pal.line, Offset(gutter, y(line)), Offset(size.width, y(line)), 1.dp.toPx())
            val label = measured[i]
            drawAxisText(label, Offset(0f, (y(line) - label.size.height / 2f).coerceAtLeast(0f)))
        }
        val slot = (size.width - gutter) / bands.size
        bands.forEachIndexed { i, band ->
            val isBest = band == best
            val barW = slot - 2 * BAR_INSET_DP.dp.toPx()
            val x = gutter + i * slot + BAR_INSET_DP.dp.toPx()
            val h = chartH - y(value(band))
            drawRoundRect(
                if (isBest) pal.ev else pal.ev.copy(alpha = DIM_BAR_ALPHA),
                Offset(x, chartH - h),
                Size(barW, h),
                CornerRadius(RADIUS_DP.dp.toPx()),
            )
            val label = measurer.measure(units.speed(band.midMph.toDouble()).toString(), axis)
            drawAxisText(label, Offset(x + (barW - label.size.width) / 2, chartH + 8.dp.toPx()))
            if (isBest) {
                val figure = measurer.measure(oneDecimal(value(band)), bestStyle)
                drawAxisText(
                    figure,
                    Offset(
                        x + (barW - figure.size.width) / 2,
                        chartH - h - figure.size.height - 4.dp.toPx(),
                    ),
                )
            }
        }
    }
}

private fun DrawScope.drawAxisText(
    layout: TextLayoutResult,
    at: Offset,
) = drawText(layout, topLeft = at)

@Composable
private fun CellNote(drift: CellDrift) {
    VoltPanel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconSquare(VoltIcons.Cells, tone = PillTone.WARN, size = 40.dp, iconSize = 20.dp)
            Column(Modifier.weight(1f)) {
                Text("Cell ${drift.cell} trending low", style = VoltType.bodyStrong, color = VoltColors.textPrimary)
                Text(
                    text =
                        "It sits ${drift.belowMeanMv} mV lower than the average cell and has dropped " +
                            "${drift.driftMv} mV over ${drift.days} days. Worth watching.",
                    style = VoltType.caption,
                    color = VoltColors.textSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private fun wholeMpg(mpg: Double): String = if (mpg % 1.0 == 0.0) mpg.toInt().toString() else oneDecimal(mpg)

private fun oneDecimalOrWhole(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else oneDecimal(v)

private const val HERO_SP = 64
private const val HERO_UNIT_SP = 26
private const val TILE_SP = 28
private const val TILE_UNIT_SP = 14
private const val BARS_DP = 112
private const val BAR_FILL = 0.62f
private const val BAR_MAX_DP = 60
private const val RADIUS_DP = 6
private const val MIN_SEGMENT_DP = 4
private const val SPEED_DP = 164
private const val LABEL_ROOM_DP = 24
private const val BAR_INSET_DP = 8
private const val DIM_BAR_ALPHA = 0.32f
private const val SPEED_MIN_TOP = 5.0
private const val GRID_STEP = 2.0
private const val VALUE_HEADROOM = 0.3

/** kWh/100 km figures run about 2.5× the mi/kWh ones: the grid and headroom scale with them. */
private const val METRIC_SCALE = 2.5
private const val VALUE_ROOM_DP = 18
private const val GUTTER_GAP_DP = 6

@Preview(widthDp = 412, heightDp = 1100)
@Composable
private fun InsightsScreenPreview() {
    VoltTheme { InsightsScreen(InsightsUiState.demo) }
}
