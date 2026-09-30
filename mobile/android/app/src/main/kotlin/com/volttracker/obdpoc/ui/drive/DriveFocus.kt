package com.volttracker.obdpoc.ui.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volttracker.obdpoc.ui.charge.costText
import com.volttracker.obdpoc.ui.charge.levelName
import com.volttracker.obdpoc.ui.components.DASH
import com.volttracker.obdpoc.ui.components.IconSquare
import com.volttracker.obdpoc.ui.components.LocalVoltPrefs
import com.volttracker.obdpoc.ui.components.NOT_REPORTED
import com.volttracker.obdpoc.ui.components.PillTone
import com.volttracker.obdpoc.ui.components.VoltIcons
import com.volttracker.obdpoc.ui.components.VoltLabel
import com.volttracker.obdpoc.ui.components.VoltPanel
import com.volttracker.obdpoc.ui.components.announceChanges
import com.volttracker.obdpoc.ui.components.pillColor
import com.volttracker.obdpoc.ui.components.voltCard
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltShapes
import com.volttracker.obdpoc.ui.theme.VoltType
import kotlin.math.roundToInt

/**
 * Number + small muted unit, the mockups' `<b>12.4</b><small> mi</small>` pairing. A [DASH]
 * placeholder drops the unit and reads "Not reported"; a figure too wide for a narrow tile at a
 * large text size steps down in size instead of clipping.
 */
@Composable
internal fun NumberUnit(
    value: String,
    unit: String,
    size: Float,
    modifier: Modifier = Modifier,
    color: Color = VoltColors.textPrimary,
    unitSize: Float = 12f,
) {
    val muted = VoltColors.textSecondary
    Text(
        text =
            buildAnnotatedString {
                append(value)
                if (unit.isNotEmpty() && value != DASH) {
                    withStyle(
                        SpanStyle(fontSize = unitSize.sp, color = muted, fontWeight = FontWeight.Medium),
                    ) { append(unit) }
                }
            },
        style = VoltType.value.copy(fontSize = size.sp),
        color = color,
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = (size * MIN_FIT).sp, maxFontSize = size.sp),
        modifier = if (value == DASH) modifier.semantics { contentDescription = NOT_REPORTED } else modifier,
    )
}

/** A 6dp rounded meter (mockups `.meter`). */
@Composable
internal fun Meter(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Float = 6f,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height.dp)
                .clip(VoltShapes.chip)
                .background(VoltColors.track),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(height.dp)
                    .clip(VoltShapes.chip)
                    .background(color),
            )
        }
    }
}

/** EV and gas range with their battery/tank meters (mockups `rangeRows`). */
@Composable
internal fun RangeCard(state: DriveUiState) {
    val gasMode = state.gasDriving
    val soc = state.shownSocPercent
    VoltPanel(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VoltLabel("Range")
            NumberUnit(
                value = state.totalRangeWhole(state.units)?.toString() ?: DASH,
                unit = " ${state.units.distanceUnit} total",
                size = 17f,
            )
        }
        val evSub =
            when {
                !state.connected -> "Battery not reported"
                gasMode && state.atReserve -> "Battery at reserve · holding ${state.socPercent.toInt()}%"
                else -> "${soc.toInt()}% battery"
            }
        RangeRow(
            icon = VoltIcons.Bolt,
            tone = PillTone.EV,
            title = "Electric",
            distance = state.evRangeMiles?.let(state.units::distanceWhole),
            unit = state.units.distanceUnit,
            fraction = if (state.connected) (soc / 100).toFloat() else 0f,
            sub = evSub,
            dim = gasMode,
        )
        HorizontalDivider(color = VoltColors.hairline, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
        val fuel = state.fuelPercent
        RangeRow(
            icon = VoltIcons.Fuel,
            tone = PillTone.GAS,
            title = "Gas",
            distance = state.gasRangeMiles?.let(state.units::distanceWhole),
            unit = state.units.distanceUnit,
            // No tank reading, no meter: an empty bar would claim an empty tank.
            fraction = fuel?.let { (it / 100).toFloat() },
            sub =
                listOfNotNull(
                    fuel?.let { "${it.toInt()}% tank" } ?: "Tank level not reported",
                    "engine running".takeIf { state.connected && state.mode == DriveMode.GAS },
                ).joinToString(" · "),
            dim = false,
        )
    }
}

