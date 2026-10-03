package com.volttracker.obdpoc.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.volttracker.obdpoc.ui.settings.SettingChange
import com.volttracker.obdpoc.ui.settings.SettingsActions
import com.volttracker.obdpoc.ui.settings.SettingsPage
import com.volttracker.obdpoc.ui.settings.SettingsScreen
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import com.volttracker.obdpoc.ui.theme.AppearanceMode
import com.volttracker.obdpoc.ui.theme.DarkStyle
import com.volttracker.obdpoc.ui.theme.OledAccent
import com.volttracker.obdpoc.ui.theme.VoltTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Settings → Appearance: mode, dark style, and the OLED-only accent swatches. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h1200dp-420dpi")
class AppearancePageTest {
    @get:Rule
    val compose = createComposeRule()

    private val changes = mutableListOf<SettingChange>()

    private fun show(state: SettingsUiState) {
        compose.setContent {
            VoltTheme(state.appearance, state.darkStyle, state.accent) {
                SettingsScreen(
                    state,
                    initialPage = SettingsPage.APPEARANCE,
                    actions = SettingsActions(onChange = { changes += it }),
                )
            }
        }
    }

    @Test
    fun modeOffersSystemDarkLightInThatOrder() {
        assertEquals(listOf("System", "Dark", "Light"), AppearanceMode.entries.map { it.label })
        show(SettingsUiState.demo)
        compose.onNodeWithText("Mode").assertExists()
        compose.onNodeWithText("Dark").performClick()
        assertEquals(SettingChange.Appearance(AppearanceMode.DARK), changes.last())
    }

    @Test
    fun oledShowsEveryAccentSwatchAndReportsTheChoice() {
        show(SettingsUiState.demo.copy(darkStyle = DarkStyle.OLED, accent = OledAccent.BLUE))
        compose.onNodeWithText("Accent").assertExists()
        OledAccent.entries.forEach { compose.onNodeWithContentDescription("${it.label} accent").assertExists() }
        compose.onNodeWithContentDescription("Blue accent").assertIsSelected()
        compose.onNodeWithContentDescription("Volt Lime accent").performClick()
        assertEquals(SettingChange.Accent(OledAccent.LIME), changes.last())
        compose.onNodeWithText("Saddle Leather").performClick()
        assertEquals(SettingChange.DarkTheme(DarkStyle.SADDLE), changes.last())
    }

    @Test
    fun saddleHidesTheAccentRow() {
        show(SettingsUiState.demo.copy(darkStyle = DarkStyle.SADDLE))
        compose.onAllNodesWithText("Accent").assertCountEquals(0)
        compose.onAllNodes(hasContentDescription("Cyan accent")).assertCountEquals(0)
        compose.onNodeWithText("OLED Black").performClick()
        assertEquals(SettingChange.DarkTheme(DarkStyle.OLED), changes.last())
    }

    @Test
    fun lightModeHidesTheAccentAndSystemExplainsWhenItApplies() {
        show(SettingsUiState.demo.copy(appearance = AppearanceMode.LIGHT, darkStyle = DarkStyle.OLED))
        compose.onAllNodesWithText("Accent").assertCountEquals(0)
        compose.onAllNodes(hasContentDescription("Cyan accent")).assertCountEquals(0)
    }

    @Test
    fun systemModeLabelsTheAccentAsTheDarkModeOne() {
        show(SettingsUiState.demo.copy(appearance = AppearanceMode.SYSTEM, darkStyle = DarkStyle.OLED))
        compose.onNodeWithText("Cyan · used when your phone is dark").assertExists()
    }

    @Test
    fun choicesAnnounceAsRadioButtonsAndTogglesAsSwitches() {
        show(SettingsUiState.demo.copy(appearance = AppearanceMode.DARK))
        compose
            .onNode(hasText("Dark") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsSelected()
        compose
            .onNode(hasText("High contrast") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOff()
            .performClick()
        assertEquals(SettingChange.HighContrast(true), changes.last())
    }
}
