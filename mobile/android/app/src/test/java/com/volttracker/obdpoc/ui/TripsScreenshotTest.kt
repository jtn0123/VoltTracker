package com.volttracker.obdpoc.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import com.volttracker.obdpoc.ui.trips.TripRoute
import com.volttracker.obdpoc.ui.trips.TripsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Trips tab with a mixed EV / gas drive selected (the mockups' `S-trips-<theme>.png`), with
 * an electric drive selected, with a drive that has no GPS route, and with nothing logged yet —
 * in OLED Black, Saddle Leather and Latte. `-ProborazziRecord` writes
 * build/outputs/roborazzi/trips-<state>-<theme>.png; every case also proves the screen composes.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class TripsScreenshotTest(
    private val stateName: String,
    private val theme: ThemeCase,
) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun capture() {
        val state = VoltAppUiState(trips = STATES.getValue(stateName), settings = theme.applyTo(SettingsUiState.demo))
        // The full page, so the review images show every drive (the real screen scrolls).
        if (stateName in setOf("mixed", "metric")) RuntimeEnvironment.setQualifiers("+h1240dp")
        compose.setContent { VoltApp(state, initialTab = VoltTab.TRIPS) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/trips-$stateName-${theme.key}.png")
    }

    companion object {
        private val demo = TripsUiState.demo
        private const val EV_KEY = "demo:1"

        private val STATES =
            mapOf(
                "mixed" to demo,
                "ev" to
                    demo.copy(
                        selectedKey = EV_KEY,
                        route =
                            demo.route?.let {
                                TripRoute(
                                    EV_KEY,
                                    it.points.map { p ->
                                        p.copy(gas = false)
                                    },
                                )
                            },
                    ),
                "noroute" to demo.copy(selectedKey = EV_KEY, route = TripRoute(EV_KEY, emptyList())),
                "routefailed" to
                    demo.copy(selectedKey = EV_KEY, route = TripRoute(EV_KEY, emptyList(), failed = true)),
                "empty" to TripsUiState(nowMs = TripsUiState.DEMO_NOW_MS),
                "loading" to TripsUiState(history = HistoryLoad.LOADING),
                "metric" to demo.copy(metricUnits = true),
            )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun cases(): List<Array<Any>> = STATES.keys.flatMap { name -> ThemeCase.THEMES.map { arrayOf<Any>(name, it) } }
    }
}
