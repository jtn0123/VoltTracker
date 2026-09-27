package com.volttracker.obdpoc.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.volttracker.obdpoc.ui.car.CarScreen
import com.volttracker.obdpoc.ui.car.carBadge
import com.volttracker.obdpoc.ui.charge.ChargeScreen
import com.volttracker.obdpoc.ui.components.LocalVoltNav
import com.volttracker.obdpoc.ui.components.VoltNavActions
import com.volttracker.obdpoc.ui.components.VoltNavBar
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.diag.DiagScreen
import com.volttracker.obdpoc.ui.drive.DriveScreen
import com.volttracker.obdpoc.ui.insights.InsightsScreen
import com.volttracker.obdpoc.ui.map.MapScreen
import com.volttracker.obdpoc.ui.settings.SettingsActions
import com.volttracker.obdpoc.ui.settings.SettingsScreen
import com.volttracker.obdpoc.ui.theme.SystemBarsAppearance
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltTheme

/**
 * The whole Compose dashboard: five tabs under a full-width nav bar, with Settings and Health
 * pushed on top as back-able routes, themed by Settings → Appearance. Pure function of [state]
 * plus local navigation — hosting activities and screenshot tests drive it identically.
 */
@Composable
fun VoltApp(
    state: VoltAppUiState,
    initialTab: VoltTab = VoltTab.DRIVE,
    initialRoutes: List<VoltRoute> = emptyList(),
    actions: VoltAppActions = VoltAppActions(),
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var routes by rememberSaveable(stateSaver = RouteStackSaver) { mutableStateOf(initialRoutes) }
    val push: (VoltRoute) -> Unit = { route -> routes = routes.filterNot { it == route } + route }
    val pop: () -> Unit = { routes = routes.dropLast(1) }
    val nav =
        remember {
            VoltNavActions(
                openSettings = { push(VoltRoute.SETTINGS) },
                openHealth = { push(VoltRoute.HEALTH) },
            )
        }
    BackHandler(enabled = routes.isNotEmpty(), onBack = pop)
    VoltTheme(appearance = state.settings.appearance) {
        SystemBarsAppearance()
        // Edge-to-edge (targetSdk 35+): paint the canvas under the system bars and keep the
        // content clear of them.
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(VoltColors.bg)
                    .safeDrawingPadding(),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                CompositionLocalProvider(LocalVoltNav provides nav) {
                    when (val route = routes.lastOrNull()) {
                        null -> VoltTabContent(tab = tab, state = state, actions = actions)
                        else -> VoltRouteContent(route = route, state = state, actions = actions, onBack = pop)
                    }
                }
            }
            VoltNavBar(
                selected = tab,
                badges = listOfNotNull(carBadge(state.diag)?.let { VoltTab.CAR to it }).toMap(),
                onSelect = {
                    tab = it
                    routes = emptyList()
                },
            )
        }
    }
}

@Composable
private fun VoltTabContent(
    tab: VoltTab,
    state: VoltAppUiState,
    actions: VoltAppActions,
) {
    when (tab) {
        VoltTab.DRIVE -> DriveScreen(state.drive, onConnect = actions.onConnect, onStartDemo = actions.onStartDemo)
        VoltTab.TRIPS -> MapScreen(state.map)
        VoltTab.CHARGE -> ChargeScreen(state.charge)
        VoltTab.INSIGHTS -> InsightsScreen(state.insights)
        VoltTab.CAR -> CarScreen(diag = state.diag, charge = state.charge)
    }
}

@Composable
private fun VoltRouteContent(
    route: VoltRoute,
    state: VoltAppUiState,
    actions: VoltAppActions,
    onBack: () -> Unit,
) {
    when (route) {
        VoltRoute.HEALTH -> DiagScreen(state.diag, onBack = onBack)
        VoltRoute.SETTINGS ->
            SettingsScreen(
                state.settings,
                onBack = onBack,
                actions =
                    SettingsActions(
                        onOpenClassicDashboard = actions.onOpenClassicDashboard,
                        onCheckForUpdate = actions.onCheckForUpdate,
                        onInstallUpdate = actions.onInstallUpdate,
                        onSetAppearance = actions.onSetAppearance,
                        onStartDemo = actions.onStartDemo,
                        onStopDemo = actions.onStopDemo,
                    ),
            )
    }
}

/** Saves the route stack as enum names so it survives rotation and process death. */
private val RouteStackSaver =
    listSaver<List<VoltRoute>, String>(
        save = { stack -> stack.map { it.name } },
        restore = { names -> names.map { VoltRoute.valueOf(it) } },
    )
