package com.volttracker.obdpoc.ui.drive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.volttracker.obdpoc.ui.components.CellHistogram
import com.volttracker.obdpoc.ui.components.MIN_CELLS_FOR_HISTOGRAM
import com.volttracker.obdpoc.ui.components.PillTone
import com.volttracker.obdpoc.ui.components.VoltLabel
import com.volttracker.obdpoc.ui.components.pillColor
import com.volttracker.obdpoc.ui.components.voltCard
import com.volttracker.obdpoc.ui.theme.LocalVoltPalette
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltFonts
import com.volttracker.obdpoc.ui.theme.VoltType
import java.util.Locale
import kotlin.math.abs

/** Direction C "Cockpit": every live signal at a glance — the Detailed view. */
@Composable
internal fun ColumnScope.CockpitContent(state: DriveUiState) {
    MainCard(state)
    CockpitRow {
        BatteryCard(state, Modifier.weight(1f))
        RangeMiniCard(state, Modifier.weight(1f))
    }
    TemperaturesCard(state)
    CockpitRow {
        CellsCard(state, Modifier.weight(1f))
        EfficiencyCard(state, Modifier.weight(1f))
    }
    CockpitRow {
        SmallCard("12V", Modifier.weight(1f)) {
            NumberUnit(state.aux12Volts?.let(::oneDecimal) ?: "--", " V", 20f, Modifier.padding(top = 3.dp))
            Sub(
                listOfNotNull(
                    state.aux12SocPercent?.let { "$it%" },
                    state.aux12Amps?.let { (if (it >= 0) "+" else "−") + "${abs(it).toInt()} A" },
                ).joinToString(" · ").ifEmpty { "Not reported" },
            )
        }
        SmallCard("Tires psi", Modifier.weight(1f)) { TiresMini(state.tires, state.tirePlacardPsi) }
        SmallCard("Motors", Modifier.weight(1f)) {
            val a = if (state.phase == DrivePhase.DRIVE) state.motorAKw else 0.0
            val b = if (state.phase == DrivePhase.DRIVE) state.motorBKw else 0.0
            Text(
                text =
                    buildAnnotatedString {
                        append(String.format(Locale.US, "%.0f", a))
                        withStyle(SpanStyle(fontSize = 12.sp, color = VoltColors.textSecondary)) { append(" / ") }
                        append(String.format(Locale.US, "%.0f", b))
                        withStyle(SpanStyle(fontSize = 12.sp, color = VoltColors.textSecondary)) { append(" kW") }
                    },
                style = VoltType.value.copy(fontSize = 20.sp),
                color = VoltColors.textPrimary,
                modifier = Modifier.padding(top = 3.dp),
            )
            Sub("A / B")
        }
    }
}

@Composable
private fun CockpitRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun CockpitCard(
    modifier: Modifier = Modifier,
    radius: Dp = 16.dp,
    padding: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.voltCard(radius = radius).padding(padding), content = content)
}

@Composable
private fun CapRow(
    left: String,
    right: String,
    rightColor: Color = VoltColors.textTertiary,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        VoltLabel(left)
        VoltLabel(right, color = rightColor)
    }
}

