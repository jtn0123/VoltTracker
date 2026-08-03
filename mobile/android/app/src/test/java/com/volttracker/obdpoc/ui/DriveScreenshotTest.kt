package com.volttracker.obdpoc.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.volttracker.obdpoc.ui.drive.DriveMode
import com.volttracker.obdpoc.ui.drive.DriveScreen
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.theme.VoltTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Headless screenshot renders of the Compose Drive screen (Robolectric native
 * graphics + Roborazzi). PNGs land in build/outputs/roborazzi/ — the "after"
 * images for the dashboard rewrite's before/after review loop, and a smoke
 * proof that the screen composes without crashing.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class DriveScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    // Detailed density stacks charts + more-signals below the fold, so render
    // taller than the viewport to show the full scrollable content.
    @Test
    @Config(qualifiers = "w412dp-h1660dp-420dpi")
    fun driveScreenDemoState() {
        compose.setContent {
            VoltTheme { DriveScreen(DriveUiState.demo) }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/after-drive.png")
    }

    // Focus density with an active regen moment: the ⚡ chip shows and the
    // power bar swings left/blue. Default phone height — Focus fits one screen.
    @Test
    fun driveScreenFocusRegen() {
        compose.setContent {
            VoltTheme {
                DriveScreen(
                    DriveUiState.demo.copy(detailed = false, powerKw = -14.2, packAmps = -39.0),
                )
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/after-drive-focus.png")
    }

    @Test
    @Config(qualifiers = "w412dp-h1660dp-420dpi")
    fun driveScreenGasMode() {
        compose.setContent {
            VoltTheme {
                DriveScreen(
                    DriveUiState.demo.copy(mode = DriveMode.GAS, rpm = 2200, powerKw = 30.5),
                )
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/after-drive-gas.png")
    }

    @Test
    fun driveScreenDisconnected() {
        compose.setContent {
            VoltTheme { DriveScreen(DriveUiState(detailed = false)) }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/after-drive-disconnected.png")
    }
}
