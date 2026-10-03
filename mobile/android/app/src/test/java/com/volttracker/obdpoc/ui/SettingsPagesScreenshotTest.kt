package com.volttracker.obdpoc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.volttracker.obdpoc.ui.settings.SettingsPage
import com.volttracker.obdpoc.ui.settings.SettingsScreen
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import com.volttracker.obdpoc.ui.theme.VoltColors
import com.volttracker.obdpoc.ui.theme.VoltTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every Settings page in OLED Black and Latte, plus Appearance in Saddle Leather and OLED Lime. `-ProborazziRecord` writes
 * build/outputs/roborazzi/settings-<page>-<theme>.png; every case also proves the page composes.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class SettingsPagesScreenshotTest(
    private val page: SettingsPage,
    private val theme: ThemeCase,
    private val editing: Boolean,
) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun capture() {
        compose.setContent {
            // Paint the app canvas behind the page, as VoltApp does.
            VoltTheme(theme.appearance, theme.darkStyle, theme.accent) {
                Box(Modifier.fillMaxSize().background(VoltColors.bg)) {
                    SettingsScreen(theme.applyTo(SAMPLE), initialPage = page)
                }
            }
        }
        // Costs with its number editor open under the home rate; Data with the backup passphrase step.
        if (editing) compose.onNodeWithText(EDIT_TARGETS.getValue(page)).performClick()
        val name = page.name.lowercase() + if (editing) "-editing" else ""
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/settings-$name-${theme.key}.png")
    }

    companion object {
        private val SAMPLE =
            SettingsUiState.demo.copy(
                publicRate = 0.42,
                notifyPackTempHigh = true,
                lastBackupLabel = "Last backup Sep 27, 2026, 9:40 PM · 42 trips",
            )

        private val EDIT_TARGETS = mapOf(SettingsPage.COSTS to "Home electricity rate", SettingsPage.DATA to "Back up")

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}-editing={2}")
        fun cases(): List<Array<Any>> =
            (SettingsPage.entries.map { it to false } + EDIT_TARGETS.keys.map { it to true })
                .flatMap { (page, editing) ->
                    listOf(ThemeCase.OLED, ThemeCase.LATTE).map { arrayOf<Any>(page, it, editing) }
                } +
                // Appearance in the other looks: Saddle hides the accent row; Lime shows the pick.
                listOf(ThemeCase.SADDLE, ThemeCase.OLED_LIME).map { arrayOf<Any>(SettingsPage.APPEARANCE, it, false) }
    }
}
