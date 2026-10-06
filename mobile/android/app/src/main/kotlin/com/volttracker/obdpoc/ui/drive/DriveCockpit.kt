package com.volttracker.obdpoc.ui.drive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.volttracker.obdpoc.ui.charge.costText
import com.volttracker.obdpoc.ui.components.CellHistogram
import com.volttracker.obdpoc.ui.components.DASH
import com.volttracker.obdpoc.ui.components.MIN_CELLS_FOR_HISTOGRAM
import com.volttracker.obdpoc.ui.components.NOT_REPORTED
import com.volttracker.obdpoc.ui.components.PillTone
import com.volttracker.obdpoc.ui.components.VoltLabel
import com.volttracker.obdpoc.ui.components.glideTenths
import com.volttracker.obdpoc.ui.components.glideWhole
import com.volttracker.obdpoc.ui.components.pillColor
import com.volttracker.obdpoc.ui.components.spoken
import com.volttracker.obdpoc.ui.components.voltCard
import com.volttracker.obdpoc.ui.components.withUnit
import com.volttracker.obdpoc.ui.theme.LocalVoltPalette
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltFonts
import com.volttracker.obdpoc.ui.theme.VoltShapes
import com.volttracker.obdpoc.ui.theme.VoltSpacing
import com.volttracker.obdpoc.ui.theme.VoltType
import com.volttracker.obdpoc.ui.units.VoltUnits
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
    // Three across normally. At the larger text sizes a third of the width cut "86% · +6 A" short
    // and stacked the four tyres one per line, so the tyres take a full-width row of their own.
    if (LocalDensity.current.fontScale > WIDE_THERMS_MAX_SCALE) {
        CockpitRow {
            Aux12Card(state, Modifier.weight(1f))
            MotorsCard(state, Modifier.weight(1f))
        }
        CockpitRow { TiresCard(state, Modifier.weight(1f)) }
    } else {
        CockpitRow {
            Aux12Card(state, Modifier.weight(1f))
            TiresCard(state, Modifier.weight(1f))
            MotorsCard(state, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Aux12Card(
    state: DriveUiState,
    modifier: Modifier,
) {
    SmallCard("12V", modifier) {
        NumberUnit(state.aux12Volts?.let(::oneDecimal) ?: DASH, " V", 20f, Modifier.padding(top = 3.dp))
        Sub(
            listOfNotNull(
                state.aux12SocPercent?.let { "$it%" },
                state.aux12Amps?.let { (if (it >= 0) "+" else "−") + "${abs(it).toInt()} A" },
            ).joinToString(" · ").ifEmpty { "Not reported" },
        )
    }
}

@Composable
private fun TiresCard(
    state: DriveUiState,
    modifier: Modifier,
) {
    SmallCard("Tires", modifier, unit = state.units.pressureUnit) {
        TiresMini(state.tires, state.tirePlacardPsi, state.units)
    }
}

@Composable
private fun MotorsCard(
    state: DriveUiState,
    modifier: Modifier,
) {
    SmallCard("Motors", modifier) {
        val a = motorLabel(state, state.motorAKw)
        val b = motorLabel(state, state.motorBKw)
        val known = a != DASH || b != DASH
        Text(
            text =
                buildAnnotatedString {
                    append(a)
                    if (known) {
                        withStyle(SpanStyle(fontSize = 12.sp, color = VoltColors.textSecondary)) { append(" / ") }
                        append(b)
                        withStyle(SpanStyle(fontSize = 12.sp, color = VoltColors.textSecondary)) { append(" kW") }
                    }
                },
            style = VoltType.value.copy(fontSize = 20.sp),
            color = VoltColors.textPrimary,
            modifier =
                Modifier.padding(top = 3.dp).semantics {
                    contentDescription =
                        if (known) "Motor A ${spoken(a)} kilowatts, motor B ${spoken(b)} kilowatts" else NOT_REPORTED
                },
        )
        // The Volt's two drive motors are named A and B (both in the one front drive unit).
        Sub("Motor A / B")
    }
}

/** A motor's kW: "0" parked (it is idle, not unknown), a dash when stale or not connected. */
private fun motorLabel(
    state: DriveUiState,
    kw: Double?,
): String =
    when {
        !state.connected || kw == null -> DASH
        state.phase != DrivePhase.DRIVE -> "0"
        else -> String.format(Locale.US, "%.0f", kw)
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
    radius: Dp = VoltShapes.TileRadius,
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
    // The right label drops to its own line when both don't fit (large text in a half-width
    // card) instead of being cut to "#4…" / "T…".
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        VoltLabel(left, Modifier.padding(end = 6.dp))
        if (right.isNotEmpty()) VoltLabel(right, color = rightColor)
    }
}

/** The muted key/value line under a cockpit figure (mockups `.c-kv`). */
@Composable
private fun KvRow(
    vararg items: String,
    modifier: Modifier = Modifier,
    colors: List<Color?> = emptyList(),
) {
    // Spread across the card, wrapping onto a second line at large text rather than cutting
    // "356.4 V" to "356…".
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
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
                softWrap = false,
                modifier = Modifier.padding(end = if (i == items.lastIndex) 0.dp else 6.dp),
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
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 1.dp),
    )
}

