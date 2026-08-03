package com.volttracker.obdpoc.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.volttracker.obdpoc.ui.components.SignedBars
import com.volttracker.obdpoc.ui.theme.VoltTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Focused renders of chart components' edge cases that the full-screen
 * screenshot suite doesn't reach.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class VoltChartsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    // A single-sample series is the newest sample, so it must render at full
    // opacity — not the faded tail alpha of a longer series.
    @Test
    fun signedBarsSingletonSeries() {
        compose.setContent {
            VoltTheme {
                SignedBars(
                    values = listOf(21.4f),
                    modifier =
                        Modifier
                            .height(64.dp)
                            .padding(8.dp),
                )
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chart-signedbars-singleton.png")
    }
}
