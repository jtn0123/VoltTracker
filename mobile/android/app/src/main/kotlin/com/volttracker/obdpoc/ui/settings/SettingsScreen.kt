package com.volttracker.obdpoc.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volttracker.obdpoc.ui.components.IconSquare
import com.volttracker.obdpoc.ui.components.PillTone
import com.volttracker.obdpoc.ui.components.VoltButton
import com.volttracker.obdpoc.ui.components.VoltGroupLabel
import com.volttracker.obdpoc.ui.components.VoltIcons
import com.volttracker.obdpoc.ui.components.VoltListCard
import com.volttracker.obdpoc.ui.components.VoltListDivider
import com.volttracker.obdpoc.ui.components.VoltListRow
import com.volttracker.obdpoc.ui.components.VoltPanel
import com.volttracker.obdpoc.ui.components.VoltScreen
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltTheme
import com.volttracker.obdpoc.ui.theme.VoltType

/** Settings sub-pages. [MAIN] is the grouped index; the rest hold the detailed controls. */
enum class SettingsPage(
    val title: String,
) {
    MAIN("Settings"),
    CONNECTION("Adapter"),
    COSTS("Costs & rates"),
    UNITS("Units"),
    APPEARANCE("Appearance"),
    ALERTS("Alerts"),
    DATA("Export & backup"),
    DEMO("Demo / testing"),
    ADVANCED("Advanced diagnostics"),
    UPDATES("App updates"),
}

/** Host actions the Settings pages can trigger. Defaults are no-ops (previews, tests). */
class SettingsActions(
    val onOpenClassicDashboard: () -> Unit = {},
    val onCheckForUpdate: () -> Unit = {},
    val onInstallUpdate: () -> Unit = {},
    val onStartDemo: () -> Unit = {},
    val onStopDemo: () -> Unit = {},
    /** Every stored-setting edit (toggles, pickers, number editors). */
    val onChange: (SettingChange) -> Unit = {},
    /** One-off tools: test connection, wait for adapter, diagnostics, backup / restore / export. */
    val onCommand: (SettingsCommand) -> Unit = {},
)

/**
 * Settings, opened from the gear on any screen (mockups `S.settings`): the adapter card, then
 * Costs / Preferences / Data / About groups whose rows open detail pages. Every setting from the
 * previous single-page layout lives on one of those pages.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    modifier: Modifier = Modifier,
    initialPage: SettingsPage = SettingsPage.MAIN,
    onBack: () -> Unit = {},
    actions: SettingsActions = SettingsActions(),
) {
    var page by rememberSaveable { mutableStateOf(initialPage) }
    BackHandler(enabled = page != SettingsPage.MAIN) { page = SettingsPage.MAIN }
    val open: (SettingsPage) -> Unit = { page = it }
    VoltScreen(
        title = page.title,
        modifier = modifier,
        onBack = if (page == SettingsPage.MAIN) onBack else ({ page = SettingsPage.MAIN }),
        showGear = false,
    ) {
        when (page) {
            SettingsPage.MAIN -> SettingsIndex(state, open)
            SettingsPage.CONNECTION -> ConnectionPage(state, actions.onChange, actions.onCommand)
            SettingsPage.COSTS -> CostsPage(state, actions.onChange)
            SettingsPage.UNITS -> UnitsPage(state, actions.onChange)
            SettingsPage.APPEARANCE -> AppearancePage(state, actions.onChange)
            SettingsPage.ALERTS -> AlertsPage(state, actions.onChange)
            SettingsPage.DATA -> DataPage(state, actions.onCommand)
            SettingsPage.DEMO -> DemoPage(state, actions.onStartDemo, actions.onStopDemo)
            SettingsPage.ADVANCED -> AdvancedPage(actions.onOpenClassicDashboard)
            SettingsPage.UPDATES -> UpdatesPage(state, actions.onCheckForUpdate, actions.onInstallUpdate)
        }
    }
}

@Composable
private fun ColumnScope.SettingsIndex(
    state: SettingsUiState,
    open: (SettingsPage) -> Unit,
) {
    AdapterCard(state, onManage = { open(SettingsPage.CONNECTION) })
    VoltGroupLabel("Costs")
    VoltListCard {
        VoltListRow(VoltIcons.Bolt, "Electricity rate", value = state.homeRateLabel, compact = true) {
            open(SettingsPage.COSTS)
        }
        VoltListDivider()
        VoltListRow(VoltIcons.Fuel, "Gas price", value = state.gasPriceLabel, compact = true) {
            open(SettingsPage.COSTS)
        }
    }
    VoltGroupLabel("Preferences")
    VoltListCard {
        VoltListRow(VoltIcons.Settings, "Units", value = state.unitsLabel, compact = true) { open(SettingsPage.UNITS) }
        VoltListDivider()
        VoltListRow(VoltIcons.Window, "Appearance", value = state.appearance.label, compact = true) {
            open(SettingsPage.APPEARANCE)
        }
        VoltListDivider()
        VoltListRow(VoltIcons.Alert, "Alerts", value = "${state.alertsOnCount} on", compact = true) {
            open(SettingsPage.ALERTS)
        }
    }
    VoltGroupLabel("Data")
    VoltListCard {
        VoltListRow(VoltIcons.Share, "Export & backup", compact = true) { open(SettingsPage.DATA) }
        VoltListDivider()
        VoltListRow(VoltIcons.Play, "Demo / testing", value = if (state.demoActive) "On" else "Off", compact = true) {
            open(SettingsPage.DEMO)
        }
        VoltListDivider()
        VoltListRow(VoltIcons.Wrench, "Advanced diagnostics", value = "Logs, raw PIDs", compact = true) {
            open(SettingsPage.ADVANCED)
        }
    }
    VoltGroupLabel("About")
    VoltListCard {
        VoltListRow(
            VoltIcons.Refresh,
            "App updates",
            value = state.updateAvailableTag?.let { "$it available" },
            tone = if (state.updateAvailableTag != null) PillTone.VOLT else PillTone.NEUTRAL,
            compact = true,
        ) { open(SettingsPage.UPDATES) }
    }
    Spacer(Modifier.height(18.dp))
    Text(
        text = state.versionLabel,
        style = VoltType.caption.copy(fontSize = 12.sp),
        color = VoltColors.textTertiary,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
}

/** The paired adapter, its link state, and the way into connection settings. */
@Composable
private fun AdapterCard(
    state: SettingsUiState,
    onManage: () -> Unit,
) {
    val energy = VoltColors.energy
    VoltPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconSquare(VoltIcons.Bluetooth, tone = if (state.connected) PillTone.EV else PillTone.NEUTRAL)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    text = state.adapterLabel.takeUnless { it == "--" } ?: "No adapter",
                    style = VoltType.bodyStrong,
                    color = VoltColors.textPrimary,
                )
                Text(
                    text =
                        buildAnnotatedString {
                            if (state.connected) {
                                withStyle(SpanStyle(color = energy)) { append("Connected") }
                            } else {
                                append(state.statusLabel)
                            }
                            append(" · auto-connect ")
                            append(if (state.autoConnect) "on" else "off")
                        },
                    style = VoltType.caption,
                    color = VoltColors.textSecondary,
                )
            }
            VoltButton(text = "Manage", onClick = onManage)
        }
    }
}

@Preview(widthDp = 412, heightDp = 1000)
@Composable
private fun SettingsScreenPreview() {
    VoltTheme { SettingsScreen(SettingsUiState.demo) }
}