@Composable
private fun RangeRow(
    icon: ImageVector,
    tone: PillTone,
    title: String,
    distance: String?,
    unit: String,
    fraction: Float?,
    sub: String,
    dim: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().alpha(if (dim) DIM_ALPHA else 1f).padding(top = 4.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconSquare(icon, tone = tone)
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = title, style = VoltType.bodyStrong.copy(fontSize = 14.sp), color = VoltColors.textPrimary)
                NumberUnit(value = distance ?: DASH, unit = " $unit", size = 20f)
            }
            if (fraction != null) {
                Meter(
                    fraction = fraction,
                    color = pillColor(tone),
                    modifier = Modifier.padding(top = 6.dp, bottom = 5.dp),
                )
            } else {
                Spacer(Modifier.height(4.dp))
            }
            Text(text = sub, style = VoltType.caption.copy(fontSize = 12.sp), color = VoltColors.textSecondary)
        }
    }
}

/** One small stat tile (mockups `.tile`). */
@Composable
internal fun StatTile(
    label: String,
    value: String,
    unit: String,
    sub: String,
    modifier: Modifier = Modifier,
    subTone: PillTone? = null,
) {
    Column(
        modifier =
            modifier
                .voltCard(radius = VoltShapes.TileRadius)
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 11.dp),
    ) {
        VoltLabel(label, ellipsize = true)
        NumberUnit(value = value, unit = unit, size = 22f, modifier = Modifier.padding(top = 5.dp))
        Text(
            text = sub,
            style = VoltType.caption.copy(fontSize = 11.5.sp),
            color = subTone?.let { pillColor(it) } ?: VoltColors.textSecondary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

/** "368 V · 42 A" from whichever of the pack's volts and amps are known; a dash when neither is. */
internal fun packElectrics(
    volts: Double?,
    amps: Double?,
): String =
    listOfNotNull(volts?.let { "${it.roundToInt()} V" }, amps?.let { "${it.roundToInt()} A" })
        .joinToString(" · ")
        .ifEmpty { DASH }

/**
 * The three state-dependent tiles under the range card (mockups `tilesA`). At large text sizes a
 * third of the width cut their labels to "THIS D…", so they stack one per row instead.
 */
@Composable
internal fun FocusTiles(state: DriveUiState) {
    val stacked = LocalDensity.current.fontScale > STACK_TILES_FONT_SCALE
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        maxItemsInEachRow = if (stacked) 1 else TILES_PER_ROW,
    ) {
        val tile = Modifier.weight(1f)
        val units = state.units
        val h24 = LocalVoltPrefs.current.clock24h
        when (state.phase) {
            DrivePhase.DRIVE -> {
                StatTile(
                    "This drive",
                    units.distanceOneDecimal(state.tripMiles),
                    " ${units.distanceUnit}",
                    state.tripDuration,
                    tile,
                )
                val mpg = state.cycleMpg
                if (state.mode == DriveMode.GAS && mpg != null) {
                    val sub = state.cycleEvPercent?.let { "$it% electric" } ?: "this cycle"
                    // A third-width tile has no room for "L/100 km" beside the figure: it moves below.
                    if (units.metric) {
                        StatTile("Efficiency", units.economyValue(mpg) ?: DASH, "", units.economyUnit, tile)
                    } else {
                        StatTile("Efficiency", units.economyValue(mpg) ?: DASH, " ${units.economyUnit}", sub, tile)
                    }
                } else {
                    val value = units.efficiencyValue(state.shownTripMiPerKwh) ?: DASH
                    // A bare dash for the first mile read as broken; say when the figure arrives.
                    val waiting = value == DASH && state.tripMiles < MIN_EFFICIENCY_MILES
                    val until = "after ${units.distanceText(MIN_EFFICIENCY_MILES).replace(".0 ", " ")}"
                    if (units.metric) {
                        StatTile("Efficiency", value, "", if (waiting) until else "${units.efficiencyUnit} avg", tile)
                    } else {
                        StatTile(
                            "Efficiency",
                            value,
                            " ${units.efficiencyUnit}",
                            if (waiting) until else "trip avg",
                            tile,
                        )
                    }
                }
                StatTile(
                    "Battery",
                    state.packTempF?.let { units.temp(it.toDouble()).toString() } ?: DASH,
                    units.tempUnit,
                    packElectrics(state.packVolts, state.packAmps),
                    tile,
                )
            }
            DrivePhase.PARKED -> {
                val last = state.lastDrive
                StatTile(
                    "Last drive",
                    last?.let { units.distanceOneDecimal(it.miles) } ?: DASH,
                    " ${units.distanceUnit}",
                    last?.let { d ->
                        listOfNotNull(
                            d.miPerKwh?.takeIf { d.miles >= MIN_EFFICIENCY_MILES }?.let(units::efficiencyText),
                            clockLabel(d.endedAtMs, short = true, h24 = h24),
                        ).joinToString(" · ")
                    } ?: "none yet",
                    tile,
                )
                val volts = state.aux12Volts ?: state.auxVolts?.takeIf { state.connected && it > 0 }
                val aux = aux12Status(volts, state.phase)
                StatTile("12V battery", volts?.let(::oneDecimal) ?: DASH, " V", aux.text, tile, aux.tone)
                val tires = state.tires
                val status = tireStatus(tires, state.tirePlacardPsi)
                StatTile(
                    "Tires",
                    tires?.let { units.pressure(it.all.average()).toString() } ?: DASH,
                    " ${units.pressureUnit}",
                    status.text,
                    tile,
                    status.tone.takeIf { tires != null },
                )
            }
            DrivePhase.CHARGING -> {
                StatTile(
                    "Added",
                    oneDecimal(state.chargeAddedKwh),
                    " kWh",
                    listOfNotNull(
                        costText(state.chargeAddedKwh, state.electricityRate),
                        state.chargeStartedAtMs?.let { "since ${clockLabel(it, short = true, h24 = h24)}" }
                            ?: "this charge",
                    ).joinToString(" · "),
                    tile,
                )
                StatTile(
                    "Battery",
                    state.packTempF?.let { units.temp(it.toDouble()).toString() } ?: DASH,
                    units.tempUnit,
                    // The full "Cells balanced (14 mV)" didn't fit a third-width tile.
                    state.cellSpreadMv?.let { "${it.roundToInt()} mV spread" } ?: DASH,
                    tile,
                    subTone = state.cellSpreadMv?.takeIf { it >= CELL_WATCH_MV }?.let { PillTone.WARN },
                )
                StatTile(
                    "Charger",
                    levelName(state.chargeLevel) ?: DASH,
                    "",
                    "${oneDecimal(state.chargeKw)} kW onboard",
                    tile,
                )
            }
        }
    }
}

/**
 * Shown for a few seconds when the engine starts (mockups `modeToast`). The explanation only
 * claims a depleted battery when the pack really is at reserve — the Volt also starts the
 * engine for cold weather, Hold mode, or hard acceleration.
 */
@Composable
internal fun EngineOnToast(
    atReserve: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = VoltShapes.tile
    val bg = if (VoltColors.isDark) VoltColors.surface3 else VoltColors.surface
    val shadow = VoltColors.cardShadow
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .shadow(12.dp, shape, ambientColor = shadow, spotColor = shadow)
                .clip(shape)
                .background(bg)
                .border(1.dp, VoltColors.gas.copy(alpha = 0.35f), shape)
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .announceChanges(LocalVoltPrefs.current),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconSquare(VoltIcons.Fuel, tone = PillTone.GAS)
        Column {
            Text(
                text = "Engine on · range extender",
                style = VoltType.bodyStrong.copy(fontSize = 14.sp),
                color = VoltColors.textPrimary,
            )
            Text(
                text =
                    if (atReserve) {
                        "Battery reached reserve. Still driving on electric motors."
                    } else {
                        "The car started the engine for extra power or heat."
                    },
                style = VoltType.caption,
                color = VoltColors.textSecondary,
            )
        }
    }
}

private const val DIM_ALPHA = 0.5f

/** The smallest a [NumberUnit] figure shrinks to fit, as a share of its size. */
private const val MIN_FIT = 0.7f

/** Above this text scale the Drive tiles stack one per row (Settings → Text size "Large" is 1.25×). */
private const val STACK_TILES_FONT_SCALE = 1.2f
private const val TILES_PER_ROW = 3
