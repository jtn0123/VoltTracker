package com.volttracker.obdpoc.ui.charge

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.components.BatteryBar
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

/** The Charge tab: charging hero, recent charges, monthly energy. */
@Composable
fun ChargeScreen(
    state: ChargeUiState,
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
            ChargeHeader(state)
            Spacer(Modifier.height(30.dp))
            ChargeHero(state)
            if (state.recentCharges.isNotEmpty()) {
                Spacer(Modifier.height(26.dp))
                RecentCharges(state)
            }
            if (state.monthly.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                MonthPanel(state)
            }
            if (state.cellBalanceLabel != null) {
                Spacer(Modifier.height(14.dp))
                CellBalance(state.cellBalanceLabel)
            }
        }
        VoltBottomNav(
            selected = VoltTab.CHARGE,
            onSelect = onSelectTab,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun ChargeHeader(state: ChargeUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Charge", style = VoltType.screenTitle, color = VoltColors.textPrimary)
        VoltStatusPill(
            text = state.statusLabel,
            dotColor = if (state.connected) VoltColors.energy else VoltColors.textTertiary,
        )
    }
}

@Composable
private fun ChargeHero(state: ChargeUiState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "${state.socPercent.toInt()}%",
            style = VoltType.display,
            color = VoltColors.textPrimary,
        )
        if (state.charging) {
            Text(
                text =
                    String.format(
                        Locale.US,
                        "⌁ %.1f kW · %s",
                        state.chargeKw,
                        state.chargeLevel,
                    ),
                style = VoltType.valueSmall,
                color = VoltColors.energy,
            )
        } else {
            Text(
                text = "Not charging",
                style = VoltType.valueSmall,
                color = VoltColors.textSecondary,
            )
        }
        Spacer(Modifier.height(20.dp))
        BatteryBar(
            socFraction = (state.socPercent / 100.0).toFloat(),
            height = 14.dp,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${state.evRangeMiles.toInt()} mi range",
                style = VoltType.caption,
                color = VoltColors.textSecondary,
            )
            if (state.timeToFullLabel != null) {
                Text(
                    text = state.timeToFullLabel,
                    style = VoltType.caption,
                    color = VoltColors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun RecentCharges(state: ChargeUiState) {
    VoltPanel {
        VoltLabel("Recent charges")
        Spacer(Modifier.height(4.dp))
        state.recentCharges.forEachIndexed { i, entry ->
            if (i > 0) {
                HorizontalDivider(color = VoltColors.hairline, thickness = 1.dp)
            }
            ChargeRow(entry)
        }
    }
}

@Composable
private fun ChargeRow(entry: ChargeEntry) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.whenLabel,
                    style = VoltType.body,
                    color = if (entry.active) VoltColors.energy else VoltColors.textPrimary,
                )
                Text(
                    text = "  ·  ${entry.level}",
                    style = VoltType.caption,
                    color = VoltColors.textSecondary,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text =
                    String.format(
                        Locale.US,
                        "%d → %d%%  ·  %.1f kW  ·  %s",
                        entry.fromPercent,
                        entry.toPercent,
                        entry.powerKw,
                        entry.durationLabel,
                    ),
                style = VoltType.caption,
                color = VoltColors.textTertiary,
            )
        }
        Text(
            text = String.format(Locale.US, "%.1f kWh", entry.energyKwh),
            style = VoltType.valueSmall,
            color = if (entry.active) VoltColors.energy else VoltColors.textPrimary,
        )
    }
}

@Composable
private fun MonthPanel(state: ChargeUiState) {
    VoltPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            VoltLabel("Charging · monthly")
            Text(
                text = String.format(Locale.US, "%.1f kWh", state.monthKwh),
                style = VoltType.valueSmall,
                color = VoltColors.energy,
            )
        }
        Spacer(Modifier.height(16.dp))
        MiniBars(
            values = state.monthly.map { it.kwh.toFloat() },
            labels = state.monthly.map { it.label },
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            VoltStat(label = "Charges", value = "${state.monthChargeCount}")
            VoltStat(
                label = "Avg / charge",
                value = String.format(Locale.US, "%.1f", state.avgKwhPerCharge),
                unit = "kWh",
            )
            VoltStat(
                label = "This month",
                value = String.format(Locale.US, "%.1f", state.monthKwh),
                unit = "kWh",
                alignEnd = true,
            )
        }
    }
}

@Composable
private fun CellBalance(label: String) {
    VoltPanel {
        VoltLabel("Battery cell balance")
        Spacer(Modifier.height(6.dp))
        Text(text = label, style = VoltType.body, color = VoltColors.textPrimary)
    }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun ChargeScreenPreview() {
    VoltTheme { ChargeScreen(ChargeUiState.demo) }
}
