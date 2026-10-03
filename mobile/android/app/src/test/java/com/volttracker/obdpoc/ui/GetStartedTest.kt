package com.volttracker.obdpoc.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.volttracker.obdpoc.ui.drive.DriveScreen
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.drive.LastDrive
import com.volttracker.obdpoc.ui.drive.isFirstRun
import com.volttracker.obdpoc.ui.theme.VoltTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GetStartedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun onlyAFreshInstallCountsAsFirstRun() {
        val fresh = DriveUiState()
        assertTrue(isFirstRun(fresh, setupNeeded = true, hasTrips = false))
        // Until the host has checked for a remembered adapter, assume a returning driver.
        assertFalse(isFirstRun(fresh, setupNeeded = false, hasTrips = false))
        assertFalse(isFirstRun(fresh, setupNeeded = true, hasTrips = true))
        assertFalse(isFirstRun(fresh.copy(connecting = true), setupNeeded = true, hasTrips = false))
        assertFalse(isFirstRun(DriveUiState.demo, setupNeeded = true, hasTrips = false))
        assertFalse(
            isFirstRun(fresh.copy(lastDrive = LastDrive(3.0, 4.0, 1L)), setupNeeded = true, hasTrips = false),
        )
    }

    @Test
    fun firstRunShowsTheSetupStepsInsteadOfEmptyCards() {
        compose.setContent { VoltTheme { DriveScreen(DriveUiState(), firstRun = true) } }
        compose.onNodeWithText("GET SET UP").performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithText(
                "No car handy? Tap Demo to look around with sample data.",
            ).performScrollTo()
            .assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("RANGE").fetchSemanticsNodes().size)
    }
}
