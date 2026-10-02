package com.volttracker.obdpoc.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.insights.InsightsUiState
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import com.volttracker.obdpoc.ui.trips.TripsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The usefulness pass: a drive's and a charge's receipt, Insights' plain-sentence summary, and
 * Health's battery trend, in OLED Black, Saddle Leather and Latte, full page. `-ProborazziRecord`
 * writes build/outputs/roborazzi/useful-<state>-<theme>.png.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h1400dp-420dpi")
class UsefulnessScreenshotTest(
    private val stateName: String,
    private val theme: ThemeCase,
) {
    @get:Rule
    val compose = createComposeRule()

    private class Case(
        val tab: VoltTab,
        val routes: List<VoltRoute> = emptyList(),
        val state: VoltAppUiState = base,
        val charge: Long? = null,
        val tall: Boolean = false,
    )

    @Test
    fun capture() {
        val case = STATES.getValue(stateName)
        if (case.tall) RuntimeEnvironment.setQualifiers("+h1700dp")
        val state = case.state.copy(settings = theme.applyTo(case.state.settings))
        compose.setContent {
            VoltApp(state, initialTab = case.tab, initialRoutes = case.routes, initialCharge = case.charge)
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/useful-$stateName-${theme.key}.png")
    }

    companion object {
        private val charge = ChargeUiState.demo.copy(charging = false)
        private val base =
            VoltAppUiState(
                drive = DriveUiState.demo,
                charge = charge,
                trips = TripsUiState.demo,
                insights = InsightsUiState.demo,
                diag = DiagUiState.demo,
                settings = SettingsUiState.demo,
            )

        private val STATES: Map<String, Case> =
            mapOf(
                "trip-mixed" to Case(VoltTab.TRIPS, listOf(VoltRoute.TRIP), tall = true),
                // A real (exportable) drive: export buttons plus the classic rename/star link.
                "trip-real" to
                    Case(
                        VoltTab.TRIPS,
                        listOf(VoltRoute.TRIP),
                        base.copy(trips = TripsUiState.demo.copy(exportable = true)),
                        tall = true,
                    ),
                "trip-ev" to
                    Case(
                        VoltTab.TRIPS,
                        listOf(VoltRoute.TRIP),
                        base.copy(trips = TripsUiState.demo.copy(selectedKey = "demo:1", route = null)),
                    ),
                "trip-norates" to
                    Case(
                        VoltTab.TRIPS,
                        listOf(VoltRoute.TRIP),
                        base.copy(trips = TripsUiState.demo.copy(homeRate = 0.0, gasMpg = null, gasPrice = 0.0)),
                    ),
                "charge" to
                    Case(
                        VoltTab.CHARGE,
                        listOf(VoltRoute.CHARGE),
                        charge = charge.sessions.first().startedAtMs,
                    ),
                "charge-list" to Case(VoltTab.CHARGE),
                "insights" to Case(VoltTab.INSIGHTS, tall = true),
                "health" to Case(VoltTab.CAR, listOf(VoltRoute.HEALTH)),
            )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun cases(): List<Array<Any>> = STATES.keys.flatMap { name -> ThemeCase.THEMES.map { arrayOf<Any>(name, it) } }
    }
}
