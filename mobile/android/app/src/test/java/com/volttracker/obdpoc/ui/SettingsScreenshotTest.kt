package com.volttracker.obdpoc.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.volttracker.obdpoc.ui.settings.SettingsScreen
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import com.volttracker.obdpoc.ui.theme.VoltTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Headless screenshot renders of the Compose Settings screen. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SettingsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    // Taller-than-viewport render so the full scrollable content is visible in
    // the before/after review images (the real screen scrolls).
    @Test
    @Config(qualifiers = "w412dp-h1850dp-420dpi")
    fun settingsScreenDemoState() {
        compose.setContent {
            VoltTheme { SettingsScreen(SettingsUiState.demo) }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/after-settings.png")
    }

    @Test
    fun settingsScreenDisconnected() {
        compose.setContent {
            VoltTheme { SettingsScreen(SettingsUiState(versionLabel = "Volt Tracker 0.33.0")) }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/after-settings-disconnected.png")
    }

    // App-updates section states: a newer build offered, and a download underway.
    @Test
    @Config(qualifiers = "w412dp-h1900dp-420dpi")
    fun settingsScreenUpdateAvailable() {
        compose.setContent {
            VoltTheme {
                SettingsScreen(
                    SettingsUiState.demo.copy(
                        updateStatusLabel = "v0.36.0 is available",
                        updateAvailableTag = "v0.36.0",
                    ),
                )
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/after-settings-update-available.png")
    }

    @Test
    @Config(qualifiers = "w412dp-h1900dp-420dpi")
    fun settingsScreenUpdateDownloading() {
        compose.setContent {
            VoltTheme {
                SettingsScreen(
                    SettingsUiState.demo.copy(
                        updateStatusLabel = "v0.36.0 is available",
                        updateAvailableTag = "v0.36.0",
                        updateDownloadPercent = 43,
                    ),
                )
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/after-settings-update-downloading.png")
    }
}
