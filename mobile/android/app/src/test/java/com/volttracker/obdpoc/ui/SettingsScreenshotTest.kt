package com.volttracker.obdpoc.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.volttracker.obdpoc.ui.settings.SettingsPage
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

    @Test
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

    // App-updates page states: a newer build offered, and a download underway.
    @Test
    fun settingsScreenUpdateAvailable() {
        compose.setContent {
            VoltTheme {
                SettingsScreen(
                    SettingsUiState.demo.copy(
                        updateStatusLabel = "v0.36.0 is available",
                        updateAvailableTag = "v0.36.0",
                    ),
                    initialPage = SettingsPage.UPDATES,
                )
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/after-settings-update-available.png")
    }

    @Test
    fun settingsScreenUpdateDownloading() {
        compose.setContent {
            VoltTheme {
                SettingsScreen(
                    SettingsUiState.demo.copy(
                        updateStatusLabel = "v0.36.0 is available",
                        updateAvailableTag = "v0.36.0",
                        updateDownloadPercent = 43,
                    ),
                    initialPage = SettingsPage.UPDATES,
                )
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/after-settings-update-downloading.png")
    }

    // Every detail page composes (each holds settings moved off the former single page).
    @Test
    @Config(qualifiers = "w412dp-h1200dp-420dpi")
    fun everySettingsPageRenders() {
        var page by mutableStateOf(SettingsPage.MAIN)
        compose.setContent {
            VoltTheme { key(page) { SettingsScreen(SettingsUiState.demo, initialPage = page) } }
        }
        SettingsPage.entries.forEach {
            page = it
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/after-settings-${it.name.lowercase()}.png")
        }
    }
}
