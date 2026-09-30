package com.volttracker.obdpoc.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.insights.InsightsPeriod
import com.volttracker.obdpoc.ui.insights.InsightsUiState
import com.volttracker.obdpoc.ui.trips.TripExport
import com.volttracker.obdpoc.ui.trips.TripsDemo
import com.volttracker.obdpoc.ui.trips.TripsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Tapping a drive or a charge opens its receipt, which shares and exports; Insights and Health speak plainly. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ReceiptScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val shared = mutableListOf<Pair<String, String>>()
    private val exports = mutableListOf<TripExport>()

    private fun show(
        start: VoltAppUiState,
        tab: VoltTab,
        routes: List<VoltRoute> = emptyList(),
    ) {
        compose.setContent {
            var state by remember { mutableStateOf(start) }
            VoltApp(
                state,
                initialTab = tab,
                initialRoutes = routes,
                actions =
                    VoltAppActions(
                        // As the host does: the tapped drive becomes the selected one.
                        onSelectTrip = { key -> state = state.copy(trips = state.trips.copy(selectedKey = key)) },
                        onShareText = { subject, text -> shared += subject to text },
                        onExportTrip = { exports += it },
                    ),
            )
        }
    }

    @Test
    fun tappingADriveOpensItsReceiptAndBackReturnsToTheList() {
        show(VoltAppUiState(trips = TripsUiState.demo.copy(selectedKey = "demo:1")), VoltTab.TRIPS)
        compose.onNodeWithText("Tahoe weekend").performScrollTo().performClick()
        // The receipt's title (the map chip names the drive too).
        compose.onAllNodes(hasText("Tahoe weekend")).onFirst().assertIsDisplayed()
        compose.onNodeWithContentDescription("Gas used", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Demo drives can't be exported").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Share").performScrollTo().performClick()
        assertEquals("Tahoe weekend", shared.single().first)
        assertTrue(shared.single().second.contains("On gas: 145.5 mi · 79%"))
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Trips").assertIsDisplayed()
    }

    @Test
    fun aRealDriveReceiptExportsItsRoute() {
        val trips = TripsUiState.demo.copy(exportable = true, homeRate = 0.0)
        show(VoltAppUiState(trips = trips), VoltTab.TRIPS, listOf(VoltRoute.TRIP))
        compose.onNodeWithText("Export GPX").performScrollTo().performClick()
        compose.onNodeWithText("Export CSV").performScrollTo().performClick()
        assertEquals(
            listOf(
                TripExport.One(TripsDemo.MIXED_KEY, TripExport.GPX),
                TripExport.One(TripsDemo.MIXED_KEY, TripExport.CSV),
            ),
            exports,
        )
    }

    @Test
    fun aMissingDriveSaysSo() {
        show(VoltAppUiState(trips = TripsUiState()), VoltTab.TRIPS, listOf(VoltRoute.TRIP))
        compose.onNodeWithText("This drive isn't available").assertIsDisplayed()
    }

    @Test
    fun tappingAChargeOpensItsReceipt() {
        val charge = ChargeUiState.demo.copy(charging = false)
        show(VoltAppUiState(charge = charge, trips = TripsUiState.demo), VoltTab.CHARGE)
        compose
            .onAllNodes(hasText("Level 2 · 24% → 91%"))
            .onFirst()
            .performScrollTo()
            .performClick()
        compose.onNodeWithText("Level 2 charge").assertIsDisplayed()
        compose.onNodeWithContentDescription("Energy added", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Share").performScrollTo().performClick()
        assertEquals("Level 2 charge", shared.single().first)
        assertTrue(shared.single().second.contains("Battery: 24% → 91% (+67)"))
    }

    @Test
    fun aChargeWithoutRatesPointsToSettings() {
        val charge = ChargeUiState.demo.copy(charging = false, homeRate = 0.0)
        show(VoltAppUiState(charge = charge), VoltTab.CHARGE)
        compose
            .onAllNodes(hasText("Level 2 · 24% → 91%"))
            .onFirst()
            .performScrollTo()
            .performClick()
        compose
            .onNodeWithText("Set your electricity rate in Settings → Costs & rates to see what this charge cost.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun insightsOpenWithThePeriodInPlainWords() {
        // The year, not the month: in UTC the demo's "now" is already May 1, a month with no drives yet.
        show(VoltAppUiState(insights = InsightsUiState.demo.copy(period = InsightsPeriod.YEAR)), VoltTab.INSIGHTS)
        compose.onNodeWithText("In short", ignoreCase = true).assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasText("You drove", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun healthShowsTheBatteryTrend() {
        show(VoltAppUiState(diag = DiagUiState.demo), VoltTab.CAR, listOf(VoltRoute.HEALTH))
        compose
            .onNodeWithContentDescription("Battery health trend", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }
}
