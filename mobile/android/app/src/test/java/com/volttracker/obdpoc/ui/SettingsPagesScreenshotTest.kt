package com.volttracker.obdpoc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.volttracker.obdpoc.ui.settings.SettingsPage
import com.volttracker.obdpoc.ui.settings.SettingsScreen
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import com.volttracker.obdpoc.ui.theme.AppearanceMode
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every Settings page in dark and light. `-ProborazziRecord` writes
 * build/outputs/roborazzi/settings-<page>-<theme>.png; every case also proves the page composes.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SettingsPagesScreenshotTest(
    private val page: SettingsPage,
    private val appearance: AppearanceMode,
    private val editing: Boolean,
) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun capture() {
        compose.setContent {
            // Paint the app canvas behind the page, as VoltApp does.
            VoltTheme(appearance = appearance) {
                Box(Modifier.fillMaxSize().background(VoltColors.bg)) { SettingsScreen(SAMPLE, initialPage = page) }
            }
        }
        // The Costs page with its inline number editor open under the home rate.
        if (editing) compose.onNodeWithText("Home electricity rate").performClick()
        val name = page.name.lowercase() + if (editing) "-editing" else ""
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/settings-$name-${appearance.key}.png")
    }

    companion object {
        private val SAMPLE = SettingsUiState.demo.copy(publicRate = 0.42, notifyPackTempHigh = true)

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}-editing={2}")
        fun cases(): List<Array<Any>> =
            (SettingsPage.entries.map { it to false } + (SettingsPage.COSTS to true)).flatMap { (page, editing) ->
                listOf(AppearanceMode.DARK, AppearanceMode.LIGHT).map { arrayOf<Any>(page, it, editing) }
            }
    }
}
