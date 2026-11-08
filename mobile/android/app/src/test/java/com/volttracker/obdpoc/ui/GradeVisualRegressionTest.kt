package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.diag.DtcCode
import com.volttracker.obdpoc.ui.drive.DrivePhase
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drive, Health and Charge states on the fixed [NativeVisualRule] fixture; CI compares these reviewed
 * baselines. The other launcher tabs are in [LauncherTabsVisualRegressionTest].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NativeVisualRule.SDK], qualifiers = NativeVisualRule.QUALIFIERS)
class GradeVisualRegressionTest(
    private val scenario: String,
    private val theme: ThemeCase,
) {
    @get:Rule val visual = NativeVisualRule()

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
        visual.capture("$scenario-${theme.key}", state, tab, routes)
    }

    companion object {
        private const val RECORDING_WARNING = "Telemetry could not be saved. Check available storage."

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun cases(): List<Array<Any>> =
            listOf("drive", "missing", "recording", "connection", "health", "unknown-health", "refresh")
                .flatMap { scenario -> ThemeCase.THEMES.map { arrayOf<Any>(scenario, it) } }
    }
}
