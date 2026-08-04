package com.volttracker.obdpoc.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.volttracker.obdpoc.ui.charge.ChargeScreen
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.diag.DiagScreen
import com.volttracker.obdpoc.ui.drive.DriveScreen
import com.volttracker.obdpoc.ui.insights.InsightsScreen
import com.volttracker.obdpoc.ui.map.MapScreen
import com.volttracker.obdpoc.ui.settings.SettingsScreen
import com.volttracker.obdpoc.ui.theme.VoltTheme

/**
 * The whole Compose dashboard: six tab screens behind one selected-tab switch.
 * Pure function of [state] plus local tab selection — hosting activities and
 * screenshot tests drive it identically.
 */
@Composable
fun VoltApp(
    state: VoltAppUiState,
    initialTab: VoltTab = VoltTab.DRIVE,
    onOpenClassicDashboard: () -> Unit = {},
    onConnect: () -> Unit = {},
    onStartDemo: () -> Unit = {},
    onCheckForUpdate: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    VoltTheme {
        when (tab) {
            VoltTab.DRIVE ->
                DriveScreen(
                    state.drive,
                    onSelectTab = { tab = it },
                    onConnect = onConnect,
                    onStartDemo = onStartDemo,
                )
            VoltTab.MAP -> MapScreen(state.map, onSelectTab = { tab = it })
            VoltTab.CHARGE -> ChargeScreen(state.charge, onSelectTab = { tab = it })
            VoltTab.INSIGHTS -> InsightsScreen(state.insights, onSelectTab = { tab = it })
            VoltTab.DIAG -> DiagScreen(state.diag, onSelectTab = { tab = it })
            VoltTab.SETTINGS ->
                SettingsScreen(
                    state.settings,
                    onSelectTab = { tab = it },
                    onOpenClassicDashboard = onOpenClassicDashboard,
                    onCheckForUpdate = onCheckForUpdate,
                    onInstallUpdate = onInstallUpdate,
                )
        }
    }
}
