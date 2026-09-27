package com.volttracker.obdpoc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.volttracker.obdpoc.ui.charge.ChargeScreen
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.diag.DiagScreen
import com.volttracker.obdpoc.ui.drive.DriveScreen
import com.volttracker.obdpoc.ui.insights.InsightsScreen
import com.volttracker.obdpoc.ui.map.MapScreen
import com.volttracker.obdpoc.ui.settings.SettingsScreen
import com.volttracker.obdpoc.ui.theme.SystemBarsAppearance
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltTheme

/**
 * The whole Compose dashboard: the tab screens behind one selected-tab switch, themed by the
 * Settings → Appearance choice. Pure function of [state] plus local tab selection — hosting
 * activities and screenshot tests drive it identically.
 */
@Composable
fun VoltApp(
    state: VoltAppUiState,
    initialTab: VoltTab = VoltTab.DRIVE,
    actions: VoltAppActions = VoltAppActions(),
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    VoltTheme(appearance = state.settings.appearance) {
        SystemBarsAppearance()
        // Edge-to-edge (targetSdk 35+): paint the canvas under the system bars and keep the
        // content clear of them.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(VoltColors.bg)
                    .safeDrawingPadding(),
        ) {
            VoltTabContent(tab = tab, state = state, onSelectTab = { tab = it }, actions = actions)
        }
    }
}

@Composable
private fun VoltTabContent(
    tab: VoltTab,
    state: VoltAppUiState,
    onSelectTab: (VoltTab) -> Unit,
    actions: VoltAppActions,
) {
    when (tab) {
        VoltTab.DRIVE ->
            DriveScreen(
                state.drive,
                onSelectTab = onSelectTab,
                onConnect = actions.onConnect,
                onStartDemo = actions.onStartDemo,
            )
        VoltTab.MAP -> MapScreen(state.map, onSelectTab = onSelectTab)
        VoltTab.CHARGE -> ChargeScreen(state.charge, onSelectTab = onSelectTab)
        VoltTab.INSIGHTS -> InsightsScreen(state.insights, onSelectTab = onSelectTab)
        VoltTab.DIAG -> DiagScreen(state.diag, onSelectTab = onSelectTab)
        VoltTab.SETTINGS ->
            SettingsScreen(
                state.settings,
                onSelectTab = onSelectTab,
                onOpenClassicDashboard = actions.onOpenClassicDashboard,
                onCheckForUpdate = actions.onCheckForUpdate,
                onInstallUpdate = actions.onInstallUpdate,
                onSetAppearance = actions.onSetAppearance,
            )
    }
}
