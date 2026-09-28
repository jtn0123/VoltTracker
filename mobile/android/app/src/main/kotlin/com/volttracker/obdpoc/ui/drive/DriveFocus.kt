package com.volttracker.obdpoc.ui.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volttracker.obdpoc.ui.components.IconSquare
import com.volttracker.obdpoc.ui.components.PillTone
import com.volttracker.obdpoc.ui.components.VoltIcons
import com.volttracker.obdpoc.ui.components.VoltLabel
import com.volttracker.obdpoc.ui.components.VoltPanel
import com.volttracker.obdpoc.ui.components.pillColor
import com.volttracker.obdpoc.ui.components.voltCard
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltType

/** Number + small muted unit, the mockups' `<b>12.4</b><small> mi</small>` pairing. */
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
                if (unit.isNotEmpty()) {
                    withStyle(
                        SpanStyle(fontSize = unitSize.sp, color = muted, fontWeight = FontWeight.Medium),
                    ) { append(unit) }
                }
            },
        style = VoltType.value.copy(fontSize = size.sp),
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
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
                .clip(RoundedCornerShape(50))
                .background(VoltColors.track),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(height.dp)
                    .clip(RoundedCornerShape(50))
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
            val total = state.totalRangeMiles
            NumberUnit(
                value = total?.let { wholeLabel(it) } ?: "--",
                unit = " mi total",
                size = 17f,
            )
        }
        val evSub =
            when {
                !state.connected -> "--"
                gasMode && state.atReserve -> "Battery at reserve · holding ${state.socPercent.toInt()}%"
                else -> "${soc.toInt()}% battery"
            }
        RangeRow(
            icon = VoltIcons.Bolt,
            tone = PillTone.EV,
            title = "Electric",
            miles = state.evRangeMiles,
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
            miles = state.gasRangeMiles,
            fraction = ((fuel ?: 0.0) / 100).toFloat(),
            sub =
                listOfNotNull(
                    fuel?.let { "${it.toInt()}% tank" } ?: "Tank level not reported",
                    "engine running".takeIf { gasMode },
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
    miles: Double?,
    fraction: Float,
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
                NumberUnit(value = wholeLabel(miles), unit = " mi", size = 20f)
            }
            Meter(fraction = fraction, color = pillColor(tone), modifier = Modifier.padding(top = 6.dp, bottom = 5.dp))
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
                .voltCard(radius = 16.dp)
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 11.dp),
    ) {
        VoltLabel(label)
        NumberUnit(value = value, unit = unit, size = 22f, modifier = Modifier.padding(top = 5.dp))
        Text(
            text = sub,
            style = VoltType.caption.copy(fontSize = 11.5.sp),
            color = subTone?.let { pillColor(it) } ?: VoltColors.textSecondary,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

/** The three state-dependent tiles under the range card (mockups `tilesA`). */
@Composable
internal fun FocusTiles(state: DriveUiState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val tile = Modifier.weight(1f)
        when (state.phase) {
            DrivePhase.DRIVE -> {
                StatTile("This drive", oneDecimal(state.tripMiles), " mi", state.tripDuration, tile)
                val mpg = state.cycleMpg
                if (state.mode == DriveMode.GAS && mpg != null) {
                    StatTile("Efficiency", oneDecimal(mpg), " mpg", "${state.cycleEvPercent ?: 0}% electric", tile)
                } else {
                    StatTile("Efficiency", state.tripMiPerKwh?.let(::oneDecimal) ?: "--", " mi/kWh", "trip avg", tile)
                }
                StatTile(
                    "Battery",
                    "${state.packTempF}",
                    "°F",
                    "${state.packVolts.toInt()} V · ${state.packAmps.toInt()} A",
                    tile,
                )
            }
            DrivePhase.PARKED -> {
                val last = state.lastDrive
                StatTile(
                    "Last drive",
                    last?.let { oneDecimal(it.miles) } ?: "--",
                    " mi",
                    last?.let { d ->
                        listOfNotNull(
                            d.miPerKwh?.let { "${oneDecimal(it)} mi/kWh" },
                            clockLabel(d.endedAtMs, short = true),
                        ).joinToString(" · ")
                    } ?: "none this session",
                    tile,
                )
                val volts = state.aux12Volts ?: state.auxVolts.takeIf { state.connected && it > 0 }
                val aux = aux12Status(volts, state.phase)
                StatTile("12V battery", volts?.let(::oneDecimal) ?: "--", " V", aux.text, tile, aux.tone)
                val tires = state.tires
                val status = tireStatus(tires)
                StatTile(
                    "Tires",
                    tires?.let { wholeLabel(it.all.average()) } ?: "--",
                    " psi",
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
                        costLabel(state.chargeAddedKwh, state.electricityRate),
                        state.chargeStartedAtMs?.let { "since ${clockLabel(it, short = true)}" } ?: "this charge",
                    ).joinToString(" · "),
                    tile,
                )
                StatTile(
                    "Battery",
                    "${state.packTempF}",
                    "°F",
                    state.cellSpreadMv?.let { "Cell Δ ${it.toInt()} mV" } ?: "--",
                    tile,
                )
                StatTile(
                    "Charger",
                    state.chargeLevel ?: "--",
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
    val shape = RoundedCornerShape(16.dp)
    val bg = if (VoltColors.isDark) VoltColors.surface3 else VoltColors.surface
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .shadow(12.dp, shape, ambientColor = TOAST_SHADOW, spotColor = TOAST_SHADOW)
                .clip(shape)
                .background(bg)
                .border(1.dp, VoltColors.gas.copy(alpha = 0.35f), shape)
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
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

private val TOAST_SHADOW = Color(0x59000000)
private const val DIM_ALPHA = 0.5f
