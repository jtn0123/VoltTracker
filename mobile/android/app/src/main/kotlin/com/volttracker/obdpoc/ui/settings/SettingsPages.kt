package com.volttracker.obdpoc.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.components.ButtonStyle
import com.volttracker.obdpoc.ui.components.VoltButton
import com.volttracker.obdpoc.ui.components.VoltIcons
import com.volttracker.obdpoc.ui.components.VoltListDivider
import com.volttracker.obdpoc.ui.components.VoltPanel
import com.volttracker.obdpoc.ui.components.VoltSegmented
import com.volttracker.obdpoc.ui.theme.AppearanceMode
import com.volttracker.obdpoc.ui.theme.DarkStyle
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltType

// Detail pages behind the Settings index. Every control reads the stored value from [SettingsUiState]
// and reports edits as a [SettingChange]; the host persists them.

@Composable
internal fun ConnectionPage(
    state: SettingsUiState,
    onChange: (SettingChange) -> Unit,
    onCommand: (SettingsCommand) -> Unit,
) {
    VoltPanel {
        SettingRow(label = "Adapter", subtitle = "Bluetooth OBD-II") { Value(state.adapterLabel) }
        VoltListDivider()
        ToggleRow(label = "Auto-connect", subtitle = "When the last adapter is seen", on = state.autoConnect) {
            onChange(SettingChange.AutoConnect(it))
        }
        VoltListDivider()
        ToggleRow(
            label = "Wait for adapter",
            subtitle =
                if (state.waitingForAdapter) {
                    "Checking every 30 s for ${state.adapterWaitLabel} · notifies when ready"
                } else {
                    "Keep checking in the background for ${state.adapterWaitLabel}"
                },
            on = state.waitingForAdapter,
        ) { onCommand(SettingsCommand.WaitForAdapter(it, state.adapterWaitMins)) }
        ThresholdChoice(
            choices = SettingsUiState.ADAPTER_WAIT_CHOICES,
            selected = state.adapterWaitMins,
            label = { "$it min" },
        ) { onCommand(SettingsCommand.WaitForAdapter(state.waitingForAdapter, it)) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VoltButton(text = "Test connection", onClick = { onCommand(SettingsCommand.TestConnection) })
            VoltButton(text = "Send diagnostics", onClick = { onCommand(SettingsCommand.SendDiagnostics) })
        }
    }
}

/** The number editors on the Costs page, keyed so the open one survives rotation. */
private enum class CostField(
    val field: NumberField,
) {
    HOME(NumberField("Home electricity rate", "$/kWh", 0.0, 2.0)),
    PUBLIC(NumberField("Public charging rate", "$/kWh", 0.0, 2.0)),
    GAS_PRICE(NumberField("Gas price", "$/gal", 0.0, 10.0)),
    GAS_MPG(NumberField("Gas vehicle MPG", "MPG", 5.0, 150.0)),
    CHARGE_TARGET(NumberField("Charge target", "%", 50.0, 100.0, clearable = false)),
}

@Composable
internal fun CostsPage(
    state: SettingsUiState,
    onChange: (SettingChange) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf<CostField?>(null) }
    val editor: @Composable (CostField) -> Unit = { field ->
        if (editing == field) {
            NumberEditor(field.field, costValue(state, field), onDismiss = { editing = null }) { value ->
                onChange(costChange(field, value))
                editing = null
            }
        }
    }
    VoltPanel {
        ValueRow("Home electricity rate", state.homeRateLabel, subtitle = "Charging cost + gas savings") {
            editing = CostField.HOME
        }
        editor(CostField.HOME)
        VoltListDivider()
        ValueRow("Public charging rate", state.publicRateLabel, subtitle = "DC fast / public sessions") {
            editing = CostField.PUBLIC
        }
        editor(CostField.PUBLIC)
        VoltListDivider()
        ValueRow("Gas price", state.gasPriceLabel) { editing = CostField.GAS_PRICE }
        editor(CostField.GAS_PRICE)
        VoltListDivider()
        ValueRow("Gas vehicle MPG", state.gasMpgLabel, subtitle = "For savings estimates") {
            editing = CostField.GAS_MPG
        }
        editor(CostField.GAS_MPG)
        VoltListDivider()
        val presets = SettingsUiState.CHARGE_TARGET_PRESETS
        val presetIndex = presets.indexOf(state.chargeTargetPct)
        ChoiceRow(
            label = "Charge target",
            subtitle = "Notify at this charge · ${state.chargeTargetLabel}",
            options = presets.map { "$it%" } + "Other",
            selectedIndex = if (presetIndex >= 0) presetIndex else presets.size,
        ) { index ->
            if (index < presets.size) {
                onChange(SettingChange.ChargeTarget(presets[index]))
            } else {
                editing = CostField.CHARGE_TARGET
            }
        }
        editor(CostField.CHARGE_TARGET)
    }
}

