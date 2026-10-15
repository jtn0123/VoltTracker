package com.volttracker.obdpoc.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.diag.DtcCode
import com.volttracker.obdpoc.ui.diag.rawReadings
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.drive.Gen2Status
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Health › Freeze frame (read, and saved but not read yet), Live signals and its All readings, in
 * OLED Black, Saddle Leather and Latte. `-ProborazziRecord` writes
 * build/outputs/roborazzi/health-detail-<state>-<theme>.png; every case also proves the screen composes.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class HealthDetailScreenshotTest(
    private val stateName: String,
    private val theme: ThemeCase,
) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun capture() {
        val (route, state) = STATES.getValue(stateName)
        val themed = state.copy(settings = theme.applyTo(state.settings))
        // The full list, so the review image shows every gen 2 row.
        if (stateName == "live-signals-gen2") RuntimeEnvironment.setQualifiers("+h2400dp")
        compose.setContent {
            VoltApp(
                themed,
                initialTab = VoltTab.CAR,
                initialRoutes = listOf(VoltRoute.HEALTH, route),
            )
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/health-detail-$stateName-${theme.key}.png")
    }

    companion object {
        private val sample =
            JSONObject()
                .put("packVoltage", 359.2)
                .put("packCurrentA", -42.5)
                .put("socPct", 62.4)
                .put("displayedSocPct", 71)
                .put("speedKph", 72)
                .put("batteryTempC", 21)
                .put("motorATempC", 48)
                .put("coolantC", 88)
                .put("aux12vVoltage", 12.6)
                .put("aux12vVoltageStaleMs", 18_000)
                .put("doorLockState", "locked")
                .put("tirePressureFlKpa", 262)
                .put("evRangeKm", 48.3)
                .put("gearState", "D")
        private val base =
            VoltAppUiState(
                drive = DriveUiState.demo.copy(rawReadings = rawReadings(sample, emptySet())),
                diag = DiagUiState.demo,
                settings = SettingsUiState.demo,
            )

        private val STATES: Map<String, Pair<VoltRoute, VoltAppUiState>> =
            mapOf(
                "freeze-frame" to (VoltRoute.FREEZE_FRAME to base),
                "freeze-frame-unread" to
                    (
                        VoltRoute.FREEZE_FRAME to
                            base.copy(
                                diag =
                                    DiagUiState.demo.copy(
                                        freezeFrame = null,
                                        codes = listOf(DtcCode("P0171", status = DtcCode.STATUS_FREEZE_FRAME)),
                                    ),
                            )
                    ),
                "live-signals" to (VoltRoute.SIGNALS to base),
                // Every gen 2 read reported, plugged in and waiting on the car's schedule.
                "live-signals-gen2" to
                    (
                        VoltRoute.SIGNALS to
                            base.copy(
                                drive =
                                    base.drive.copy(
                                        hvIsolationKohm = 2_000,
                                        gen2 =
                                            Gen2Status(
                                                engineStartReason = "None",
                                                remoteStartReason = "Ignition switch",
                                                batteryConditioning = "Active heating",
                                                batteryCoolantF = 68,
                                                egrCommandedPct = 12,
                                                egrCooler1F = 194,
                                                egrCooler2F = 178,
                                                aux12vCapacityAh = 41.5,
                                                chargeState = "Waiting",
                                                chargeSystem = "Ready",
                                                chargerAcDutyPct = 72,
                                                chargerOutputDutyPct = 68,
                                                chargeStart = "Tue 23:05",
                                                remoteStartsLeft = 2,
                                            ),
                                    ),
                            )
                    ),
                "all-readings" to (VoltRoute.ALL_READINGS to base),
            )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun cases(): List<Array<Any>> = STATES.keys.flatMap { name -> ThemeCase.THEMES.map { arrayOf<Any>(name, it) } }
    }
}