@Composable
private fun MainCard(state: DriveUiState) {
    val pal = LocalVoltPalette.current
    val driving = state.phase == DrivePhase.DRIVE
    val charging = state.phase == DrivePhase.CHARGING
    CockpitCard(radius = VoltShapes.CardRadius, padding = VoltSpacing.tile) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text =
                        if (!state.connected) {
                            DASH
                        } else if (driving) {
                            state.speedMph?.let { "${glideWhole(state.units.speed(it.toDouble()), "cockpit-speed")}" }
                                ?: DASH
                        } else {
                            state.shownSocPercent?.let { "${glideWhole(it.toInt(), "cockpit-soc")}" } ?: DASH
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
                    text =
                        when {
                            !state.connected -> ""
                            driving -> state.units.speedUnit
                            else -> "% battery"
                        },
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
                val kw = if (charging) state.chargeKw else state.powerKw
                val shownKw = glideTenths(kw ?: 0.0, "cockpit-power")
                val value =
                    when {
                        !state.connected || kw == null -> DASH
                        charging -> "+" + oneDecimal(shownKw)
                        else -> (if (state.regenerating && shownKw < 0) "−" else "") + oneDecimal(abs(shownKw))
                    }
                val unit = if (state.connected) " kW" else ""
                NumberUnit(value, unit, 26f, Modifier.padding(top = 8.dp), color = color, unitSize = 13f)
                val what =
                    when {
                        charging -> "charging"
                        state.regenerating -> "regen"
                        else -> "pack power"
                    }
                Text(
                    text = if (!state.gearKnown) what else "$what · gear ${state.gear}",
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
    val described = powerTraceDescription(values)
    Canvas(modifier.semantics { contentDescription = described }) {
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
    val holding = state.gasDriving
    val soc = state.shownSocPercent
    val shownSoc = glideWhole(soc?.toInt() ?: 0, "cockpit-battery-soc")
    CockpitCard(modifier) {
        CapRow(
            "HV battery",
            when {
                !state.connected || soc == null -> "Not reported"
                holding -> "Holding charge"
                else -> "OK"
            },
            pillColor(if (holding) PillTone.GAS else PillTone.EV),
        )
        Text(
            text =
                buildAnnotatedString {
                    append(if (state.connected && soc != null) "$shownSoc" else DASH)
                    if (state.connected && soc != null) {
                        withStyle(SpanStyle(fontSize = 13.sp, color = VoltColors.textSecondary)) { append("%") }
                    }
                },
            style = VoltType.value.copy(fontSize = 30.sp),
            color = VoltColors.textPrimary,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )
        Meter(((soc ?: 0.0) / 100).toFloat(), VoltColors.energy)
        KvRow(
            withUnit(state.packVolts?.let(::oneDecimal), "V"),
            withUnit(state.packAmps?.let { "${it.toInt()}" }, "A"),
            state.packTempF?.let { state.units.tempText(it.toDouble()) } ?: DASH,
        )
    }
}

@Composable
private fun RangeMiniCard(
    state: DriveUiState,
    modifier: Modifier = Modifier,
) {
    CockpitCard(modifier) {
        val units = state.units
        CapRow("Range", state.totalRangeWhole(units)?.let { "$it ${units.distanceUnit}" } ?: DASH)
        RangeBar(
            "EV",
            PillTone.EV,
            state.shownSocPercent?.let { (it / 100).toFloat() }?.takeIf { state.connected },
            state.evRangeMiles?.let(units::distanceWhole) ?: DASH,
        )
        RangeBar(
            "Gas",
            PillTone.GAS,
            state.fuelPercent?.let { (it / 100).toFloat() },
            state.gasRangeMiles?.let(units::distanceWhole) ?: DASH,
        )
        val engineOn = state.mode == DriveMode.GAS && state.rpm > 0
        KvRow(
            "Engine",
            when {
                !state.connected -> DASH
                engineOn -> "${String.format(Locale.US, "%,d", state.rpm)} rpm"
                else -> "off"
            },
            colors = listOf(null, if (engineOn) VoltColors.gas else null),
        )
    }
}

@Composable
private fun RangeBar(
    label: String,
    tone: PillTone,
    fraction: Float?,
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
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.widthIn(min = 28.dp),
        )
        // No reading, no meter: an empty bar would claim an empty tank.
        if (fraction != null) Meter(fraction, pillColor(tone), Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = VoltType.valueSmall.copy(fontSize = 15.sp),
            color = VoltColors.textPrimary,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.widthIn(min = 34.dp),
            textAlign = TextAlign.End,
        )
    }
}

/** Above this text scale the six thermometers wrap onto two rows and the tyres get a row of their own. */
private const val WIDE_THERMS_MAX_SCALE = 1.15f

/**
 * One cockpit thermometer: range and warn threshold follow the mockups' `therm` calls. The scale
 * and threshold stay in °F (the store's scale); only the printed figure follows Settings → Units.
 */
private data class Therm(
    val label: String,
    val valueF: Int?,
    val lo: Int,
    val hi: Int,
    val warnAt: Int,
    /** What a screen reader says, when the printed [label] is abbreviated. */
    val spokenLabel: String = label,
)

@Composable
private fun TemperaturesCard(state: DriveUiState) {
    val therms =
        listOf(
            Therm("Pack", state.packTempF?.takeIf { state.connected }, 20, 120, 104),
            Therm("Motor A", state.motorTempF, 40, 260, 230),
            Therm("Inverter", state.inverterTempF, 40, 220, 190),
            Therm("Coolant", state.coolantF?.takeIf { state.connected }, 40, 240, 225),
            Therm("Trans.", state.transTempF?.takeIf { state.connected }, 40, 260, 240, "Transmission"),
            Therm("Cabin", state.cabinTempF, 20, 120, 110),
        )
    CockpitCard(Modifier.padding(top = 8.dp)) {
        CapRow("Temperatures", state.units.tempUnit)
        // Six across normally; at the larger text sizes two rows of three so the labels stay whole.
        val perRow = if (LocalDensity.current.fontScale > WIDE_THERMS_MAX_SCALE) therms.size / 2 else therms.size
        therms.chunked(perRow).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                row.forEach { Thermometer(it, state.units, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Thermometer(
    t: Therm,
    units: VoltUnits,
    modifier: Modifier = Modifier,
) {
    val warn = t.valueF != null && t.valueF >= t.warnAt
    val fraction = t.valueF?.let { ((it - t.lo).toFloat() / (t.hi - t.lo)).coerceIn(0f, 1f) } ?: 0f
    val fill = if (warn) VoltColors.warn else VoltColors.accent
    val reading = t.valueF?.let { units.tempText(it.toDouble()) }
    Column(
        modifier.semantics(mergeDescendants = true) {
            contentDescription = "${t.spokenLabel} ${reading ?: NOT_REPORTED}${if (warn) ", high" else ""}"
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
            text = t.valueF?.let { "${units.temp(it.toDouble())}°" } ?: DASH,
            style = VoltType.valueSmall.copy(fontSize = 16.sp),
            color = if (warn) VoltColors.warn else VoltColors.textPrimary,
            modifier = Modifier.padding(top = 6.dp),
        )
        // The labels are short ("Trans.") so they fit one line in a six-across column.
        Text(
            text = t.label,
            style = VoltType.caption.copy(fontSize = 11.sp, hyphens = Hyphens.Auto, textAlign = TextAlign.Center),
            color = VoltColors.textSecondary,
            maxLines = 2,
        )
    }
}

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
            val described = cellsDescription(state)
            CellHistogram(
                state.cellVoltages,
                state.minCellNumber,
                Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .height(40.dp)
                    .semantics { contentDescription = described },
            )
        } else {
            Text(
                text = "Per-cell detail appears after a full cell read",
                style = VoltType.caption.copy(fontSize = 11.5.sp),
                color = VoltColors.textTertiary,
                modifier = Modifier.padding(top = 6.dp).heightIn(min = 40.dp),
            )
        }
        KvRow(
            spread?.let { "${it.toInt()} mV spread" } ?: DASH,
            state.maxCellVolts?.let { String.format(Locale.US, "%.3f V", it) } ?: DASH,
            state.minCellVolts?.let { String.format(Locale.US, "%.3f V", it) } ?: DASH,
        )
    }
}

@Composable
private fun EfficiencyCard(
    state: DriveUiState,
    modifier: Modifier = Modifier,
) {
    val mpg = state.cycleMpg.takeIf { state.mode == DriveMode.GAS }
    val units = state.units
    CockpitCard(modifier) {
        CapRow("Efficiency", if (mpg != null) "cycle" else "trip")
        NumberUnit(
            value =
                if (mpg != null) {
                    units.economyValue(mpg) ?: DASH
                } else {
                    units.efficiencyValue(state.shownTripMiPerKwh) ?: DASH
                },
            unit = if (mpg != null) " ${units.economyUnit}" else " ${units.efficiencyUnit}",
            size = 30f,
            unitSize = 13f,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )
        KvRow(
            if (state.connected) "${units.distanceOneDecimal(state.tripMiles)} ${units.distanceUnit}" else DASH,
            if (state.connected) state.tripDuration else DASH,
            state.cycleEvPercent?.let { "$it% EV" } ?: DASH,
        )
        KvRow(
            withUnit(state.tripKwh?.let(::oneDecimal), "kWh"),
            costText(state.tripKwh, state.electricityRate)
                ?: if (state.connected) "max ${units.speedText(state.tripMaxMph.toDouble())}" else DASH,
            state.ambientF?.takeIf { state.connected }?.let { "${units.tempText(it.toDouble())} out" } ?: DASH,
            modifier = Modifier.padding(top = 0.dp),
        )
    }
}

@Composable
private fun SmallCard(
    label: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.voltCard(radius = VoltShapes.TileRadius).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        VoltLabel(label, unit = unit)
        content()
    }
}

/** A tiny top-down car with each tyre tinted by its pressure (mockups `tiresMini`). */
@Composable
private fun TiresMini(
    tires: TirePressures?,
    placardPsi: Double,
    units: VoltUnits,
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
            Text(
                DASH,
                style = VoltType.valueSmall.copy(fontSize = 14.sp),
                color = VoltColors.textTertiary,
                modifier = Modifier.semantics { contentDescription = NOT_REPORTED },
            )
        } else {
            Column {
                listOf(tires.fl to tires.fr, tires.rl to tires.rr).forEach { (l, r) ->
                    Text(
                        text = "${units.pressure(l)}  ${units.pressure(r)}",
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