private fun costValue(
    state: SettingsUiState,
    field: CostField,
): Double? =
    when (field) {
        CostField.HOME -> state.homeRate
        CostField.PUBLIC -> state.publicRate
        CostField.GAS_PRICE -> state.gasPrice
        CostField.GAS_MPG -> state.gasMpg
        CostField.CHARGE_TARGET -> state.chargeTargetPct.toDouble()
    }?.takeIf { it > 0.0 }

private fun costChange(
    field: CostField,
    value: Double?,
): SettingChange =
    when (field) {
        CostField.HOME -> SettingChange.HomeRate(value ?: 0.0)
        CostField.PUBLIC -> SettingChange.PublicRate(value ?: 0.0)
        CostField.GAS_PRICE -> SettingChange.GasPrice(value ?: 0.0)
        CostField.GAS_MPG -> SettingChange.GasMpg(value)
        CostField.CHARGE_TARGET -> SettingChange.ChargeTarget(value?.toInt() ?: DEFAULT_CHARGE_TARGET)
    }

private const val DEFAULT_CHARGE_TARGET = 100

@Composable
internal fun UnitsPage(
    state: SettingsUiState,
    onChange: (SettingChange) -> Unit,
) {
    VoltPanel {
        ChoiceRow(
            label = "Units",
            subtitle = if (state.metricUnits) "km · °C" else "mi · °F",
            options = listOf("Imperial", "Metric"),
            selectedIndex = if (state.metricUnits) 1 else 0,
        ) { onChange(SettingChange.MetricUnits(it == 1)) }
    }
}

@Composable
internal fun AppearancePage(
    state: SettingsUiState,
    onChange: (SettingChange) -> Unit,
) {
    VoltPanel {
        ThemeRows(state, onChange)
        VoltListDivider()
        ChoiceRow(
            label = "Drive view",
            subtitle = "Focus is the ring; Detailed shows every gauge",
            options = listOf("Focus", "Detailed"),
            selectedIndex = if (state.driveDetailed) 1 else 0,
        ) { onChange(SettingChange.DriveDetailed(it == 1)) }
        VoltListDivider()
        ToggleRow(
            label = "Energy flow on Drive",
            subtitle = "Grid · battery · drive unit · engine",
            on = state.driveEnergyFlow,
        ) { onChange(SettingChange.DriveEnergyFlow(it)) }
        VoltListDivider()
        ToggleRow(
            label = "Keep screen awake",
            subtitle = "While Drive or Trips is live",
            on = state.keepScreenAwake,
        ) { onChange(SettingChange.KeepScreenAwake(it)) }
        VoltListDivider()
        val sizes = SettingsUiState.TEXT_SIZES
        ChoiceRow(
            label = "Text size",
            options = sizes.map { it.second },
            selectedIndex = sizes.indexOfFirst { it.first == state.fontScale }.coerceAtLeast(0),
        ) { onChange(SettingChange.TextSize(sizes[it].first)) }
        VoltListDivider()
        ToggleRow(label = "High contrast", on = state.highContrast) { onChange(SettingChange.HighContrast(it)) }
        VoltListDivider()
        ToggleRow(
            label = "Quiet live data",
            subtitle = "Calmer TalkBack announcements",
            on = state.quietLiveData,
        ) { onChange(SettingChange.QuietLiveData(it)) }
    }
}

/** Mode (System / Dark / Light), the dark style, and — for OLED Black — its accent swatches. */
@Composable
private fun ThemeRows(
    state: SettingsUiState,
    onChange: (SettingChange) -> Unit,
) {
    ChoiceRow(
        label = "Mode",
        subtitle = "System follows your phone's dark theme",
        options = AppearanceMode.entries.map { it.label },
        selectedIndex = state.appearance.ordinal,
    ) { onChange(SettingChange.Appearance(AppearanceMode.entries[it])) }
    VoltListDivider()
    ChoiceRow(
        label = "Dark style",
        subtitle = "Light mode uses Latte",
        options = DarkStyle.entries.map { it.label },
        selectedIndex = state.darkStyle.ordinal,
    ) { onChange(SettingChange.DarkTheme(DarkStyle.entries[it])) }
    if (state.darkStyle == DarkStyle.OLED) {
        VoltListDivider()
        SettingRow(label = "Accent", subtitle = state.accent.label) {}
        AccentSwatches(selected = state.accent, onSelect = { onChange(SettingChange.Accent(it)) })
    }
}

