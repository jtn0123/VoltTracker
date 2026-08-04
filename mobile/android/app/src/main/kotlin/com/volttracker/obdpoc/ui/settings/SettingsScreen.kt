package com.volttracker.obdpoc.ui.settings

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.components.VoltBottomNav
import com.volttracker.obdpoc.ui.components.VoltButton
import com.volttracker.obdpoc.ui.components.VoltLabel
import com.volttracker.obdpoc.ui.components.VoltPanel
import com.volttracker.obdpoc.ui.components.VoltStatusPill
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltTheme
import com.volttracker.obdpoc.ui.theme.VoltType

/** The Settings tab: grouped list rows instead of the legacy checkbox forest. */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    modifier: Modifier = Modifier,
    onSelectTab: (VoltTab) -> Unit = {},
    onOpenClassicDashboard: () -> Unit = {},
    onCheckForUpdate: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
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
            SettingsHeader(state)
            Spacer(Modifier.height(26.dp))
            ConnectionGroup(state)
            Spacer(Modifier.height(14.dp))
            AlertsGroup(state)
            Spacer(Modifier.height(14.dp))
            UnitsGroup(state)
            Spacer(Modifier.height(14.dp))
            DisplayGroup(state)
            Spacer(Modifier.height(14.dp))
            DataGroup(state)
            Spacer(Modifier.height(14.dp))
            UpdatesGroup(state, onCheckForUpdate, onInstallUpdate)
            Spacer(Modifier.height(14.dp))
            ClassicDashboardGroup(onOpenClassicDashboard)
            Spacer(Modifier.height(22.dp))
            Text(
                text = state.versionLabel,
                style = VoltType.caption,
                color = VoltColors.textTertiary,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        VoltBottomNav(
            selected = VoltTab.SETTINGS,
            onSelect = onSelectTab,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun SettingsHeader(state: SettingsUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Settings", style = VoltType.screenTitle, color = VoltColors.textPrimary)
        VoltStatusPill(
            text = state.statusLabel,
            dotColor = if (state.connected) VoltColors.energy else VoltColors.textTertiary,
        )
    }
}

/** One settings list row: label (+ optional subtitle) with a trailing control. */
@Composable
private fun SettingRow(
    label: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.padding(end = 16.dp)) {
            Text(text = label, style = VoltType.body, color = VoltColors.textPrimary)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(text = subtitle, style = VoltType.caption, color = VoltColors.textTertiary)
            }
        }
        trailing()
    }
}

/** Compact on/off pill — reads as a switch without Material's large thumb. */
@Composable
private fun TogglePill(on: Boolean) {
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (on) VoltColors.energyDim else VoltColors.surfaceElevated)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (on) VoltColors.energy else VoltColors.textTertiary),
        )
        Text(
            text = if (on) "On" else "Off",
            style = VoltType.caption,
            color = if (on) VoltColors.textPrimary else VoltColors.textSecondary,
        )
    }
}

@Composable
private fun ValueChevron(value: String) {
    Text(
        text = "$value  ›",
        style = VoltType.body,
        color = VoltColors.textSecondary,
    )
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(color = VoltColors.hairline, thickness = 1.dp)
}

@Composable
private fun ConnectionGroup(state: SettingsUiState) {
    VoltPanel {
        VoltLabel("Connection")
        SettingRow(label = "Adapter", subtitle = "Bluetooth OBD-II") { ValueChevron(state.adapterLabel) }
        GroupDivider()
        SettingRow(label = "Auto-connect", subtitle = "When the last adapter is seen") {
            TogglePill(state.autoConnect)
        }
        GroupDivider()
        SettingRow(label = "Wait for adapter", subtitle = "Keep checking in the background") {
            ValueChevron(state.backgroundWaitLabel)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VoltButton(text = "Test connection", onClick = {})
            VoltButton(text = "Send diagnostics", onClick = {})
        }
    }
}

@Composable
private fun AlertsGroup(state: SettingsUiState) {
    VoltPanel {
        VoltLabel("Alerts")
        SettingRow(label = "Charging complete") { TogglePill(state.notifyChargingComplete) }
        GroupDivider()
        SettingRow(label = "New car code found") { TogglePill(state.notifyNewCode) }
        GroupDivider()
        SettingRow(label = "Battery low", subtitle = state.batteryLowLabel) {
            TogglePill(state.notifyBatteryLow)
        }
        GroupDivider()
        SettingRow(label = "Pack temperature high", subtitle = state.packTempHighLabel) {
            TogglePill(state.notifyPackTempHigh)
        }
        GroupDivider()
        SettingRow(label = "Maintenance overdue") { TogglePill(state.notifyMaintenance) }
        GroupDivider()
        SettingRow(label = "End-of-drive recap") { TogglePill(state.endOfDriveRecap) }
        GroupDivider()
        SettingRow(label = "Auto-scan for codes", subtitle = "One background scan per connect") {
            TogglePill(state.autoScanCodes)
        }
    }
}

