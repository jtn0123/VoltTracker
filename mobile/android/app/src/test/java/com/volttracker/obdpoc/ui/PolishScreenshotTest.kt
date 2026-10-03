package com.volttracker.obdpoc.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.components.VoltTab
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
 * The polish pass's edge cases in each shipped theme: the Demo flag on every header, a 360dp
 * phone at the largest text size, and the empty / disconnected states. `-ProborazziRecord` writes
 * build/outputs/roborazzi/polish-<case>-<theme>.png; every case also proves the screen composes.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class PolishScreenshotTest(
    private val case: String,
    private val theme: ThemeCase,
) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun capture() {
        val shot = CASES.getValue(case)
        if (shot.narrow) RuntimeEnvironment.setQualifiers("w360dp-h1200dp-420dpi")
        val state = shot.state.copy(settings = theme.applyTo(shot.state.settings))
        compose.setContent { VoltApp(state, initialTab = shot.tab) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/polish-$case-${theme.key}.png")
    }

    private class Shot(
        val tab: VoltTab,
        val state: VoltAppUiState,
        /** A 360dp phone at Settings → Text size → Largest (1.5×), tall enough to show the lower cards. */
        val narrow: Boolean = false,
    )

    companion object {
        private val DEMO = SettingsUiState.demo.copy(demoActive = true)
        private val LARGE = SettingsUiState.demo.copy(fontScale = LARGEST_TEXT)
        private val CASES =
            mapOf(
                "drive-demo" to Shot(VoltTab.DRIVE, VoltAppUiState(drive = DriveUiState.demo, settings = DEMO)),
                "cockpit-demo" to
                    Shot(
                        VoltTab.DRIVE,
                        VoltAppUiState(drive = DriveUiState.demo.copy(detailed = true), settings = DEMO),
                    ),
                "charge-demo" to Shot(VoltTab.CHARGE, VoltAppUiState(charge = ChargeUiState.demo, settings = DEMO)),
                "trips-demo" to Shot(VoltTab.TRIPS, VoltAppUiState(trips = TripsUiState.demo, settings = DEMO)),
                "drive-large" to
                    Shot(VoltTab.DRIVE, VoltAppUiState(drive = DriveUiState.demo, settings = LARGE), narrow = true),
                "cockpit-large" to
                    Shot(
                        VoltTab.DRIVE,
                        VoltAppUiState(drive = DriveUiState.demo.copy(detailed = true), settings = LARGE),
                        narrow = true,
                    ),
                "charging-large" to
                    Shot(
                        VoltTab.DRIVE,
                        VoltAppUiState(drive = DriveUiState.demoCharging, settings = LARGE),
                        narrow = true,
                    ),
                "insights-large" to
                    Shot(
                        VoltTab.INSIGHTS,
                        VoltAppUiState(insights = InsightsUiState.demo, settings = LARGE),
                        narrow = true,
                    ),
                "charge-offline" to Shot(VoltTab.CHARGE, VoltAppUiState(charge = ChargeUiState())),
                "trips-empty" to
                    Shot(VoltTab.TRIPS, VoltAppUiState(trips = TripsUiState(nowMs = TripsUiState.DEMO_NOW_MS))),
                "insights-empty" to
                    Shot(
                        VoltTab.INSIGHTS,
                        VoltAppUiState(insights = InsightsUiState(nowMs = TripsUiState.DEMO_NOW_MS)),
                    ),
                "car-offline" to Shot(VoltTab.CAR, VoltAppUiState()),
            )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun cases(): List<Array<Any>> = CASES.keys.flatMap { case -> ThemeCase.THEMES.map { arrayOf<Any>(case, it) } }
    }
}

private const val LARGEST_TEXT = 1.5
