package com.volttracker.obdpoc.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Charge tab charging (the mockups' `S-charge-<theme>.png`), plugged out with history, and
 * with no adapter or history yet — in OLED Black, Saddle Leather and Latte. `-ProborazziRecord` writes
 * build/outputs/roborazzi/charge-<state>-<theme>.png; every case also proves the screen composes.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ChargeScreenshotTest(
    private val stateName: String,
    private val theme: ThemeCase,
) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun capture() {
        val state =
            VoltAppUiState(
                charge = STATES.getValue(stateName),
                settings = theme.applyTo(SettingsUiState.demo),
            )
        // The full page, so the review images show every card (the real screen scrolls).
        if (stateName in setOf("charging", "metric")) RuntimeEnvironment.setQualifiers("+h1240dp")
        compose.setContent { VoltApp(state, initialTab = VoltTab.CHARGE) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/charge-$stateName-${theme.key}.png")
    }

    companion object {
        private val STATES =
            mapOf(
                "charging" to ChargeUiState.demo,
                "idle" to
                    ChargeUiState.demo.copy(
                        charging = false,
                        chargeKw = 0.0,
                        fromSoc = null,
                        startedAtMs = null,
                        socPoints = emptyList(),
                        addedKwh = 0.0,
                        statusLabel = "Live",
                    ),
                "empty" to ChargeUiState(),
                "loading" to ChargeUiState(history = HistoryLoad.LOADING),
                "metric" to ChargeUiState.demo.copy(metricUnits = true, targetSoc = 80),
            )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun cases(): List<Array<Any>> =
            STATES.keys.flatMap { name ->
                ThemeCase.THEMES.map { arrayOf<Any>(name, it) }
            }
    }
}
