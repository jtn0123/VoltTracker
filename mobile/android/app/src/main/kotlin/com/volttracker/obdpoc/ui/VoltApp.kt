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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.volttracker.obdpoc.ui.car.CarScreen
import com.volttracker.obdpoc.ui.car.carBadge
import com.volttracker.obdpoc.ui.car.cellBalanceLabel
import com.volttracker.obdpoc.ui.charge.ChargeScreen
import com.volttracker.obdpoc.ui.components.LocalVoltNav
import com.volttracker.obdpoc.ui.components.VoltNavActions
import com.volttracker.obdpoc.ui.components.VoltNavBar
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.diag.DiagScreen
import com.volttracker.obdpoc.ui.drive.DriveScreen
import com.volttracker.obdpoc.ui.insights.InsightsScreen
import com.volttracker.obdpoc.ui.settings.SettingChange
import com.volttracker.obdpoc.ui.settings.SettingsActions
import com.volttracker.obdpoc.ui.settings.SettingsScreen
import com.volttracker.obdpoc.ui.theme.SystemBarsAppearance
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltTheme
import com.volttracker.obdpoc.ui.trips.TripsScreen

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
    val screen = screenViewName(tab, routes.lastOrNull())
    LaunchedEffect(screen) { actions.onScreenShown(screen) }
    VoltTheme(
        appearance = state.settings.appearance,
        darkStyle = state.settings.darkStyle,
        accent = state.settings.accent,
    ) {
        ScaledText(state.settings.fontScale) {
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
}

/**
 * Settings → Text size. Scales every sp on top of the phone's own font scale, like the classic
 * dashboard's --font-scale token.
 */
@Composable
private fun ScaledText(
    scale: Double,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scaled = remember(density, scale) { Density(density.density, density.fontScale * scale.toFloat()) }
    CompositionLocalProvider(LocalDensity provides scaled, content = content)
}

/** The classic dashboard's name for what is on screen (its keep-screen-awake rule keys on it). */
internal fun screenViewName(
    tab: VoltTab,
    route: VoltRoute?,
): String =
    when (route) {
        VoltRoute.SETTINGS -> "settings"
        VoltRoute.HEALTH -> "diagnostics"
        null ->
            when (tab) {
                VoltTab.DRIVE -> "drive"
                VoltTab.TRIPS -> "map"
                VoltTab.CHARGE -> "charge"
                VoltTab.INSIGHTS -> "insights"
                VoltTab.CAR -> "diagnostics"
            }
    }

@Composable
private fun VoltTabContent(
    tab: VoltTab,
    state: VoltAppUiState,
    actions: VoltAppActions,
) {
    when (tab) {
        VoltTab.DRIVE ->
            DriveScreen(
                state.drive,
                onConnect = actions.onConnect,
                onStartDemo = actions.onStartDemo,
                showEnergyFlow = state.settings.driveEnergyFlow,
                onSetDetailed = { actions.onSettingChange(SettingChange.DriveDetailed(it)) },
            )
        VoltTab.TRIPS -> TripsScreen(state.trips, onSelect = actions.onSelectTrip, onExport = actions.onExportTrip)
        VoltTab.CHARGE -> ChargeScreen(state.charge)
        VoltTab.INSIGHTS -> InsightsScreen(state.insights, onPeriod = actions.onInsightsPeriod)
        VoltTab.CAR -> CarScreen(diag = state.diag, cellBalanceLabel = cellBalanceLabel(state.drive.cellSpreadMv))
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
                        onStartDemo = actions.onStartDemo,
                        onStopDemo = actions.onStopDemo,
                        onChange = actions.onSettingChange,
                        onCommand = actions.onSettingsCommand,
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
