package com.volttracker.obdpoc.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.components.VoltButton
import com.volttracker.obdpoc.ui.components.VoltIcons
import com.volttracker.obdpoc.ui.components.VoltListDivider
import com.volttracker.obdpoc.ui.components.VoltPanel
import com.volttracker.obdpoc.ui.components.VoltSegmented
import com.volttracker.obdpoc.ui.drive.TIRE_LOW_MARGIN_PSI
import com.volttracker.obdpoc.ui.theme.AppearanceMode
import com.volttracker.obdpoc.ui.theme.DarkStyle
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltType
import com.volttracker.obdpoc.ui.units.VoltUnits

// Detail pages behind the Settings index. Every control reads the stored value from [SettingsUiState]
// and reports edits as a [SettingChange]; the host persists them.

@Composable
internal fun ConnectionPage(
    state: SettingsUiState,
    onChange: (SettingChange) -> Unit,
    onCommand: (SettingsCommand) -> Unit,
) {
    VoltPanel {
        PairedAdaptersSection(state, onCommand)
        SettingRow(label = "Adapter", subtitle = "Bluetooth OBD-II") {
            Value(state.adapterLabel.takeUnless { it == "--" } ?: "None chosen")
        }
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
            enabled = state.waitingForAdapter,
        ) { onCommand(SettingsCommand.WaitForAdapter(state.waitingForAdapter, it)) }
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VoltButton(text = "Test connection", onClick = { onCommand(SettingsCommand.TestConnection) })
            VoltButton(text = "Send diagnostics", onClick = { onCommand(SettingsCommand.SendDiagnostics) })
        }
    }
}

/**
 * The number editors on the Costs page, keyed so the open one survives rotation. Gas price and
 * economy are edited in the chosen units (per litre and L/100 km in metric) over the same range
 * the store accepts in $/gal and mpg.
 */
private enum class CostField {
    HOME,
    PUBLIC,
    GAS_PRICE,
    GAS_MPG,
    CHARGE_TARGET,
    ;

    fun field(units: VoltUnits): NumberField =
        when (this) {
            HOME -> NumberField("Home electricity rate", "$/kWh", 0.0, MAX_RATE)
            PUBLIC -> NumberField("Public charging rate", "$/kWh", 0.0, MAX_RATE)
            GAS_PRICE ->
                NumberField("Gas price", "$/${units.gasVolumeUnit}", 0.0, units.gasPrice(MAX_GAS_PRICE))
            GAS_MPG ->
                if (units.metric) {
                    NumberField("Gas vehicle economy", units.economyUnit, economyMin(units), economyMax(units))
                } else {
                    NumberField("Gas vehicle mpg", "mpg", MIN_MPG, MAX_MPG)
                }
            CHARGE_TARGET -> NumberField("Charge target", "%", MIN_TARGET, FULL_TARGET, clearable = false)
        }
}

// L/100 km runs the other way from mpg: the fewest litres is the most mpg.
private fun economyMin(units: VoltUnits): Double = units.economy(MAX_MPG) ?: 0.0

private fun economyMax(units: VoltUnits): Double = units.economy(MIN_MPG) ?: 0.0

private const val MAX_RATE = 2.0
private const val MAX_GAS_PRICE = 10.0
private const val MIN_MPG = 5.0
private const val MAX_MPG = 150.0
private const val MIN_TARGET = 50.0
private const val FULL_TARGET = 100.0

@Composable
internal fun CostsPage(
    state: SettingsUiState,
    onChange: (SettingChange) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf<CostField?>(null) }
    val editor: @Composable (CostField) -> Unit = { field ->
        if (editing == field) {
            NumberEditor(field.field(state.units), costValue(state, field), onDismiss = { editing = null }) { value ->
                onChange(costChange(state.units, field, value))
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
        ValueRow(
            if (state.metricUnits) "Gas vehicle economy" else "Gas vehicle mpg",
            state.gasMpgLabel,
            subtitle = "For savings estimates",
        ) {
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
        CostField.GAS_PRICE -> state.units.gasPrice(state.gasPrice)
        CostField.GAS_MPG -> state.gasMpg?.let(state.units::economy)
        CostField.CHARGE_TARGET -> state.chargeTargetPct.toDouble()
    }?.takeIf { it > 0.0 }

private fun costChange(
    units: VoltUnits,
    field: CostField,
    value: Double?,
): SettingChange =
    when (field) {
        CostField.HOME -> SettingChange.HomeRate(value ?: 0.0)
        CostField.PUBLIC -> SettingChange.PublicRate(value ?: 0.0)
        CostField.GAS_PRICE -> SettingChange.GasPrice(value?.let(units::gasPricePerGallon) ?: 0.0)
        CostField.GAS_MPG -> SettingChange.GasMpg(value?.let(units::mpgFrom))
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
            subtitle = if (state.metricUnits) "km · °C · kPa" else "mi · °F · psi",
            options = listOf("Imperial", "Metric"),
            selectedIndex = if (state.metricUnits) 1 else 0,
        ) { onChange(SettingChange.MetricUnits(it == 1)) }
        VoltListDivider()
        var editing by rememberSaveable { mutableStateOf(false) }
        ValueRow(
            "Tire placard pressure",
            state.tirePlacardLabel,
            subtitle =
                "Cold pressure on the driver's door jamb; tires read low " +
                    "${state.units.pressureText(TIRE_LOW_MARGIN_PSI)} under it",
        ) { editing = true }
        if (editing) {
            val units = state.units
            NumberEditor(placardField(units), units.pressureValue(state.tirePlacardPsi), onDismiss = {
                editing = false
            }) { value ->
                value?.let { onChange(SettingChange.TirePlacard(units.psiFrom(it))) }
                editing = false
            }
        }
    }
}

/** The placard editor in the chosen units, over the same 20–60 psi the store accepts. */
private fun placardField(units: VoltUnits): NumberField =
    NumberField(
        "Tire placard",
        units.pressureUnit,
        units.pressureValue(PLACARD_MIN_PSI),
        units.pressureValue(PLACARD_MAX_PSI),
        clearable = false,
    )

private const val PLACARD_MIN_PSI = 20.0
private const val PLACARD_MAX_PSI = 60.0

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
        subtitle = state.appearance.hint,
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
    // The accent only exists in OLED Black, and does nothing while Light pins Latte.
    if (state.darkStyle == DarkStyle.OLED && state.appearance != AppearanceMode.LIGHT) {
        VoltListDivider()
        val whenUsed = if (state.appearance == AppearanceMode.SYSTEM) " · used when your phone is dark" else ""
        SettingRow(label = "Accent", subtitle = state.accent.label + whenUsed) {}
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
    enabled: Boolean = true,
    onSelect: (Int) -> Unit,
) {
    VoltSegmented(
        options = choices.map(label),
        selectedIndex = choices.indexOf(selected),
        role = Role.RadioButton,
        enabled = enabled,
        onSelect = { onSelect(choices[it]) },
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
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
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VoltButton(text = "Back up", onClick = { step = DataStep.BACK_UP })
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
            text = "All data stays on this phone until you share it. Export saves every drive as a CSV.",
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
            VoltButton(text = "Stop demo", onClick = onStopDemo)
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
            "Connection logs, live readings from every sensor, and the rarely used settings " +
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
