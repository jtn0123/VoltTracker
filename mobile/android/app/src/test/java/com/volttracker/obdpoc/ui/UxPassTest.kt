package com.volttracker.obdpoc.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.insights.InsightsPeriod
import com.volttracker.obdpoc.ui.insights.InsightsUiState
import com.volttracker.obdpoc.ui.trips.TripsUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The UX pass: no empty screen is a dead end (each offers Connect, the demo, a retry or a wider
 * period), every live screen's header speaks the same connection line, and a pull down re-reads
 * the saved history.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class UxPassTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var connects = 0
    private var demos = 0
    private var refreshes = 0
    private var period: InsightsPeriod? = null

    private val actions =
        VoltAppActions(
            onConnect = {
                connects++
                true
            },
            onStartDemo = { demos++ },
            onRefresh = { refreshes++ },
            onInsightsPeriod = { period = it },
        )

    private fun show(
        state: VoltAppUiState,
        tab: VoltTab = VoltTab.DRIVE,
        routes: List<VoltRoute> = emptyList(),
    ) = compose.setContent { VoltApp(state, initialTab = tab, initialRoutes = routes, actions = actions) }

    private val noCar = VoltAppUiState(drive = DriveUiState(statusLabel = "Not connected"))

    @Test
    fun anEmptyTripsTabOffersConnectAndTheDemo() {
        show(noCar, VoltTab.TRIPS)
        compose.onNodeWithText("Connect ›").performClick()
        compose.onNodeWithText("Try the demo ›").performClick()
        assertEquals(1, connects)
        assertEquals(1, demos)
    }

    @Test
    fun anEmptyTripsTabOffersNothingWhileADriveIsBeingLogged() {
        show(noCar.copy(trips = TripsUiState(connected = true)), VoltTab.TRIPS)
        compose.onNodeWithText("No drives logged yet").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("Connect ›")).fetchSemanticsNodes().size)
    }

    @Test
    fun aFailedReadOffersARetry() {
        show(noCar.copy(trips = TripsUiState(history = HistoryLoad.FAILED)), VoltTab.TRIPS)
        compose.onNodeWithText("Try again ›").performClick()
        assertEquals(1, refreshes)
    }

    @Test
    fun anEmptyInsightsPeriodOffersAllTime() {
        show(noCar, VoltTab.INSIGHTS)
        compose.onNodeWithText("Show all time ›").performClick()
        assertEquals(InsightsPeriod.ALL, period)
    }

    @Test
    fun anEmptyAllTimeOffersTheDemoInstead() {
        show(noCar.copy(insights = InsightsUiState(period = InsightsPeriod.ALL)), VoltTab.INSIGHTS)
        compose.onNodeWithText("Try the demo ›").performClick()
        assertEquals(1, demos)
    }

    @Test
    fun theCarTabOffersConnectOffTheLinkAndSaysSoInItsHeader() {
        show(noCar, VoltTab.CAR)
        compose.onNodeWithText("Not connected").assertIsDisplayed()
        compose.onNodeWithText("Connect").performClick()
        compose.onNodeWithText("Demo").performClick()
        assertEquals(1, connects)
        assertEquals(1, demos)
    }

    @Test
    fun liveSignalsOffersConnectOffTheLink() {
        show(noCar, VoltTab.CAR, listOf(VoltRoute.HEALTH, VoltRoute.SIGNALS))
        compose.onNodeWithText("Connect ›").performClick()
        assertEquals(1, connects)
    }

    @Test
    fun connectWithNoAdapterChosenOpensThePicker() {
        compose.setContent {
            VoltApp(noCar, initialTab = VoltTab.TRIPS, actions = actions.copy(onConnect = { false }))
        }
        compose.onNodeWithText("Connect ›").performClick()
        compose.onNodeWithText("Auto-connect").assertIsDisplayed()
    }

    @Test
    fun healthAndCarSpeakDrivesConnectionLine() {
        val live = DriveUiState.demo
        show(
            VoltAppUiState(drive = live, diag = DiagUiState.demo.copy(connected = true)),
            VoltTab.CAR,
        )
        val line = "Live · ${live.adapterLabel}"
        compose.onNodeWithText(line).assertIsDisplayed()
        compose.onNodeWithText("Vehicle health").performClick()
        compose.onNodeWithText(line).assertIsDisplayed()
    }

    @Test
    fun aHeaderDotSaysWhatItMeansWhereTheSubtitleDoesNot() {
        show(noCar.copy(trips = TripsUiState(connecting = true)), VoltTab.TRIPS)
        compose.onNodeWithContentDescription("Connecting").assertIsDisplayed()
    }

    @Test
    fun pullingDownRereadsTheHistory() {
        show(noCar.copy(trips = TripsUiState.demo), VoltTab.TRIPS)
        compose.onRoot().performTouchInput { swipeDown(startY = height * 0.3f, endY = height * 0.9f) }
        compose.waitForIdle()
        assertEquals(1, refreshes)
    }
}
