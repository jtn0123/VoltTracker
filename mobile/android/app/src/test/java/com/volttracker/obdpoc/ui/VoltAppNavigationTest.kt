package com.volttracker.obdpoc.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.insights.InsightsUiState
import com.volttracker.obdpoc.ui.settings.SettingChange
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import com.volttracker.obdpoc.ui.theme.AppearanceMode
import com.volttracker.obdpoc.ui.trips.TripsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the [VoltApp] shell: each of the five tabs lands on its screen, the gear opens Settings
 * from anywhere, Car opens Health, and back (button or system) unwinds the pushed routes. This is
 * the navigation contract the activity relies on — the screens are covered by the screenshot suite.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class VoltAppNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val demoState =
        VoltAppUiState(
            drive = DriveUiState.demo,
            charge = ChargeUiState.demo,
            trips = TripsUiState.demo,
            insights = InsightsUiState.demo,
            diag = DiagUiState.demo,
            settings = SettingsUiState.demo,
        )

    private fun tab(label: String) = compose.onNodeWithContentDescription(label, substring = true)

    @Test
    fun tappingEachTabShowsItsScreen() {
        compose.setContent { VoltApp(demoState) }

        tab("Trips").performClick()
        compose.onNodeWithText("ELECTRIC").assertIsDisplayed()

        tab("Charge").performClick()
        compose.onNodeWithText("RECENT SESSIONS").performScrollTo().assertIsDisplayed()

        tab("Insights").performClick()
        compose.onNodeWithText("DRIVEN ON ELECTRICITY").performScrollTo().assertIsDisplayed()

        tab("Car").performClick()
        compose.onNodeWithText("Vehicle health").performScrollTo().assertIsDisplayed()

        tab("Drive").performClick()
        compose.onNodeWithContentDescription("47 miles per hour", substring = true).assertIsDisplayed()
    }

    @Test
    fun gearOpensSettingsAndBackReturnsToTheTab() {
        compose.setContent { VoltApp(demoState, initialTab = VoltTab.INSIGHTS) }

        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("PREFERENCES").assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("DRIVEN ON ELECTRICITY").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun carOpensHealthAndSystemBackUnwindsTheStack() {
        compose.setContent { VoltApp(demoState, initialTab = VoltTab.CAR) }

        compose.onNodeWithText("Vehicle health").performScrollTo().performClick()
        compose.onNodeWithText("Health").assertIsDisplayed()

        // Settings over Health, then two system backs: Settings → Health → Car.
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("PREFERENCES").assertIsDisplayed()
        systemBack()
        compose.onNodeWithText("Health").assertIsDisplayed()
        systemBack()
        compose.onNodeWithText("Vehicle health").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun switchingTabsClearsPushedRoutes() {
        compose.setContent { VoltApp(demoState, initialTab = VoltTab.DRIVE) }

        compose.onNodeWithContentDescription("Settings").performClick()
        tab("Charge").performClick()
        compose.onNodeWithText("RECENT SESSIONS").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun settingsSubPageBackReturnsToTheIndex() {
        compose.setContent { VoltApp(demoState, initialRoutes = listOf(VoltRoute.SETTINGS)) }

        compose.onNodeWithText("Alerts").performClick()
        compose.onNodeWithText("Charging complete").assertIsDisplayed()
        systemBack()
        compose.onNodeWithText("PREFERENCES").assertIsDisplayed()
    }

    @Test
    fun carTabCarriesABadgeOnlyWhileCodesAreStored() {
        compose.setContent { VoltApp(demoState) }
        compose.onNodeWithContentDescription("Car, has warnings").assertIsDisplayed()
    }

    @Test
    fun cleanCarHasNoBadge() {
        compose.setContent { VoltApp(demoState.copy(diag = DiagUiState.demo.copy(codes = emptyList()))) }
        compose.onNodeWithContentDescription("Car").assertIsDisplayed()
    }

    @Test
    fun advancedWiresTheClassicDashboardAction() {
        var opened = false
        compose.setContent {
            VoltApp(
                demoState,
                initialRoutes = listOf(VoltRoute.SETTINGS),
                actions = VoltAppActions(onOpenClassicDashboard = { opened = true }),
            )
        }

        compose.onNodeWithText("Advanced diagnostics").performScrollTo().performClick()
        compose.onNodeWithText("Open classic dashboard").performClick()
        assertTrue(opened)
    }

    @Test
    fun carTireTestRunsOnlyWhileConnected() {
        var runs = 0
        var connected by mutableStateOf(true)
        compose.setContent {
            VoltApp(
                demoState.copy(drive = DriveUiState.demo.copy(connected = connected)),
                actions = VoltAppActions(onTireTest = { runs += 1 }),
            )
        }

        tab("Car").performClick()
        compose.onNodeWithText("Tire test").performScrollTo().performClick()
        assertEquals(1, runs)

        connected = false
        compose.onNodeWithText("Tire test").performScrollTo().performClick()
        assertEquals("no run while disconnected", 1, runs)
    }

    @Test
    fun healthOpensNativeLiveSignals() {
        compose.setContent { VoltApp(demoState, initialRoutes = listOf(VoltRoute.HEALTH)) }

        compose.onNodeWithText("Live signals").performScrollTo().performClick()
        compose.onNodeWithText("Pack voltage").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("All raw readings").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun healthTroubleshooterInvokesItsDedicatedDestination() {
        var opened = 0
        compose.setContent {
            VoltApp(
                demoState,
                initialRoutes = listOf(VoltRoute.HEALTH),
                actions = VoltAppActions(onOpenTroubleshooter = { opened += 1 }),
            )
        }
        compose.onNodeWithText("Troubleshooter").performScrollTo().performClick()
        assertEquals(1, opened)
        compose.onNodeWithText("Test connection").assertDoesNotExist()
    }

    @Test
    fun connectionNoticeRecoveryControlsDispatchAndDismiss() {
        var retries = 0
        var troubleshooting = 0
        var state by mutableStateOf(demoState.copy(connectionFailure = ConnectionFailure("Permission missing.")))
        compose.setContent {
            VoltApp(
                state,
                actions =
                    VoltAppActions(
                        onConnect = {
                            retries += 1
                            true
                        },
                        onOpenTroubleshooter = { troubleshooting += 1 },
                        onDismissConnectionFailure = { state = state.copy(connectionFailure = null) },
                    ),
            )
        }
        compose.onNodeWithText("Permission missing.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Troubleshooter").performClick()
        assertEquals(1, retries)
        assertEquals(1, troubleshooting)
        compose.onNodeWithText("Adapter").performClick()
        compose.onNodeWithText("Test connection").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithText("Permission missing.").assertDoesNotExist()
    }

    @Test
    fun demoPageStartsTheDemo() {
        var started = false
        compose.setContent {
            VoltApp(
                demoState,
                initialRoutes = listOf(VoltRoute.SETTINGS),
                actions = VoltAppActions(onStartDemo = { started = true }),
            )
        }

        compose.onNodeWithText("Demo / testing").performScrollTo().performClick()
        compose.onNodeWithText("Start demo").performClick()
        assertTrue(started)
    }

    @Test
    fun demoPageStopsARunningDemo() {
        var stopped = false
        compose.setContent {
            VoltApp(
                demoState.copy(settings = SettingsUiState.demo.copy(demoActive = true)),
                initialRoutes = listOf(VoltRoute.SETTINGS),
                actions = VoltAppActions(onStopDemo = { stopped = true }),
            )
        }

        compose.onNodeWithText("Demo / testing").performScrollTo().performClick()
        compose.onNodeWithText("Stop demo").performClick()
        assertTrue(stopped)
    }

    @Test
    fun appearanceSegmentsReportTheChosenMode() {
        var chosen: SettingChange? = null
        compose.setContent {
            VoltApp(
                demoState,
                initialRoutes = listOf(VoltRoute.SETTINGS),
                actions = VoltAppActions(onSettingChange = { chosen = it }),
            )
        }

        compose.onNodeWithText("Appearance").performClick()
        compose.onNodeWithText("Light").performClick()
        assertEquals(SettingChange.Appearance(AppearanceMode.LIGHT), chosen)
        compose.onNodeWithText("Dark").performClick()
        assertEquals(SettingChange.Appearance(AppearanceMode.DARK), chosen)
    }

    private fun systemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
}
