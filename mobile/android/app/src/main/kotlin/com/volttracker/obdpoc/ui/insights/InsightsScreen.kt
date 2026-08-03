package com.volttracker.obdpoc.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.components.MiniBars
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

/** The Insights tab: lifetime efficiency, driving patterns, battery health, vehicle. */
@Composable
fun InsightsScreen(
    state: InsightsUiState,
    modifier: Modifier = Modifier,
    onSelectTab: (VoltTab) -> Unit = {},
) {
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
            InsightsHeader(state)
            Spacer(Modifier.height(30.dp))
            EfficiencyHero(state)
            Spacer(Modifier.height(26.dp))
            LifetimePanel(state)
            Spacer(Modifier.height(14.dp))
            EfficiencyBySpeed(state)
            Spacer(Modifier.height(14.dp))
            MonthlyDistance(state)
            Spacer(Modifier.height(14.dp))
            BatteryHealth(state)
            Spacer(Modifier.height(14.dp))
            VehiclePanel(state)
        }
        VoltBottomNav(
            selected = VoltTab.INSIGHTS,
            onSelect = onSelectTab,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun InsightsHeader(state: InsightsUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Insights", style = VoltType.screenTitle, color = VoltColors.textPrimary)
        VoltStatusPill(
            text = state.statusLabel,
            dotColor = if (state.connected) VoltColors.energy else VoltColors.textTertiary,
        )
    }
}

@Composable
private fun EfficiencyHero(state: InsightsUiState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.lifetimeMiPerKwh?.let { String.format(Locale.US, "%.1f", it) } ?: "--",
            style = VoltType.display,
            color = VoltColors.textPrimary,
        )
        Text(
            text = "MI / KWH LIFETIME",
            style = VoltType.heroUnit,
            color = VoltColors.textTertiary,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            Text(
                text = "City  ${state.cityMiPerKwh?.let { String.format(Locale.US, "%.1f", it) } ?: "--"}",
                style = VoltType.caption,
                color = VoltColors.textSecondary,
            )
            Text(
                text = "Highway  ${state.highwayMiPerKwh?.let { String.format(Locale.US, "%.1f", it) } ?: "--"}",
                style = VoltType.caption,
                color = VoltColors.textSecondary,
            )
        }
    }
}

@Composable
private fun LifetimePanel(state: InsightsUiState) {
    VoltPanel {
        VoltLabel("Lifetime · ${state.driveCount} drives")
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            VoltStat(label = "Distance", value = String.format(Locale.US, "%.0f", state.lifetimeMiles), unit = "mi")
            VoltStat(label = "Drive time", value = state.driveTimeLabel)
            VoltStat(label = "Top speed", value = "${state.topSpeedMph}", unit = "mph", alignEnd = true)
        }
    }
}

@Composable
private fun EfficiencyBySpeed(state: InsightsUiState) {
    VoltPanel {
        VoltLabel("Efficiency vs speed")
        if (state.efficiencyPeakLabel != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = state.efficiencyPeakLabel,
                style = VoltType.body,
                color = VoltColors.textPrimary,
            )
        }
        Spacer(Modifier.height(16.dp))
        MiniBars(
            values = state.efficiencyBySpeed.map { it.value.toFloat() },
            labels = state.efficiencyBySpeed.map { it.label },
            highlightIndex = state.efficiencyPeakIndex,
            barHeight = 80.dp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "speed (mph)",
            style = VoltType.caption,
            color = VoltColors.textTertiary,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun MonthlyDistance(state: InsightsUiState) {
    VoltPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            VoltLabel("Driving · monthly")
            Text(
                text = String.format(Locale.US, "%.0f mi avg", state.avgMilesPerMonth),
                style = VoltType.valueSmall,
                color = VoltColors.textSecondary,
            )
        }
        Spacer(Modifier.height(16.dp))
        MiniBars(
            values = state.monthlyMiles.map { it.value.toFloat() },
            labels = state.monthlyMiles.map { it.label },
        )
    }
}

@Composable
private fun BatteryHealth(state: InsightsUiState) {
    VoltPanel {
        VoltLabel("HV battery")
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            VoltStat(
                label = "Health",
                value = state.batteryHealthPercent?.let { "$it%" } ?: "--",
                valueColor = VoltColors.energy,
            )
            VoltStat(label = "Voltage", value = String.format(Locale.US, "%.0f", state.packVolts), unit = "V")
            VoltStat(label = "Temp", value = "${state.packTempF}°F", alignEnd = true)
        }
    }
}

@Composable
private fun VehiclePanel(state: InsightsUiState) {
    VoltPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                VoltLabel("Vehicle")
                Spacer(Modifier.height(5.dp))
                Text(text = state.vehicleLabel, style = VoltType.valueSmall, color = VoltColors.textPrimary)
            }
            Column(horizontalAlignment = Alignment.End) {
                VoltLabel("Odometer")
                Spacer(Modifier.height(5.dp))
                Text(
                    text = String.format(Locale.US, "%,d mi", state.odometerMiles),
                    style = VoltType.valueSmall,
                    color = VoltColors.textPrimary,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = String.format(Locale.US, "%.0f mi logged", state.loggedMiles),
                style = VoltType.caption,
                color = VoltColors.textSecondary,
            )
            Text(
                text = "Maintenance: ${state.maintenanceLabel}",
                style = VoltType.caption,
                color = VoltColors.textSecondary,
            )
        }
    }
}

@Preview(widthDp = 412, heightDp = 1400)
@Composable
private fun InsightsScreenPreview() {
    VoltTheme { InsightsScreen(InsightsUiState.demo) }
}
