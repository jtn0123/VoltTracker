package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.car.CarControlsUi
import com.volttracker.obdpoc.ui.car.CarUiState
import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.drive.TirePressures
import com.volttracker.obdpoc.ui.insights.InsightsUiState
import com.volttracker.obdpoc.ui.settings.AdapterListState
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import com.volttracker.obdpoc.ui.trips.TripsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/**
 * Trips, Insights, Car and Settings on the fixed [NativeVisualRule] fixture, each with its key empty
 * or failure states, in OLED Black, Saddle Leather and Latte; CI compares these reviewed baselines.
 * Synthetic demo data only. The tabs' `*ScreenshotTest` classes stay compose-only explorations.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NativeVisualRule.SDK], qualifiers = NativeVisualRule.QUALIFIERS)
class LauncherTabsVisualRegressionTest(
    private val scenario: Scenario,
    private val theme: ThemeCase,
) {
    // The demo's drives and "now" are written in Pacific daylight time (TripsUiState.DEMO_NOW_MS is
    // Apr 30, 9:42 PM PDT); in UTC that is already May, an empty month. A fixed UTC-7 offset renders
    // them as designed and, unlike a region zone, cannot shift with the JDK's time-zone data.
    @get:Rule val visual = NativeVisualRule(TimeZone.getTimeZone("GMT-07:00"))

    @Test fun capture() {
        val state = scenario.state.let { it.copy(settings = theme.applyTo(it.settings)) }
        visual.capture("${scenario.key}-${theme.key}", state, scenario.tab, scenario.routes, scenario.heightDp)
    }

    /** One reviewed screen state; [key] prefixes its `baselines/<key>-<theme>.png`. */
    enum class Scenario(
        val key: String,
        val tab: VoltTab,
        val state: VoltAppUiState,
        val routes: List<VoltRoute> = emptyList(),
        /** Car captures its whole page: its codes, warnings and tiles sit below a phone's fold. */
        val heightDp: Int? = null,
    ) {
        // Trips: the demo's drives with the mixed EV / gas one selected; nothing logged; a failed read.
        TRIPS("trips", VoltTab.TRIPS, VoltAppUiState(trips = TripsUiState.demo, settings = SETTINGS)),
        TRIPS_EMPTY(
            "trips-empty",
            VoltTab.TRIPS,
            VoltAppUiState(trips = TripsUiState(nowMs = TRIPS_NOW), settings = SETTINGS),
        ),
        TRIPS_FAILED(
            "trips-failed",
            VoltTab.TRIPS,
            VoltAppUiState(trips = TripsUiState(nowMs = TRIPS_NOW, history = HistoryLoad.FAILED), settings = SETTINGS),
        ),

        // Insights: the demo's month; nothing logged; a failed read.
        INSIGHTS("insights", VoltTab.INSIGHTS, VoltAppUiState(insights = InsightsUiState.demo, settings = SETTINGS)),
        INSIGHTS_EMPTY(
            "insights-empty",
            VoltTab.INSIGHTS,
            VoltAppUiState(insights = InsightsUiState(nowMs = TRIPS_NOW), settings = SETTINGS),
        ),
        INSIGHTS_FAILED(
            "insights-failed",
            VoltTab.INSIGHTS,
            VoltAppUiState(
                insights = InsightsUiState(nowMs = TRIPS_NOW, history = HistoryLoad.FAILED),
                settings = SETTINGS,
            ),
        ),

        // Car: parked and locked with controls ready; a low tyre with stored codes and dash warnings;
        // connected through an adapter that hears no body-bus broadcasts.
        CAR("car", VoltTab.CAR, CAR_BASE, heightDp = CAR_PAGE_DP),
        CAR_FAULT(
            "car-fault",
            VoltTab.CAR,
            CAR_BASE.copy(
                drive = PARKED.copy(tires = TirePressures(38.0, 38.0, 37.0, 31.0)),
                diag = DiagUiState.demo,
                car = CAR_BASE.car.copy(dashWarnings = listOf("washer_fluid_low", "bulb_left_brake")),
            ),
            heightDp = CAR_PAGE_DP,
        ),
        CAR_UNREPORTED(
            "car-unreported",
            VoltTab.CAR,
            CAR_BASE.copy(
                drive = DriveUiState(connected = true, statusLabel = "Live", auxVolts = 12.4),
                car = CarUiState(outsideTempC = 12.0, nowMs = CarUiState.DEMO_NOW_MS),
                charge = ChargeUiState(),
                diag = DiagUiState(),
            ),
            heightDp = CAR_PAGE_DP,
        ),

        // Settings: the index while connected; the adapter page before Nearby devices is allowed.
        SETTINGS_MAIN("settings", VoltTab.DRIVE, VoltAppUiState(settings = SETTINGS), listOf(VoltRoute.SETTINGS)),
        SETTINGS_ADAPTER(
            "settings-adapter",
            VoltTab.DRIVE,
            VoltAppUiState(
                settings =
                    SettingsUiState(
                        versionLabel = VERSION_LABEL,
                        adapterList = AdapterListState.NEEDS_PERMISSION,
                    ),
            ),
            listOf(VoltRoute.ADAPTER),
        ),
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun cases(): List<Array<Any>> =
            Scenario.entries.flatMap { scenario -> ThemeCase.THEMES.map { arrayOf<Any>(scenario, it) } }
    }
}

/** Pinned: the demo's `BuildConfig.VERSION_NAME` label changes with every release. */
private const val VERSION_LABEL = "Volt Tracker 1.0.0"
private const val TRIPS_NOW = TripsUiState.DEMO_NOW_MS

/** Tall enough for the whole Car page, its fault state included. */
private const val CAR_PAGE_DP = 1460
private val SETTINGS = SettingsUiState.demo.copy(versionLabel = VERSION_LABEL)
private val PARKED =
    DriveUiState.demoParked.copy(
        aux12SocPercent = 86,
        cabinTempF = 70,
        ambientF = 64,
        tires = TirePressures(38.0, 38.0, 37.0, 38.0),
        locked = true,
        cellSpreadMv = 19.0,
        oilLifePct = 72,
    )
private val CAR_BASE =
    VoltAppUiState(
        drive = PARKED,
        car = CarUiState.demo.copy(controls = CarControlsUi(enabled = true, gate = "ready")),
        charge = ChargeUiState(sohPct = 91.0),
        diag = DiagUiState.demo.copy(codes = emptyList()),
        settings = SETTINGS,
    )
