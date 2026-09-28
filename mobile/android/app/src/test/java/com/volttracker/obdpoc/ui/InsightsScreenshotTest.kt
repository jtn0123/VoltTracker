package com.volttracker.obdpoc.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.insights.InsightsPeriod
import com.volttracker.obdpoc.ui.insights.InsightsUiState
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import com.volttracker.obdpoc.ui.trips.TripsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Insights tab over the demo's month (the mockups' `S-insights-<theme>.png`), its week and
 * year, a month with no costs set / no speed data / no drifting cell, and nothing logged — in
 * OLED Black, Saddle Leather and Latte. `-ProborazziRecord` writes
 * build/outputs/roborazzi/insights-<state>-<theme>.png; every case also proves the screen composes.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h1000dp-420dpi")
class InsightsScreenshotTest(
    private val stateName: String,
    private val theme: ThemeCase,
) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun capture() {
        val state =
            VoltAppUiState(insights = STATES.getValue(stateName), settings = theme.applyTo(SettingsUiState.demo))
        compose.setContent { VoltApp(state, initialTab = VoltTab.INSIGHTS) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/insights-$stateName-${theme.key}.png")
    }

    companion object {
        private val demo = InsightsUiState.demo

        private val STATES =
            mapOf(
                "month" to demo,
                "week" to demo.copy(period = InsightsPeriod.WEEK),
                "year" to demo.copy(period = InsightsPeriod.YEAR),
                "bare" to demo.copy(gasMpg = null, speedEfficiency = emptyList(), cellDrift = null),
                "empty" to InsightsUiState(nowMs = TripsUiState.DEMO_NOW_MS),
            )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun cases(): List<Array<Any>> = STATES.keys.flatMap { name -> ThemeCase.THEMES.map { arrayOf<Any>(name, it) } }
    }
}
