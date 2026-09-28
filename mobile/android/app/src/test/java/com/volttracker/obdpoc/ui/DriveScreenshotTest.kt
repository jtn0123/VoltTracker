package com.volttracker.obdpoc.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import com.volttracker.obdpoc.ui.theme.AppearanceMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Drive tab in every ring state (electric, regen, gas, parked, charging, not connected), in
 * Focus (Direction A) and Detailed (Direction C), dark and light — the same matrix as the
 * mockups' `A-/C-<state>-<theme>.png`. `-ProborazziRecord` writes
 * build/outputs/roborazzi/drive-<view>-<state>-<theme>.png; every case also proves the screen
 * composes.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class DriveScreenshotTest(
    private val view: String,
    private val stateName: String,
    private val appearance: AppearanceMode,
) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun capture() {
        val drive = STATES.getValue(stateName.removeSuffix(FLOW)).copy(detailed = view == "C")
        val settings = SettingsUiState.demo.copy(appearance = appearance, driveEnergyFlow = stateName.endsWith(FLOW))
        val state = VoltAppUiState(drive = drive, settings = settings)
        // The flow card sits below the fold on a phone: render those cases tall enough to show it.
        if (settings.driveEnergyFlow) RuntimeEnvironment.setQualifiers("+h1120dp")
        compose.setContent { VoltApp(state, initialTab = VoltTab.DRIVE) }
        compose
            .onRoot()
            .captureRoboImage("build/outputs/roborazzi/drive-$view-$stateName-${appearance.key}.png")
    }

    companion object {
        /** A Focus case with the optional energy-flow card switched on. */
        private const val FLOW = "-flow"
        private val STATES =
            mapOf(
                "ev" to DriveUiState.demo,
                "regen" to DriveUiState.demoRegen,
                "gas" to DriveUiState.demoGas,
                "parked" to DriveUiState.demoParked,
                "charging" to DriveUiState.demoCharging,
                "offline" to DriveUiState(),
            )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}-{2}")
        fun cases(): List<Array<Any>> =
            (
                listOf("A", "C").flatMap { view -> STATES.keys.map { view to it } } +
                    listOf("A" to "ev$FLOW", "A" to "charging$FLOW")
            ).flatMap { (view, name) ->
                listOf(AppearanceMode.DARK, AppearanceMode.LIGHT).map { arrayOf<Any>(view, name, it) }
            }
    }
}
