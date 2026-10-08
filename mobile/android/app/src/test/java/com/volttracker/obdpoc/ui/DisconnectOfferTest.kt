package com.volttracker.obdpoc.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.volttracker.obdpoc.GuidedCarTestState
import com.volttracker.obdpoc.GuidedTestStatus
import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The on-screen Disconnect, decided once for every tab: offered while a live session is parked or
 * charging, never mid-drive, never while a guided test holds the session (its live data paused, the
 * last sample can still read parked), never in demo.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class DisconnectOfferTest {
    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(
        VoltAppUiState(drive = DriveUiState.demoParked, charge = ChargeUiState.demo, settings = SettingsUiState.demo),
    )
    private var disconnects = 0

    @After
    fun noGuidedTest() = GuidedCarTestState.publish(GuidedTestStatus())

    private fun show(tab: VoltTab) =
        compose.setContent {
            VoltApp(state, initialTab = tab, actions = VoltAppActions(onDisconnect = { disconnects++ }))
        }

    private fun offered(): Boolean {
        compose.waitForIdle()
        return compose.onAllNodes(hasText("Disconnect")).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun aParkedSessionOffersItAndItEndsTheSession() {
        show(VoltTab.DRIVE)

        compose.onNodeWithText("Disconnect").performClick()

        assertEquals(1, disconnects)
    }

    @Test
    fun chargeOffersItParkedButNotMidDrive() {
        show(VoltTab.CHARGE)
        assertTrue(offered())

        state = state.copy(drive = DriveUiState.demo)

        assertFalse("a stray tap mid-drive can't end it", offered())
    }

    @Test
    fun aGuidedTestHidesItOnDriveWhileTheLastSampleStillReadsParked() {
        show(VoltTab.DRIVE)
        assertTrue(offered())

        GuidedCarTestState.publish(GuidedTestStatus(running = true))

        assertFalse(offered())
    }

    @Test
    fun aGuidedTestHidesItOnCar() {
        GuidedCarTestState.publish(GuidedTestStatus(running = true))
        show(VoltTab.CAR)

        assertFalse(offered())
    }

    @Test
    fun theDemoOffersNone() {
        state = state.copy(settings = SettingsUiState.demo.copy(demoActive = true))
        show(VoltTab.DRIVE)

        assertFalse(offered())
    }
}