@Composable
internal fun AlertsPage(
    state: SettingsUiState,
    onChange: (SettingChange) -> Unit,
) {
    VoltPanel {
        ToggleRow(label = "Charging complete", on = state.notifyChargingComplete) {
            onChange(SettingChange.NotifyChargeComplete(it))
        }
        VoltListDivider()
        ToggleRow(label = "New car code found", on = state.notifyNewCode) { onChange(SettingChange.NotifyNewCode(it)) }
        VoltListDivider()
        ToggleRow(label = "Battery low", subtitle = state.batteryLowLabel, on = state.notifyBatteryLow) {
            onChange(SettingChange.NotifyBatteryLow(it, state.batteryLowPct))
        }
        if (state.notifyBatteryLow) {
            ThresholdChoice(
                choices = SettingsUiState.BATTERY_LOW_CHOICES,
                selected = state.batteryLowPct,
                label = { "$it%" },
            ) { onChange(SettingChange.NotifyBatteryLow(true, it)) }
        }
        VoltListDivider()
        ToggleRow(label = "Pack temperature high", subtitle = state.packTempHighLabel, on = state.notifyPackTempHigh) {
            onChange(SettingChange.NotifyPackTempHigh(it, state.packTempHighC))
        }
        if (state.notifyPackTempHigh) {
            ThresholdChoice(
                choices = SettingsUiState.PACK_TEMP_CHOICES,
                selected = state.packTempHighC,
                label = state::temperatureLabel,
            ) { onChange(SettingChange.NotifyPackTempHigh(true, it)) }
        }
        VoltListDivider()
        ToggleRow(label = "Maintenance overdue", on = state.notifyMaintenance) {
            onChange(SettingChange.NotifyMaintenance(it))
        }
        VoltListDivider()
        ToggleRow(
            label = "End-of-drive recap",
            on = state.endOfDriveRecap,
        ) { onChange(SettingChange.EndOfDriveRecap(it)) }
        VoltListDivider()
        ToggleRow(
            label = "Auto-scan for codes",
            subtitle = "One background scan per connect",
            on = state.autoScanCodes,
        ) { onChange(SettingChange.AutoScanCodes(it)) }
    }
}

/** The threshold picker under an enabled alert. A stored custom value shows with no choice selected. */
@Composable
private fun ThresholdChoice(
    choices: List<Int>,
    selected: Int,
    label: (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    VoltSegmented(
        options = choices.map(label),
        selectedIndex = choices.indexOf(selected),
        onSelect = { onSelect(choices[it]) },
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    )
}

/** Which passphrase step the Data page has open. */
private enum class DataStep { BACK_UP, RESTORE }

@Composable
internal fun DataPage(
    state: SettingsUiState,
    onCommand: (SettingsCommand) -> Unit,
) {
    var step by rememberSaveable { mutableStateOf<DataStep?>(null) }
    VoltPanel {
        Note(state.lastBackupLabel)
        state.dataTaskLabel?.let {
            Spacer(Modifier.height(6.dp))
            Text(text = it, style = VoltType.caption, color = VoltColors.accent)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VoltButton(text = "Back up", accent = true, onClick = { step = DataStep.BACK_UP })
            VoltButton(text = "Restore", onClick = { step = DataStep.RESTORE })
            VoltButton(text = "Export", onClick = { onCommand(SettingsCommand.ExportTrips) })
        }
        Spacer(Modifier.height(12.dp))
        when (step) {
            DataStep.BACK_UP ->
                PassphraseEditor(
                    hint = "Add a passphrase (8+ characters) to encrypt the backup, or leave it blank.",
                    confirmLabel = "Back up now",
                    onDismiss = { step = null },
                ) {
                    onCommand(SettingsCommand.BackUp(it))
                    step = null
                }
            DataStep.RESTORE ->
                PassphraseEditor(
                    hint = "Only needed if the backup was encrypted. You'll pick the file next.",
                    confirmLabel = "Choose file",
                    onDismiss = { step = null },
                ) {
                    onCommand(SettingsCommand.Restore(it))
                    step = null
                }
            null -> Unit
        }
        Text(
            text = "All data stays on this phone until you share it. Export saves every trip as a CSV.",
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
