package com.volttracker.obdpoc.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.volttracker.obdpoc.AppPrefs
import com.volttracker.obdpoc.ui.drive.DriveScreen
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.settings.SettingChange
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import com.volttracker.obdpoc.ui.theme.AppearancePrefs
import com.volttracker.obdpoc.ui.theme.VoltTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Drive-tab behaviour: the Focus/Detailed toggle, the energy-flow card, the engine notice, connect offers. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class DriveScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun toggleSwitchesBetweenFocusAndDetailedAndReportsTheChoice() {
        val chosen = mutableListOf<Boolean>()
        compose.setContent { VoltTheme { DriveScreen(DriveUiState.demo, onSetDetailed = { chosen += it }) } }

        compose.onNodeWithText("RANGE").assertIsDisplayed()
        compose.onNodeWithContentDescription("Detailed view").performClick()
        compose.onNodeWithText("TEMPERATURES").assertIsDisplayed()
        compose.onNodeWithContentDescription("Focus view").performClick()
        compose.onNodeWithText("RANGE").assertIsDisplayed()
        assertEquals(listOf(true, false), chosen)
    }

    @Test
    fun energyFlowCardIsOptional() {
        var show by mutableStateOf(true)
        compose.setContent { VoltTheme { DriveScreen(DriveUiState.demo, showEnergyFlow = show) } }
        compose.onNodeWithContentDescription("Energy flow: Battery → drive unit").performScrollTo().assertIsDisplayed()
        show = false
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithContentDescriptionCount("Energy flow: Battery → drive unit"))
    }

    @Test
    fun engineNoticeAppearsWhenTheRangeExtenderStartsMidDrive() {
        var state by mutableStateOf(DriveUiState.demo)
        compose.mainClock.autoAdvance = false
        compose.setContent { VoltTheme { DriveScreen(state) } }
        compose.mainClock.advanceTimeBy(100)
        assertEquals(0, compose.onAllNodesWithTextCount("Engine on · range extender"))

        state = DriveUiState.demoGas
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Engine on · range extender").assertIsDisplayed()
        compose.onNodeWithText("Battery reached reserve. Still driving on electric motors.").assertIsDisplayed()

        compose.mainClock.advanceTimeBy(5_000)
        assertEquals(0, compose.onAllNodesWithTextCount("Engine on · range extender"))
    }

    @Test
    fun engineNoticeDoesNotBlameTheBatteryWhenItIsNotSpent() {
        compose.setContent {
            VoltTheme {
                DriveScreen(
                    DriveUiState.demoGas.copy(evRangeMiles = 20.0, displayedSocPercent = 50.0),
                    initialToast = true,
                )
            }
        }
        compose.onNodeWithText("The car started the engine for extra power or heat.").assertIsDisplayed()
    }

    @Test
    fun offlineOffersConnectAndDemo() {
        var connect = 0
        var demo = 0
        compose.setContent {
            VoltTheme {
                DriveScreen(
                    DriveUiState(),
                    onConnect = { connect++ },
                    onStartDemo = { demo++ },
                )
            }
        }
        compose.onNodeWithText("Connect to see your Volt live").assertIsDisplayed()
        compose.onNodeWithText("Connect").performClick()
        compose.onNodeWithText("Demo").performClick()
        assertEquals(1, connect)
        assertEquals(1, demo)
    }

    @Test
    fun noConnectOffersMidHandshake() {
        compose.setContent { VoltTheme { DriveScreen(DriveUiState(connecting = true)) } }
        assertEquals(0, compose.onAllNodesWithTextCount("Connect"))
    }

    @Test
    fun parkedAndChargingStatesRenderTheirCenters() {
        var state by mutableStateOf(DriveUiState.demoParked)
        compose.setContent { VoltTheme { DriveScreen(state) } }
        compose.onNodeWithText("26 mi electric range").assertIsDisplayed()
        compose.onNodeWithText("LAST DRIVE").assertIsDisplayed()
        state = DriveUiState.demoCharging
        compose.waitForIdle()
        compose.onNodeWithText("ADDED").assertIsDisplayed()
        compose.onNodeWithText("CHARGER").assertIsDisplayed()
    }

    @Test
    fun energyFlowSettingTogglesThroughTheAppearancePage() {
        val chosen = mutableListOf<SettingChange>()
        compose.setContent {
            VoltApp(
                VoltAppUiState(drive = DriveUiState.demo, settings = SettingsUiState.demo.copy(driveEnergyFlow = true)),
                initialRoutes = listOf(VoltRoute.SETTINGS),
                actions = VoltAppActions(onSettingChange = { chosen += it }),
            )
        }
        compose.onNodeWithText("Appearance").performClick()
        compose.onNodeWithText("Energy flow on Drive").performScrollTo().performClick()
        assertEquals(listOf<SettingChange>(SettingChange.DriveEnergyFlow(false)), chosen)
    }

    @Test
    fun driveViewPrefsRoundTripThroughTheSharedPrefsFile() {
        val prefs =
            ApplicationProvider
                .getApplicationContext<Context>()
                .getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
        // Defaults: Focus, with the energy-flow card opt-in.
        assertFalse(AppearancePrefs.readDriveDetailed(prefs))
        assertFalse(AppearancePrefs.readDriveEnergyFlow(prefs))
        AppearancePrefs.writeDriveDetailed(prefs, true)
        AppearancePrefs.writeDriveEnergyFlow(prefs, true)
        assertTrue(AppearancePrefs.readDriveDetailed(prefs))
        assertTrue(AppearancePrefs.readDriveEnergyFlow(prefs))
        assertTrue(prefs.getBoolean(AppearancePrefs.KEY_DRIVE_DETAILED, false))
        assertTrue(prefs.getBoolean(AppearancePrefs.KEY_DRIVE_ENERGY_FLOW, false))
    }

    private fun ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(hasText(text)).fetchSemanticsNodes().size

    private fun ComposeContentTestRule.onAllNodesWithContentDescriptionCount(desc: String): Int =
        onAllNodes(hasContentDescription(desc)).fetchSemanticsNodes().size
}
