package com.volttracker.obdpoc.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.components.ButtonStyle
import com.volttracker.obdpoc.ui.components.VoltButton
import com.volttracker.obdpoc.ui.components.VoltIcons
import com.volttracker.obdpoc.ui.components.VoltListDivider
import com.volttracker.obdpoc.ui.components.VoltPanel
import com.volttracker.obdpoc.ui.components.VoltSegmented
import com.volttracker.obdpoc.ui.theme.AppearanceMode
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltType

// Detail pages behind the Settings index. Each is the former single-page group, moved intact.

/** One settings row: label (+ optional subtitle) with a trailing control. */
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
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
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
                .background(if (on) VoltColors.accentDim else VoltColors.surfaceElevated)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (on) VoltColors.accent else VoltColors.textTertiary),
        )
        Text(
            text = if (on) "On" else "Off",
            style = VoltType.caption,
            color = if (on) VoltColors.textPrimary else VoltColors.textSecondary,
        )
    }
}

@Composable
private fun Value(value: String) {
    Text(text = value, style = VoltType.body, color = VoltColors.textSecondary)
}

@Composable
private fun Note(text: String) {
    Text(text = text, style = VoltType.caption, color = VoltColors.textSecondary)
}

@Composable
internal fun ConnectionPage(state: SettingsUiState) {
    VoltPanel {
        SettingRow(label = "Adapter", subtitle = "Bluetooth OBD-II") { Value(state.adapterLabel) }
        VoltListDivider()
        SettingRow(label = "Auto-connect", subtitle = "When the last adapter is seen") {
            TogglePill(state.autoConnect)
        }
        VoltListDivider()
        SettingRow(label = "Wait for adapter", subtitle = "Keep checking in the background") {
            Value(state.backgroundWaitLabel)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VoltButton(text = "Test connection", onClick = {})
            VoltButton(text = "Send diagnostics", onClick = {})
        }
    }
}

@Composable
internal fun CostsPage(state: SettingsUiState) {
    VoltPanel {
        SettingRow(label = "Home electricity rate", subtitle = "Charging cost + gas savings") {
            Value(state.homeRateLabel)
        }
        VoltListDivider()
        SettingRow(label = "Public charging rate") { Value(state.publicRateLabel) }
        VoltListDivider()
        SettingRow(label = "Gas price") { Value(state.gasPriceLabel) }
        VoltListDivider()
        SettingRow(label = "Gas vehicle MPG", subtitle = "For savings estimates") { Value(state.gasMpgLabel) }
        VoltListDivider()
        SettingRow(label = "Charge target", subtitle = "Notify at this state of charge") {
            Value(state.chargeTargetLabel)
        }
    }
}

@Composable
internal fun UnitsPage(state: SettingsUiState) {
    VoltPanel {
        SettingRow(label = "Units", subtitle = "Distance and temperature") { Value(state.unitsLabel) }
    }
}

@Composable
internal fun AppearancePage(
    state: SettingsUiState,
    onSetAppearance: (AppearanceMode) -> Unit,
) {
    VoltPanel {
        SettingRow(label = "Theme", subtitle = "System follows your phone's dark theme") {}
        VoltSegmented(
            options = AppearanceMode.entries.map { it.label },
            selectedIndex = state.appearance.ordinal,
            onSelect = { onSetAppearance(AppearanceMode.entries[it]) },
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )
        VoltListDivider()
        SettingRow(label = "Keep screen awake", subtitle = "While Drive or Trips is live") {
            TogglePill(state.keepScreenAwake)
        }
        VoltListDivider()
        SettingRow(label = "Quiet live data", subtitle = "Calmer TalkBack announcements") {
            TogglePill(state.quietLiveData)
        }
        VoltListDivider()
        SettingRow(label = "Text size") { Value(state.textSizeLabel) }
        VoltListDivider()
        SettingRow(label = "High contrast") { TogglePill(state.highContrast) }
        VoltListDivider()
        SettingRow(label = "Drive tiles", subtitle = "Choose the live signals") { Value(state.driveTilesLabel) }
    }
}

@Composable
internal fun AlertsPage(state: SettingsUiState) {
    VoltPanel {
        SettingRow(label = "Charging complete") { TogglePill(state.notifyChargingComplete) }
        VoltListDivider()
        SettingRow(label = "New car code found") { TogglePill(state.notifyNewCode) }
        VoltListDivider()
        SettingRow(label = "Battery low", subtitle = state.batteryLowLabel) { TogglePill(state.notifyBatteryLow) }
        VoltListDivider()
        SettingRow(label = "Pack temperature high", subtitle = state.packTempHighLabel) {
            TogglePill(state.notifyPackTempHigh)
        }
        VoltListDivider()
        SettingRow(label = "Maintenance overdue") { TogglePill(state.notifyMaintenance) }
        VoltListDivider()
        SettingRow(label = "End-of-drive recap") { TogglePill(state.endOfDriveRecap) }
        VoltListDivider()
        SettingRow(label = "Auto-scan for codes", subtitle = "One background scan per connect") {
            TogglePill(state.autoScanCodes)
        }
    }
}

@Composable
internal fun DataPage(state: SettingsUiState) {
    VoltPanel {
        Note(state.lastBackupLabel)
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

@Composable
internal fun DemoPage(
    state: SettingsUiState,
    onStartDemo: () -> Unit,
    onStopDemo: () -> Unit,
) {
    VoltPanel {
        Note(
            "Streams realistic sample data through every screen — no car or adapter needed. " +
                "Demo data never mixes with your real history.",
        )
        Spacer(Modifier.height(14.dp))
        if (state.demoActive) {
            VoltButton(text = "Stop demo", style = ButtonStyle.GHOST, onClick = onStopDemo)
        } else {
            VoltButton(text = "Start demo", accent = true, icon = VoltIcons.Play, onClick = onStartDemo)
        }
    }
}

/** Power-user tools: the classic dashboard still hosts logs, raw PIDs, and the signal workspace. */
@Composable
internal fun AdvancedPage(onOpenClassicDashboard: () -> Unit) {
    VoltPanel {
        Note(
            "Troubleshooting logs, raw PID reads, the signal workspace, and the full settings forest " +
                "live in the classic dashboard.",
        )
        Spacer(Modifier.height(14.dp))
        VoltButton(text = "Open classic dashboard", onClick = onOpenClassicDashboard)
    }
}

/** App updates via GitHub Releases: version, check action, one-tap install. */
@Composable
internal fun UpdatesPage(
    state: SettingsUiState,
    onCheckForUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
) {
    VoltPanel {
        Note(state.versionLabel.ifBlank { "Installed version unknown" })
        if (state.updateStatusLabel != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = state.updateStatusLabel,
                style = VoltType.caption,
                color = if (state.updateAvailableTag != null) VoltColors.accent else VoltColors.textTertiary,
            )
        }
        Spacer(Modifier.height(14.dp))
        when {
            state.updateDownloadPercent != null ->
                Text(
                    text = "Downloading… ${state.updateDownloadPercent}%",
                    style = VoltType.body,
                    color = VoltColors.textPrimary,
                )
            state.updateAvailableTag != null ->
                VoltButton(text = "Update to ${state.updateAvailableTag}", accent = true, onClick = onInstallUpdate)
            else -> VoltButton(text = "Check for updates", onClick = onCheckForUpdate)
        }
    }
}
