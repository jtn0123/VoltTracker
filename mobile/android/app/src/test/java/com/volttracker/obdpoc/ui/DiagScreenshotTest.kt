package com.volttracker.obdpoc.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.volttracker.obdpoc.ui.diag.DiagScreen
import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.theme.VoltTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Headless screenshot renders of the Compose Diagnostics screen. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class DiagScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    // Taller-than-viewport render so the full scrollable content is visible in
    // the before/after review images (the real screen scrolls).
    @Test
    @Config(qualifiers = "w412dp-h1350dp-420dpi")
    fun diagScreenDemoState() {
        compose.setContent {
            VoltTheme { DiagScreen(DiagUiState.demo) }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/after-diagnostics.png")
    }

    @Test
    fun diagScreenAllClear() {
        compose.setContent {
            VoltTheme {
                DiagScreen(
                    DiagUiState.demo.copy(codes = emptyList(), lastScanLabel = "Last scan 5m ago"),
                )
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/after-diagnostics-clear.png")
    }
}
