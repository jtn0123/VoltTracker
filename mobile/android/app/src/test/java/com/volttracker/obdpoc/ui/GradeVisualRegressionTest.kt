package com.volttracker.obdpoc.ui

import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.diag.DtcCode
import com.volttracker.obdpoc.ui.drive.DrivePhase
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/** Fixed SDK, size, clock, app fonts, themes and motion; CI compares these reviewed baselines. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w412dp-h915dp-420dpi")
class GradeVisualRegressionTest(
    private val scenario: String,
    private val theme: ThemeCase,
) {
    @get:Rule val compose = createComposeRule()

    private val originalZone = TimeZone.getDefault()

    @Before fun fixedClock() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Settings.System.putString(RuntimeEnvironment.getApplication().contentResolver, Settings.System.TIME_12_24, "12")
    }

    @After fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    @Test fun capture() {
        val base =
            VoltAppUiState(
                drive = DriveUiState.demo,
                charge = ChargeUiState.demo,
                diag = DiagUiState.demo,
                settings = theme.applyTo(SettingsUiState.demo),
            )
        val failure = ConnectionFailure("Nearby devices permission is missing.", "permission", "Torque")
        val disconnectedDrive = DriveUiState(statusLabel = "Not connected")
        val unknownDiag = DiagUiState.demo.copy(codes = listOf(DtcCode("B1000")))
        val state =
            when (scenario) {
                "missing" ->
                    base.copy(
                        drive =
                            DriveUiState(
                                connected = true,
                                phase = DrivePhase.DRIVE,
                                statusLabel = "Connected",
                            ),
                        charge = ChargeUiState(connected = true),
                    )
                "recording" -> base.copy(recordingWarning = RECORDING_WARNING)
                "connection" -> base.copy(drive = disconnectedDrive, connectionFailure = failure)
                "health" -> base.copy(settings = base.settings.copy(fontScale = 1.5))
                "unknown-health" -> base.copy(diag = unknownDiag)
                "refresh" -> base.copy(historyRefreshing = true, settings = base.settings.copy(fontScale = 1.5))
                else -> base
            }
        val tab = if (scenario == "refresh") VoltTab.CHARGE else VoltTab.DRIVE
        val routes = if (scenario.endsWith("health")) listOf(VoltRoute.HEALTH) else emptyList()
        // No pulsing/infinite animations or wall-clock-dependent live data in the baseline.
        Settings.Global.putFloat(
            RuntimeEnvironment.getApplication().contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            0f,
        )
        compose.setContent { VoltApp(state, initialTab = tab, initialRoutes = routes) }
        compose.onNodeWithContentDescription("Trips").assertIsDisplayed()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000L)
        compose.onRoot().captureRoboImage("$BASELINES/$scenario-${theme.key}.png")
    }

    companion object {
        private const val RECORDING_WARNING = "Telemetry could not be saved. Check available storage."
        private const val BASELINES = "src/test/java/com/volttracker/obdpoc/ui/baselines"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun cases(): List<Array<Any>> =
            listOf("drive", "missing", "recording", "connection", "health", "unknown-health", "refresh")
                .flatMap { scenario -> ThemeCase.THEMES.map { arrayOf<Any>(scenario, it) } }
    }
}
