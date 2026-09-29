package com.volttracker.obdpoc.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.unit.dp
import com.volttracker.obdpoc.ui.car.CarActions
import com.volttracker.obdpoc.ui.car.CarScreen
import com.volttracker.obdpoc.ui.car.carBadge
import com.volttracker.obdpoc.ui.charge.ChargeScreen
import com.volttracker.obdpoc.ui.components.LocalVoltNav
import com.volttracker.obdpoc.ui.components.LocalVoltPrefs
import com.volttracker.obdpoc.ui.components.VoltNavActions
import com.volttracker.obdpoc.ui.components.VoltNavBar
import com.volttracker.obdpoc.ui.components.VoltNavRail
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.components.rememberSystemPrefs
import com.volttracker.obdpoc.ui.diag.DiagScreen
import com.volttracker.obdpoc.ui.diag.HealthActions
import com.volttracker.obdpoc.ui.diag.LiveSignalsScreen
import com.volttracker.obdpoc.ui.diag.hvBattery
import com.volttracker.obdpoc.ui.drive.DriveScreen
import com.volttracker.obdpoc.ui.insights.InsightsScreen
import com.volttracker.obdpoc.ui.settings.SettingChange
import com.volttracker.obdpoc.ui.settings.SettingsActions
import com.volttracker.obdpoc.ui.settings.SettingsPage
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
    val prefs = rememberSystemPrefs(quietLiveData = state.settings.quietLiveData, demo = state.settings.demoActive)
    VoltTheme(
        appearance = state.settings.appearance,
        darkStyle = state.settings.darkStyle,
        accent = state.settings.accent,
        highContrast = state.settings.highContrast,
    ) {
        ScaledText(state.settings.fontScale) {
            SystemBarsAppearance()
            val badges = listOfNotNull(carBadge(state.diag)?.let { VoltTab.CAR to it }).toMap()
            val select: (VoltTab) -> Unit = {
                tab = it
                routes = emptyList()
            }
            val page: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier = modifier) {
                    CompositionLocalProvider(LocalVoltNav provides nav, LocalVoltPrefs provides prefs) {
                        when (val route = routes.lastOrNull()) {
                            null ->
                                VoltTabContent(
                                    tab = tab,
                                    state = state,
                                    actions = actions,
                                    onConnect = { if (!actions.onConnect()) push(VoltRoute.ADAPTER) },
                                )
                            else ->
                                VoltRouteContent(
                                    route = route,
                                    state = state,
                                    actions = actions,
                                    onBack = pop,
                                    openAdapter = { push(VoltRoute.ADAPTER) },
                                    openSignals = { push(VoltRoute.SIGNALS) },
                                )
                        }
                    }
                }
            }
            // Edge-to-edge (targetSdk 35+): paint the canvas under the system bars and keep the
            // content clear of them. A landscape phone gets a side rail instead of a bottom bar.
            BoxWithConstraints(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(VoltColors.bg)
                        .safeDrawingPadding(),
            ) {
                if (maxWidth > maxHeight && maxHeight < RAIL_MAX_HEIGHT) {
                    Row(Modifier.fillMaxSize()) {
                        VoltNavRail(selected = tab, badges = badges, onSelect = select)
                        page(Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        page(Modifier.weight(1f).fillMaxWidth())
                        VoltNavBar(selected = tab, badges = badges, onSelect = select)
                    }
                }
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
        VoltRoute.SETTINGS, VoltRoute.ADAPTER -> "settings"
        VoltRoute.HEALTH, VoltRoute.SIGNALS -> "diagnostics"
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
    onConnect: () -> Unit,
) {
    when (tab) {
        VoltTab.DRIVE ->
            DriveScreen(
                state.drive,
                onConnect = onConnect,
                onStartDemo = actions.onStartDemo,
                showEnergyFlow = state.settings.driveEnergyFlow,
                onSetDetailed = { actions.onSettingChange(SettingChange.DriveDetailed(it)) },
            )
        VoltTab.TRIPS -> TripsScreen(state.trips, onSelect = actions.onSelectTrip, onExport = actions.onExportTrip)
        VoltTab.CHARGE -> ChargeScreen(state.charge, onConnect = onConnect, onStartDemo = actions.onStartDemo)
        VoltTab.INSIGHTS -> InsightsScreen(state.insights, onPeriod = actions.onInsightsPeriod)
        VoltTab.CAR ->
            CarScreen(
                drive = state.drive,
                car = state.car,
                diag = state.diag,
                sohPct = state.charge.sohPct,
                demo = state.settings.demoActive,
                actions =
                    CarActions(
                        onControl = actions.onCarControl,
                        onEnableControls = { actions.onCarControlsEnabled(true) },
                        onDisableControls = { actions.onCarControlsEnabled(false) },
                    ),
            )
    }
}

@Composable
private fun VoltRouteContent(
    route: VoltRoute,
    state: VoltAppUiState,
    actions: VoltAppActions,
    onBack: () -> Unit,
    openAdapter: () -> Unit,
    openSignals: () -> Unit,
) {
    when (route) {
        VoltRoute.HEALTH ->
            DiagScreen(
                state.diag,
                battery = hvBattery(state.drive, state.charge.sohPct, state.charge.capacityAh),
                drive = state.drive,
                demo = state.settings.demoActive,
                onBack = onBack,
                actions =
                    HealthActions(
                        onScan = actions.onScanCodes,
                        onClear = actions.onClearCodes,
                        onShare = actions.onShareHealthReport,
                        onOpenClassic = actions.onOpenClassicDashboard,
                        onOpenAdapter = openAdapter,
                        onOpenSignals = openSignals,
                    ),
            )
        VoltRoute.SIGNALS ->
            LiveSignalsScreen(state.drive, onBack = onBack, onOpenClassic = actions.onOpenClassicDashboard)
        VoltRoute.SETTINGS -> Settings(state, actions, onBack, SettingsPage.MAIN)
        VoltRoute.ADAPTER -> Settings(state, actions, onBack, SettingsPage.CONNECTION)
    }
}

@Composable
private fun Settings(
    state: VoltAppUiState,
    actions: VoltAppActions,
    onBack: () -> Unit,
    initialPage: SettingsPage,
) {
    SettingsScreen(
        state.settings,
        initialPage = initialPage,
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

/** Below this height a landscape window trades the bottom bar for a side rail. */
private val RAIL_MAX_HEIGHT = 480.dp

/** Saves the route stack as enum names so it survives rotation and process death. */
private val RouteStackSaver =
    listSaver<List<VoltRoute>, String>(
        save = { stack -> stack.map { it.name } },
        restore = { names -> names.map { VoltRoute.valueOf(it) } },
    )
