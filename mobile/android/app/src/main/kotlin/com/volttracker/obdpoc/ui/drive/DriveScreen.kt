package com.volttracker.obdpoc.ui.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.components.BatteryBar
import com.volttracker.obdpoc.ui.components.PowerBar
import com.volttracker.obdpoc.ui.components.SignedBars
import com.volttracker.obdpoc.ui.components.Sparkline
import com.volttracker.obdpoc.ui.components.VoltBottomNav
import com.volttracker.obdpoc.ui.components.VoltLabel
import com.volttracker.obdpoc.ui.components.VoltPanel
import com.volttracker.obdpoc.ui.components.VoltStat
import com.volttracker.obdpoc.ui.components.VoltStatusPill
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltTheme
import com.volttracker.obdpoc.ui.theme.VoltType
import java.util.Locale

/** The Drive tab: hero speed, power flow, battery, and the current trip. */
@Composable
fun DriveScreen(
    state: DriveUiState,
    modifier: Modifier = Modifier,
    onSelectTab: (VoltTab) -> Unit = {},
) {
    var detailed by remember(state.detailed) { mutableStateOf(state.detailed) }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(VoltColors.bg),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 18.dp, bottom = 118.dp),
        ) {
            DriveHeader(state)
            Spacer(Modifier.height(30.dp))
            SpeedHero(state)
            Spacer(Modifier.height(14.dp))
            StatusChips(state)
            Spacer(Modifier.height(16.dp))
            DensityToggle(detailed = detailed, onChange = { detailed = it })
            Spacer(Modifier.height(22.dp))
            PowerSection(state)
            if (detailed) {
                Spacer(Modifier.height(14.dp))
                ChartsRow(state)
            }
            Spacer(Modifier.height(14.dp))
            BatterySection(state)
            if (detailed) {
                Spacer(Modifier.height(14.dp))
                MoreSignalsGrid(state)
                Spacer(Modifier.height(14.dp))
                VitalsRow(state)
            }
            Spacer(Modifier.height(14.dp))
            TripStrip(state)
        }
        VoltBottomNav(
            selected = VoltTab.DRIVE,
            onSelect = onSelectTab,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun DriveHeader(state: DriveUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Drive", style = VoltType.screenTitle, color = VoltColors.textPrimary)
        VoltStatusPill(
            text = state.statusLabel,
            dotColor = if (state.connected) VoltColors.energy else VoltColors.textTertiary,
        )
    }
}

@Composable
private fun SpeedHero(state: DriveUiState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "${state.speedMph}",
            style = VoltType.display,
            color = VoltColors.textPrimary,
        )
        Text(
            text = "MPH",
            style = VoltType.heroUnit,
            color = VoltColors.textTertiary,
        )
        Spacer(Modifier.height(18.dp))
        Sparkline(
            values = state.speedTrace,
            lineColor = if (state.mode == DriveMode.GAS) VoltColors.drive else VoltColors.energy,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(56.dp),
        )
    }
}

/** At-a-glance chips under the hero: propulsion mode, regen, range, GPS fix. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusChips(state: DriveUiState) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.mode == DriveMode.GAS) {
            VoltStatusPill(text = "Gas", dotColor = VoltColors.drive)
        } else {
            VoltStatusPill(text = "EV", dotColor = VoltColors.energy)
        }
        if (state.powerKw < -0.05) {
            VoltStatusPill(text = "⚡ Regen", dotColor = VoltColors.regen)
        }
        VoltStatusPill(text = "${state.evRangeMiles.toInt()} mi range", dotColor = VoltColors.energyDim)
        state.gpsAccuracyFt?.let {
            VoltStatusPill(text = "±$it ft", dotColor = VoltColors.textTertiary)
        }
    }
}

/** Two-segment density switch: Focus (essentials) vs Detailed (everything). */
@Composable
private fun DensityToggle(
    detailed: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Row(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(VoltColors.surface)
                    .padding(3.dp)
                    .selectableGroup(),
        ) {
            DensitySegment(text = "Focus", selected = !detailed) { onChange(false) }
            DensitySegment(text = "Detailed", selected = detailed) { onChange(true) }
        }
    }
}

@Composable
private fun DensitySegment(
    text: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Text(
        text = text,
        style = VoltType.caption,
        color = if (selected) VoltColors.textPrimary else VoltColors.textTertiary,
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (selected) VoltColors.surfaceElevated else Color.Transparent)
                .selectable(selected = selected, onClick = onSelect, role = Role.Tab)
                .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** Label + accent color for the current power flow, derived together so they can't drift. */
private data class PowerStatus(
    val label: String,
    val color: androidx.compose.ui.graphics.Color,
)

private fun powerStatus(state: DriveUiState): PowerStatus =
    when {
        state.powerKw < -0.05 -> PowerStatus("Regen", VoltColors.regen)
        state.powerKw > 0.05 ->
            PowerStatus(
                if (state.mode == DriveMode.GAS) "Drive · Gas" else "Drive",
                VoltColors.drive,
            )
        else -> PowerStatus("Idle", VoltColors.textTertiary)
    }

@Composable
private fun PowerSection(state: DriveUiState) {
    val status = powerStatus(state)
    val kwText = String.format(Locale.US, "%.1f", kotlin.math.abs(state.powerKw))
    VoltPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(text = kwText, style = VoltType.value, color = VoltColors.textPrimary)
                Spacer(Modifier.height(0.dp))
                Text(
                    text = " kW",
                    style = VoltType.caption,
                    color = VoltColors.textSecondary,
                )
            }
            VoltLabel(text = status.label, color = status.color)
        }
        Spacer(Modifier.height(14.dp))
        PowerBar(
            powerKw = state.powerKw,
            maxDriveKw = state.maxDriveKw,
            maxRegenKw = state.maxRegenKw,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "Regen", style = VoltType.caption, color = VoltColors.textTertiary)
            Text(text = "Drive", style = VoltType.caption, color = VoltColors.textTertiary)
        }
    }
}