/** The muted key/value line under a cockpit figure (mockups `.c-kv`). */
@Composable
private fun KvRow(
    vararg items: String,
    modifier: Modifier = Modifier,
    colors: List<Color?> = emptyList(),
) {
    Row(modifier = modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        items.forEachIndexed { i, text ->
            Text(
                text = text,
                style =
                    VoltType.caption.copy(
                        fontSize = 12.sp,
                        fontFamily = VoltFonts.barlow,
                        fontWeight = FontWeight.Medium,
                    ),
                color = colors.getOrNull(i) ?: VoltColors.textSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Sub(text: String) {
    Text(
        text = text,
        style = VoltType.caption.copy(fontSize = 11.5.sp),
        color = VoltColors.textSecondary,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.padding(top = 1.dp),
    )
}

@Composable
private fun MainCard(state: DriveUiState) {
    val pal = LocalVoltPalette.current
    val driving = state.phase == DrivePhase.DRIVE
    val charging = state.phase == DrivePhase.CHARGING
    CockpitCard(radius = 18.dp, padding = 14.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text =
                        if (!state.connected) {
                            "--"
                        } else if (driving) {
                            "${state.speedMph}"
                        } else {
                            "${state.shownSocPercent.toInt()}"
                        },
                    style =
                        VoltType.value.copy(
                            fontSize = 80.sp,
                            fontWeight = FontWeight.Normal,
                            lineHeight = 68.sp,
                            letterSpacing = (-0.03).em,
                        ),
                    color = VoltColors.textPrimary,
                )
                Text(
                    text = if (driving) "mph" else "% SOC",
                    style = VoltType.caption.copy(fontSize = 13.sp),
                    color = VoltColors.textSecondary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                ModePill(state)
                val color =
                    when {
                        charging -> pal.ev
                        !driving -> pal.muted
                        else -> powerColor(pal, state.powerRole)
                    }
                val value =
                    if (charging) {
                        "+" + oneDecimal(state.chargeKw)
                    } else {
                        (if (state.regenerating) "−" else "") + oneDecimal(abs(state.powerKw))
                    }
                NumberUnit(value, " kW", 26f, Modifier.padding(top = 8.dp), color = color, unitSize = 13f)
                val what =
                    when {
                        charging -> "charging"
                        state.regenerating -> "regen"
                        else -> "pack power"
                    }
                Text(
                    text = "$what · gear ${state.gear}",
                    style = VoltType.caption.copy(fontSize = 11.5.sp),
                    color = VoltColors.textSecondary,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        PowerStrip(state, Modifier.padding(top = 10.dp).fillMaxWidth().height(46.dp))
        Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("−60 s", "power", "now").forEach {
                Text(
                    text = it.uppercase(Locale.US),
                    style =
                        VoltType.label.copy(
                            fontSize = 10.sp,
                            letterSpacing = 0.08.em,
                            fontWeight = FontWeight.Medium,
                        ),
                    color = VoltColors.textTertiary,
                )
            }
        }
    }
}

/** The last minute of pack power as signed bars: teal drive, green regen, amber with the engine on. */
@Composable
private fun PowerStrip(
    state: DriveUiState,
    modifier: Modifier = Modifier,
) {
    val pal = LocalVoltPalette.current
    val values = state.powerTrace
    val gas = state.gasTrace
    Canvas(modifier.semantics { contentDescription = "Pack power over the last minute" }) {
        val zero = size.height * STRIP_ZERO
        drawLine(pal.line2, Offset(0f, zero), Offset(size.width, zero), strokeWidth = 1.dp.toPx())
        if (values.isEmpty()) return@Canvas
        val slots = maxOf(values.size, STRIP_SLOTS)
        val bw = size.width / slots
        val offset = slots - values.size
        values.forEachIndexed { i, v ->
            val h =
                if (v >= 0) {
                    (v.coerceIn(0f, STRIP_DRIVE_KW) / STRIP_DRIVE_KW) * zero
                } else {
                    ((-v).coerceIn(0f, STRIP_REGEN_KW) / STRIP_REGEN_KW) * (size.height - zero)
                }
            val color =
                when {
                    v < REGEN_BAR_KW -> pal.ev
                    gas.getOrElse(i) { false } -> pal.gas
                    else -> pal.volt
                }
            val slot = i + offset
            val alpha = STRIP_MIN_ALPHA + (1 - STRIP_MIN_ALPHA) * (slot.toFloat() / slots)
            val barH = maxOf(h, 1f)
            drawRoundRect(
                color = color.copy(alpha = alpha),
                topLeft = Offset(slot * bw + BAR_INSET * bw, if (v >= 0) zero - barH else zero),
                size = Size(bw * (1 - 2 * BAR_INSET), barH),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}

@Composable
private fun BatteryCard(
    state: DriveUiState,
    modifier: Modifier = Modifier,
) {
    val sustain = state.gasDriving
    val soc = state.shownSocPercent
    CockpitCard(modifier) {
        CapRow("HV battery", if (sustain) "sustain" else "ok", pillColor(if (sustain) PillTone.GAS else PillTone.EV))
        Text(
            text =
                buildAnnotatedString {
                    append(if (state.connected) "${soc.toInt()}" else "--")
                    withStyle(SpanStyle(fontSize = 13.sp, color = VoltColors.textSecondary)) { append("%") }
                    withStyle(
                        SpanStyle(fontSize = 11.sp, color = VoltColors.textTertiary, fontFamily = VoltFonts.hanken),
                    ) {
                        append("  raw ${oneDecimal(state.socPercent)}%")
                    }
                },
            style = VoltType.value.copy(fontSize = 30.sp),
            color = VoltColors.textPrimary,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )
        Meter((soc / 100).toFloat(), VoltColors.energy)
        KvRow(
            "${oneDecimal(state.packVolts)} V",
            "${state.packAmps.toInt()} A",
            "${state.packTempF}°F",
        )
    }
}

@Composable
private fun RangeMiniCard(
    state: DriveUiState,
    modifier: Modifier = Modifier,
) {
    CockpitCard(modifier) {
        CapRow("Range", state.totalRangeMiles?.let { "${wholeLabel(it)} mi" } ?: "--")
        RangeBar("EV", PillTone.EV, (state.shownSocPercent / 100).toFloat(), wholeLabel(state.evRangeMiles))
        RangeBar("Gas", PillTone.GAS, ((state.fuelPercent ?: 0.0) / 100).toFloat(), wholeLabel(state.gasRangeMiles))
        val engineOn = state.mode == DriveMode.GAS && state.rpm > 0
        KvRow(
            "Engine",
            if (engineOn) "${String.format(Locale.US, "%,d", state.rpm)} rpm" else "off",
            colors = listOf(null, if (engineOn) VoltColors.gas else null),
        )
    }
}

@Composable
private fun RangeBar(
    label: String,
    tone: PillTone,
    fraction: Float,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = VoltType.caption.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            color = pillColor(tone),
            modifier = Modifier.width(28.dp),
        )
        Meter(fraction, pillColor(tone), Modifier.weight(1f))
        Text(
            text = value,
            style = VoltType.valueSmall.copy(fontSize = 15.sp),
            color = VoltColors.textPrimary,
            modifier = Modifier.width(34.dp),
            textAlign = TextAlign.End,
        )
    }
}

/** One cockpit thermometer: range and warn threshold follow the mockups' `therm` calls. */
private data class Therm(
    val label: String,
    val valueF: Int?,
    val lo: Int,
    val hi: Int,
    val warnAt: Int,
)

@Composable
private fun TemperaturesCard(state: DriveUiState) {
    val therms =
        listOf(
            Therm("Pack", state.packTempF.takeIf { state.connected }, 20, 120, 104),
            Therm("Motor A", state.motorTempF, 40, 260, 230),
            Therm("Inverter", state.inverterTempF, 40, 220, 190),
            Therm("Coolant", state.coolantF.takeIf { state.connected }, 40, 240, 225),
            Therm("Trans", state.transTempF.takeIf { state.connected }, 40, 260, 240),
            Therm("Cabin", state.cabinTempF, 20, 120, 110),
        )
    CockpitCard(Modifier.padding(top = 8.dp)) {
        CapRow("Temperatures", "°F")
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            therms.forEach { Thermometer(it, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun Thermometer(
    t: Therm,
    modifier: Modifier = Modifier,
) {
    val warn = t.valueF != null && t.valueF >= t.warnAt
    val fraction = t.valueF?.let { ((it - t.lo).toFloat() / (t.hi - t.lo)).coerceIn(0f, 1f) } ?: 0f
    val fill = if (warn) VoltColors.warn else VoltColors.accent
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier =
                Modifier
                    .size(6.dp, 40.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(VoltColors.track),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(fraction)
                        .clip(RoundedCornerShape(3.dp))
                        .background(fill),
                )
            }
        }
        Text(
            text = t.valueF?.let { "$it°" } ?: "--",
            style = VoltType.valueSmall.copy(fontSize = 16.sp),
            color = if (warn) VoltColors.warn else VoltColors.textPrimary,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = t.label,
            style = VoltType.caption.copy(fontSize = 11.sp),
            color = VoltColors.textSecondary,
            maxLines = 1,
        )
    }
}

/** Cell spread at which the weakest group is called out (matches the Health "watch" band). */
private const val CELL_WATCH_MV = 50.0

@Composable
private fun CellsCard(
    state: DriveUiState,
    modifier: Modifier = Modifier,
) {
    val spread = state.cellSpreadMv
    val weak = spread != null && spread >= CELL_WATCH_MV
    val count = state.cellVoltages.size.takeIf { it > 0 } ?: CELL_GROUPS
    CockpitCard(modifier) {
        CapRow(
            "Cells · $count",
            state.minCellNumber?.let { "#$it ${if (weak) "low" else "min"}" } ?: "",
            if (weak) VoltColors.warn else VoltColors.textTertiary,
        )
        if (state.cellVoltages.count { it != null } >= MIN_CELLS_FOR_HISTOGRAM) {
            CellHistogram(
                state.cellVoltages,
                state.minCellNumber,
                Modifier.padding(top = 6.dp).fillMaxWidth().height(40.dp),
            )
        } else {
            Text(
                text = "Per-cell detail appears after a full cell read",
                style = VoltType.caption.copy(fontSize = 11.5.sp),
                color = VoltColors.textTertiary,
                modifier = Modifier.padding(top = 6.dp).height(40.dp),
            )
        }
        KvRow(
            spread?.let { "Δ ${it.toInt()} mV" } ?: "Δ --",
            state.maxCellVolts?.let { String.format(Locale.US, "%.3f V", it) } ?: "--",
            state.minCellVolts?.let { String.format(Locale.US, "%.3f V", it) } ?: "--",
        )
    }
}

@Composable
private fun EfficiencyCard(
    state: DriveUiState,
    modifier: Modifier = Modifier,
) {
    val mpg = state.cycleMpg.takeIf { state.mode == DriveMode.GAS }
    CockpitCard(modifier) {
        CapRow("Efficiency", if (mpg != null) "cycle" else "trip")
        NumberUnit(
            value = mpg?.let(::oneDecimal) ?: state.tripMiPerKwh?.let(::oneDecimal) ?: "--",
            unit = if (mpg != null) " mpg" else " mi/kWh",
            size = 30f,
            unitSize = 13f,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )
        KvRow(
            "${oneDecimal(state.tripMiles)} mi",
            state.tripDuration,
            state.cycleEvPercent?.let { "$it% EV" } ?: "--",
        )
        KvRow(
            state.tripKwh?.let { "${oneDecimal(it)} kWh" } ?: "-- kWh",
            costLabel(state.tripKwh, state.electricityRate) ?: "max ${state.tripMaxMph}",
            "${state.ambientF}°F out",
            modifier = Modifier.padding(top = 0.dp),
        )
    }
}

@Composable
private fun SmallCard(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.voltCard(radius = 14.dp).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        VoltLabel(label)
        content()
    }
}

/** A tiny top-down car with each tyre tinted by its pressure (mockups `tiresMini`). */
@Composable
private fun TiresMini(
    tires: TirePressures?,
    placardPsi: Double,
) {
    val pal = LocalVoltPalette.current
    Row(
        modifier = Modifier.padding(top = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(Modifier.size(26.dp, 40.dp)) {
            val k = size.width / 40f
            drawRoundRect(pal.carBody, Offset(8 * k, 4 * k), Size(24 * k, 52 * k), CornerRadius(9 * k))
            drawRoundRect(
                pal.carLine,
                Offset(8 * k, 4 * k),
                Size(24 * k, 52 * k),
                CornerRadius(9 * k),
                style =
                    Stroke(
                        1.2f * k,
                    ),
            )
            val spots = listOf(Offset(3f, 11f), Offset(32f, 11f), Offset(3f, 39f), Offset(32f, 39f))
            spots.forEachIndexed { i, o ->
                val psi = tires?.all?.get(i)
                val color =
                    when {
                        psi == null -> pal.faint
                        tireLow(psi, placardPsi) -> pal.warn
                        else -> pal.ev
                    }
                drawRoundRect(color, Offset(o.x * k, o.y * k), Size(5 * k, 10 * k), CornerRadius(2 * k))
            }
        }
        if (tires == null) {
            Text("--", style = VoltType.valueSmall.copy(fontSize = 14.sp), color = VoltColors.textTertiary)
        } else {
            Column {
                listOf(tires.fl to tires.fr, tires.rl to tires.rr).forEach { (l, r) ->
                    Text(
                        text = "${wholeLabel(l)}  ${wholeLabel(r)}",
                        style = VoltType.valueSmall.copy(fontSize = 14.sp, lineHeight = 19.sp),
                        color = VoltColors.textPrimary,
                    )
                }
            }
        }
    }
}

private const val CELL_GROUPS = 96
private const val STRIP_ZERO = 0.62f
private const val STRIP_DRIVE_KW = 60f
private const val STRIP_REGEN_KW = 30f
private const val STRIP_SLOTS = 58
private const val STRIP_MIN_ALPHA = 0.35f
private const val BAR_INSET = 0.12f
private const val REGEN_BAR_KW = -0.3f
