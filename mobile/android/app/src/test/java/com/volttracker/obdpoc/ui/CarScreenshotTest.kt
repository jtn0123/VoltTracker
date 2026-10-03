package com.volttracker.obdpoc.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.volttracker.obdpoc.ui.car.BodyGroup
import com.volttracker.obdpoc.ui.car.CarControlsUi
import com.volttracker.obdpoc.ui.car.CarUiState
import com.volttracker.obdpoc.ui.car.Openings
import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.drive.TirePressures
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Car tab (the mockups' `S-car` / `S-carFault`): parked and locked with controls ready, a low
 * tyre with stored codes, a door and window open with controls blocked, nothing reported (no
 * OBDLink), and metric units — in OLED Black, Saddle Leather and Latte. `-ProborazziRecord` writes
 * build/outputs/roborazzi/car-<state>-<theme>.png; every case also proves the screen composes.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h1150dp-420dpi")
class CarScreenshotTest(
    private val stateName: String,
    private val theme: ThemeCase,
) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun capture() {
        val state = STATES.getValue(stateName).let { it.copy(settings = theme.applyTo(it.settings)) }
        compose.setContent { VoltApp(state, initialTab = VoltTab.CAR) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/car-$stateName-${theme.key}.png")
    }

    companion object {
        private val parked =
            DriveUiState.demoParked.copy(
                aux12SocPercent = 86,
                cabinTempF = 70,
                ambientF = 64,
                tires = TirePressures(38.0, 38.0, 37.0, 38.0),
                locked = true,
                cellSpreadMv = 19.0,
            )
        private val ready = CarControlsUi(enabled = true, gate = "ready")
        private val base =
            VoltAppUiState(
                drive = parked,
                car = CarUiState.demo.copy(controls = ready),
                charge = ChargeUiState(sohPct = 91.0),
                diag = DiagUiState.demo.copy(codes = emptyList()),
                settings = SettingsUiState.demo,
            )

        private val STATES =
            mapOf(
                "normal" to base,
                "fault" to
                    base.copy(
                        drive = parked.copy(tires = TirePressures(38.0, 38.0, 37.0, 31.0)),
                        diag = DiagUiState.demo,
                        car =
                            base.car.copy(
                                controls = ready.copy(lastCommand = "lock", lastOutcome = "confirmed"),
                            ),
                    ),
                "open" to
                    base.copy(
                        drive = parked.copy(locked = false),
                        car =
                            base.car.copy(
                                openings = Openings(listOf("Driver door")),
                                windowsPct = listOf(60, 0, 0, 0),
                                controls = ready.copy(gate = "not_in_park", gateDetail = "Put the car in Park first."),
                            ),
                    ),
                "unreported" to
                    base.copy(
                        drive = DriveUiState(connected = true, statusLabel = "Live", auxVolts = 12.4),
                        car = CarUiState(outsideTempC = 12.0, nowMs = CarUiState.DEMO_NOW_MS),
                        charge = ChargeUiState(),
                        diag = DiagUiState(),
                    ),
                "metric" to
                    base.copy(
                        car =
                            base.car.copy(
                                metricUnits = true,
                                seenAtMs = BodyGroup.entries.associateWith { CarUiState.DEMO_NOW_MS - 300_000L },
                            ),
                        settings = SettingsUiState.demo.copy(metricUnits = true),
                    ),
            )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun cases(): List<Array<Any>> = STATES.keys.flatMap { name -> ThemeCase.THEMES.map { arrayOf<Any>(name, it) } }
    }
}