/** Detailed-mode charts: signed pack power over the last minute + session SOC. */
@Composable
private fun ChartsRow(state: DriveUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        VoltPanel(modifier = Modifier.weight(1f)) {
            VoltLabel("Power · 60 s")
            Spacer(Modifier.height(12.dp))
            SignedBars(
                values = state.powerTrace,
                modifier = Modifier.height(64.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = "↓ regen", style = VoltType.caption, color = VoltColors.regen)
                Text(text = "↑ drive", style = VoltType.caption, color = VoltColors.drive)
            }
        }
        VoltPanel(modifier = Modifier.weight(1f)) {
            VoltLabel("SOC · session")
            Spacer(Modifier.height(12.dp))
            Sparkline(
                values = state.socTrace,
                modifier = Modifier.height(64.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = socSessionLabel(state.socTrace),
                style = VoltType.caption,
                color = VoltColors.textSecondary,
            )
        }
    }
}

private fun socSessionLabel(socTrace: List<Float>): String =
    if (socTrace.size < 2) {
        "--"
    } else {
        "${socTrace.first().toInt()}% → ${socTrace.last().toInt()}%"
    }

@Composable
private fun BatterySection(state: DriveUiState) {
    VoltPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "${state.socPercent.toInt()}%",
                    style = VoltType.value,
                    color = VoltColors.textPrimary,
                )
                Text(
                    text = "  ·  ${state.evRangeMiles.toInt()} mi",
                    style = VoltType.valueSmall,
                    color = VoltColors.energy,
                )
            }
            VoltLabel("HV Battery")
        }
        Spacer(Modifier.height(14.dp))
        BatteryBar(socFraction = (state.socPercent / 100.0).toFloat())
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            KeyValue("Voltage", String.format(Locale.US, "%.0f V", state.packVolts))
            KeyValue("Current", String.format(Locale.US, "%.1f A", state.packAmps))
            KeyValue("Power", String.format(Locale.US, "%.1f kW", state.powerKw))
            KeyValue("Temp", "${state.packTempF}°F")
        }
    }
}

@Composable
private fun KeyValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = VoltColors.textPrimary,
) {
    Column(modifier = modifier) {
        VoltLabel(label)
        Spacer(Modifier.height(5.dp))
        Text(text = value, style = VoltType.valueSmall, color = valueColor)
    }
}

/** Detailed-mode grid restoring the legacy "more signals" tiles. */
@Composable
private fun MoreSignalsGrid(state: DriveUiState) {
    VoltPanel {
        VoltLabel("More signals")
        Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            KeyValue("Motor A", "${state.motorATempF}°F", modifier = Modifier.weight(1f))
            KeyValue("Motor B", "${state.motorBTempF}°F", modifier = Modifier.weight(1f))
            KeyValue("Trans", "${state.transTempF}°F", modifier = Modifier.weight(1f))
            KeyValue("Torque", "${state.torqueNm} Nm", modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            KeyValue("Ambient", "${state.ambientF}°F", modifier = Modifier.weight(1f))
            KeyValue("Oil life", "${state.oilLifePct}%", modifier = Modifier.weight(1f))
            KeyValue("Gear", state.gear, modifier = Modifier.weight(1f))
            KeyValue("EV range", "${state.evRangeMiles.toInt()} mi", modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun VitalsRow(state: DriveUiState) {
    VoltPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            KeyValue("Aux 12V", String.format(Locale.US, "%.1f V", state.auxVolts))
            KeyValue("Coolant", "${state.coolantF}°F")
            if (state.mode == DriveMode.GAS) {
                KeyValue("RPM", "${state.rpm}")
            } else {
                KeyValue("GPS", state.gpsAccuracyFt?.let { "±$it ft" } ?: "--")
            }
        }
    }
}

@Composable
private fun TripStrip(state: DriveUiState) {
    VoltPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VoltStat(label = "This trip", value = String.format(Locale.US, "%.0f", state.tripMiles), unit = "mi")
            VoltStat(label = "Time", value = state.tripDuration)
            VoltStat(
                label = "Efficiency",
                value = state.tripMiPerKwh?.let { String.format(Locale.US, "%.1f", it) } ?: "--",
                unit = "mi/kWh",
                valueColor = VoltColors.energy,
                alignEnd = true,
            )
        }
    }
}

@Preview(widthDp = 412, heightDp = 1500)
@Composable
private fun DriveScreenPreview() {
    VoltTheme { DriveScreen(DriveUiState.demo) }
}

@Preview(widthDp = 412, heightDp = 1100)
@Composable
private fun DriveScreenFocusPreview() {
    VoltTheme { DriveScreen(DriveUiState.demo.copy(detailed = false)) }
}
