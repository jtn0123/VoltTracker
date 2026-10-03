package com.volttracker.obdpoc.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.volttracker.obdpoc.ui.charge.ChargeScreen
import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.components.LocalVoltPrefs
import com.volttracker.obdpoc.ui.components.VoltPrefs
import com.volttracker.obdpoc.ui.components.VoltScreen
import com.volttracker.obdpoc.ui.drive.DriveScreen
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.settings.SettingsPage
import com.volttracker.obdpoc.ui.settings.SettingsScreen
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import com.volttracker.obdpoc.ui.theme.VoltTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The polish pass's on-screen behaviour: demo flag, Charge connect offer, Settings editors. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class PolishScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everyHeaderFlagsTheDemo() {
        compose.setContent {
            VoltTheme {
                CompositionLocalProvider(LocalVoltPrefs provides VoltPrefs(demo = true)) {
                    VoltScreen(title = "Trips", subtitle = "Live") {}
                }
            }
        }
        // Pills are set in caps.
        compose.onNodeWithText("DEMO").assertIsDisplayed()
    }

    @Test
    fun noDemoFlagForTheRealCar() {
        compose.setContent { VoltTheme { VoltScreen(title = "Trips", subtitle = "Live") {} } }
        assertEquals(0, compose.onAllNodes(hasText("DEMO")).fetchSemanticsNodes().size)
    }

    @Test
    fun chargeOffersConnectAndDemoWhenDisconnected() {
        var connects = 0
        var demos = 0
        compose.setContent {
            VoltTheme { ChargeScreen(ChargeUiState(), onConnect = { connects++ }, onStartDemo = { demos++ }) }
        }
        compose.onNodeWithText("Connect").performClick()
        compose.onNodeWithText("Demo").performClick()
        assertEquals(1, connects)
        assertEquals(1, demos)
    }

    @Test
    fun chargeHidesTheOfferWhileConnecting() {
        compose.setContent { VoltTheme { ChargeScreen(ChargeUiState(connecting = true)) } }
        assertEquals(0, compose.onAllNodes(hasText("Connect")).fetchSemanticsNodes().size)
    }

    @Test
    fun numberEditorDisablesSaveAndExplainsBadInput() {
        compose.setContent { VoltTheme { SettingsScreen(SettingsUiState(), initialPage = SettingsPage.COSTS) } }
        compose.onNodeWithText("Home electricity rate").performClick()
        compose.onNodeWithText("From $0 to $2 per kWh").assertIsDisplayed()
        compose.onNodeWithTag("settings-number-input").performTextReplacement("abc")
        compose.onNodeWithText("Enter a number").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithTag("settings-number-input").performTextReplacement("0.14")
        compose.onNodeWithText("Save").assertIsEnabled()
        assertEquals(0, compose.onAllNodes(hasText("Enter a number")).fetchSemanticsNodes().size)
    }

    @Test
    fun waitForAdapterMinutesOnlyWorkWhileWaiting() {
        compose.setContent {
            VoltTheme {
                SettingsScreen(
                    SettingsUiState(waitingForAdapter = false),
                    initialPage = SettingsPage.CONNECTION,
                )
            }
        }
        compose.onNodeWithText("10 min").assertIsNotEnabled()
    }

    @Test
    fun waitForAdapterMinutesWorkWhileWaiting() {
        compose.setContent {
            VoltTheme {
                SettingsScreen(
                    SettingsUiState(waitingForAdapter = true),
                    initialPage = SettingsPage.CONNECTION,
                )
            }
        }
        compose.onNodeWithText("10 min").assertIsEnabled()
    }

    @Test
    fun adapterCardSaysNoAdapterOnceAndUpdatesSayUpToDate() {
        compose.setContent {
            VoltTheme { SettingsScreen(SettingsUiState(updateStatusLabel = SettingsUiState.UP_TO_DATE)) }
        }
        assertEquals(1, compose.onAllNodes(hasText("No adapter", substring = true)).fetchSemanticsNodes().size)
        compose.onNodeWithText("Not connected", substring = true).assertIsDisplayed()
        compose.onNodeWithText("You're up to date").assertIsDisplayed()
    }

    @Test
    fun demoDriveHeaderSaysSampleDataNotTheAdapter() {
        compose.setContent {
            VoltTheme {
                CompositionLocalProvider(LocalVoltPrefs provides VoltPrefs(demo = true)) {
                    DriveScreen(DriveUiState.demo)
                }
            }
        }
        compose.onNodeWithText("Sample data").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("OBDLink", substring = true)).fetchSemanticsNodes().size)
    }
}
