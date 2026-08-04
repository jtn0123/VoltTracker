package com.volttracker.obdpoc.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.insights.InsightsUiState
import com.volttracker.obdpoc.ui.map.MapUiState
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the [VoltApp] shell through every tab: each bottom-nav tap must land
 * on the matching screen. This is the navigation contract the activity relies
 * on — the screens themselves are covered by the screenshot suite.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class VoltAppNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private val demoState =
        VoltAppUiState(
            drive = DriveUiState.demo,
            charge = ChargeUiState.demo,
            map = MapUiState.demo,
            insights = InsightsUiState.demo,
            diag = DiagUiState.demo,
            settings = SettingsUiState.demo,
        )

    @Test
    fun tappingEachTabShowsItsScreen() {
        compose.setContent { VoltApp(demoState) }

        compose.onNodeWithText("Map").performClick()
        compose.onNodeWithText("▶ Play").assertIsDisplayed()

        compose.onNodeWithText("Charge").performClick()
        compose.onNodeWithText("RECENT CHARGES").assertIsDisplayed()

        compose.onNodeWithText("Insights").performClick()
        compose.onNodeWithText("MI / KWH LIFETIME").assertIsDisplayed()

        compose.onNodeWithText("Diag").performClick()
        compose.onNodeWithText("Scan now").assertIsDisplayed()

        compose.onNodeWithText("Drive").performClick()
        compose.onNodeWithText("MPH").assertIsDisplayed()
    }

    @Test
    fun settingsTabWiresTheClassicDashboardAction() {
        var opened = false
        compose.setContent {
            VoltApp(
                demoState,
                initialTab = VoltTab.SETTINGS,
                onOpenClassicDashboard = { opened = true },
            )
        }

        compose
            .onNodeWithText("Open classic dashboard")
            .performScrollTo()
            .performClick()
        assertTrue(opened)
    }
}
