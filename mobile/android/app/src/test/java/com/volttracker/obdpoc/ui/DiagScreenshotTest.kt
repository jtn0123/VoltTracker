package com.volttracker.obdpoc.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.diag.DtcCode
import com.volttracker.obdpoc.ui.diag.DtcSeverity
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Car › Health (the mockups' `S-health` / `S-healthFault`): all clear, two engine codes that are
 * safe to drive on, a critical HV code, not scanned yet with nothing reported, and a scan running —
 * in OLED Black, Saddle Leather and Latte. `-ProborazziRecord` writes
 * build/outputs/roborazzi/health-<state>-<theme>.png; every case also proves the screen composes.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h1150dp-420dpi")
class DiagScreenshotTest(
    private val stateName: String,
    private val theme: ThemeCase,
) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun capture() {
        val state = STATES.getValue(stateName).let { it.copy(settings = theme.applyTo(it.settings)) }
        compose.setContent { VoltApp(state, initialTab = VoltTab.CAR, initialRoutes = listOf(VoltRoute.HEALTH)) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/health-$stateName-${theme.key}.png")
    }

    companion object {
        private val now = DiagUiState.DEMO_NOW_MS
        private val drive = DriveUiState.demoParked.copy(signalCount = 78)
        private val base =
            VoltAppUiState(
                drive = drive,
                charge = ChargeUiState(sohPct = 91.0, capacityAh = 47.3),
                diag = DiagUiState.demo.copy(codes = emptyList(), freezeFrame = null),
                settings = SettingsUiState.demo,
            )

        private val STATES =
            mapOf(
                "normal" to base,
                "fault" to base.copy(diag = DiagUiState.demo),
                "alert" to
                    base.copy(
                        diag =
                            DiagUiState.demo.copy(
                                codes =
                                    listOf(
                                        DtcCode(
                                            code = "P0AA6",
                                            description = "Hybrid battery voltage system isolation fault",
                                            category = "HV battery",
                                            severity = DtcSeverity.ALERT,
                                            firstSeenMs = now - 3_600_000L,
                                            lastSeenMs = now - 600_000L,
                                            seenCount = 2,
                                        ),
                                    ) +
                                        DiagUiState.demo.codes
                                            .orEmpty()
                                            .take(1),
                                scannedAtMs = now - 600_000L,
                                earlierCodes = listOf("P0171"),
                            ),
                    ),
                "unscanned" to
                    base.copy(
                        drive = DriveUiState(),
                        charge = ChargeUiState(),
                        diag = DiagUiState(adapterLabel = "OBDLink MX+", nowMs = now),
                    ),
                "scanning" to
                    base.copy(
                        diag =
                            DiagUiState.demo.copy(
                                codes = emptyList(),
                                freezeFrame = null,
                                busyLabel = "Reading stored diagnostic trouble codes...",
                            ),
                    ),
            )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun cases(): List<Array<Any>> = STATES.keys.flatMap { name -> ThemeCase.THEMES.map { arrayOf<Any>(name, it) } }
    }
}
