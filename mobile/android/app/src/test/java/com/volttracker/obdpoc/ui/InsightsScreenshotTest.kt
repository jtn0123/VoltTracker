package com.volttracker.obdpoc.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.volttracker.obdpoc.ui.insights.InsightsScreen
import com.volttracker.obdpoc.ui.insights.InsightsUiState
import com.volttracker.obdpoc.ui.theme.VoltTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Headless screenshot renders of the Compose Insights screen. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class InsightsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    // Taller-than-viewport render so the full scrollable content is visible in
    // the before/after review images (the real screen scrolls).
    @Test
    @Config(qualifiers = "w412dp-h1180dp-420dpi")
    fun insightsScreenDemoState() {
        compose.setContent {
            VoltTheme { InsightsScreen(InsightsUiState.demo) }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/after-insights.png")
    }

    @Test
    fun insightsScreenEmptyState() {
        compose.setContent {
            VoltTheme { InsightsScreen(InsightsUiState()) }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/after-insights-empty.png")
    }
}