@Composable
private fun UnitsGroup(state: SettingsUiState) {
    VoltPanel {
        VoltLabel("Units & rates")
        SettingRow(label = "Units") { ValueChevron(state.unitsLabel) }
        GroupDivider()
        SettingRow(label = "Home electricity rate", subtitle = "Charging cost + gas savings") {
            ValueChevron(state.homeRateLabel)
        }
        GroupDivider()
        SettingRow(label = "Public charging rate") { ValueChevron(state.publicRateLabel) }
        GroupDivider()
        SettingRow(label = "Gas vehicle MPG", subtitle = "For savings estimates") {
            ValueChevron(state.gasMpgLabel)
        }
        GroupDivider()
        SettingRow(label = "Gas price") { ValueChevron(state.gasPriceLabel) }
        GroupDivider()
        SettingRow(label = "Charge target", subtitle = "Notify at this state of charge") {
            ValueChevron(state.chargeTargetLabel)
        }
    }
}

@Composable
private fun DisplayGroup(state: SettingsUiState) {
    VoltPanel {
        VoltLabel("Display")
        SettingRow(label = "Keep screen awake", subtitle = "While Drive or Map is live") {
            TogglePill(state.keepScreenAwake)
        }
        GroupDivider()
        SettingRow(label = "Quiet live data", subtitle = "Calmer TalkBack announcements") {
            TogglePill(state.quietLiveData)
        }
        GroupDivider()
        SettingRow(label = "Text size") { ValueChevron(state.textSizeLabel) }
        GroupDivider()
        SettingRow(label = "High contrast") { TogglePill(state.highContrast) }
        GroupDivider()
        SettingRow(label = "Drive tiles", subtitle = "Choose the live signals") {
            ValueChevron(state.driveTilesLabel)
        }
    }
}

@Composable
private fun DataGroup(state: SettingsUiState) {
    VoltPanel {
        VoltLabel("Data")
        Spacer(Modifier.height(6.dp))
        Text(
            text = state.lastBackupLabel,
            style = VoltType.caption,
            color = VoltColors.textSecondary,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VoltButton(text = "Back up", accent = true, onClick = {})
            VoltButton(text = "Restore", onClick = {})
            VoltButton(text = "Export", onClick = {})
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Backups are encrypted. All data stays on this phone.",
            style = VoltType.caption,
            color = VoltColors.textTertiary,
        )
    }
}

/** App updates via GitHub Releases: version, check action, one-tap install. */
@Composable
private fun UpdatesGroup(
    state: SettingsUiState,
    onCheckForUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
) {
    VoltPanel {
        VoltLabel("App updates")
        Spacer(Modifier.height(6.dp))
        Text(
            text = state.versionLabel.ifBlank { "Installed version unknown" },
            style = VoltType.caption,
            color = VoltColors.textSecondary,
        )
        if (state.updateStatusLabel != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = state.updateStatusLabel,
                style = VoltType.caption,
                color = if (state.updateAvailableTag != null) VoltColors.energy else VoltColors.textTertiary,
            )
        }
        Spacer(Modifier.height(14.dp))
        val downloading = state.updateDownloadPercent != null
        when {
            downloading ->
                Text(
                    text = "Downloading… ${state.updateDownloadPercent}%",
                    style = VoltType.body,
                    color = VoltColors.textPrimary,
                )
            state.updateAvailableTag != null ->
                VoltButton(
                    text = "Update to ${state.updateAvailableTag}",
                    accent = true,
                    onClick = onInstallUpdate,
                )
            else -> VoltButton(text = "Check for updates", onClick = onCheckForUpdate)
        }
    }
}

@Composable
private fun ClassicDashboardGroup(onOpenClassicDashboard: () -> Unit) {
    VoltPanel {
        VoltLabel("Classic dashboard")
        Spacer(Modifier.height(6.dp))
        Text(
            text =
                "Trips, charge history, insights, and full settings still live in the " +
                    "classic dashboard while the new app fills in.",
            style = VoltType.caption,
            color = VoltColors.textSecondary,
        )
        Spacer(Modifier.height(14.dp))
        VoltButton(text = "Open classic dashboard", onClick = onOpenClassicDashboard)
    }
}

@Preview(widthDp = 412, heightDp = 1700)
@Composable
private fun SettingsScreenPreview() {
    VoltTheme { SettingsScreen(SettingsUiState.demo) }
}
