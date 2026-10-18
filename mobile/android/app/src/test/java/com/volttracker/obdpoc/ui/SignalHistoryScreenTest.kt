package com.volttracker.obdpoc.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.diag.SignalHistoryScreen
import com.volttracker.obdpoc.ui.diag.SignalHistoryUiState
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.theme.VoltTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h915dp-420dpi")
class SignalHistoryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun recordedSignalsAreReachableOfflineAndExportHasARealAction() {
        val history = SignalHistoryUiState.demo().copy(demo = false)
        var loads = 0
        var exports = 0
        compose.setContent {
            VoltApp(
                VoltAppUiState(drive = DriveUiState(connected = false), signalHistory = history),
                actions = VoltAppActions(onLoadSignalHistory = { loads++ }, onExportSignals = { exports++ }),
                initialTab = VoltTab.CAR,
                initialRoutes = listOf(VoltRoute.HEALTH, VoltRoute.SIGNALS),
            )
        }
        compose.onNodeWithText("Signal history & CSV").performClick()
        compose.onNodeWithText("Signal history").assertIsDisplayed()
        compose.onNode(hasContentDescription("EGR cooler 1 over the recording", substring = true)).assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/signal-history-imperial.png")
        compose.onNodeWithText("Export diagnostic CSV").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, loads)
            assertEquals(1, exports)
        }
    }

    @Test fun demoChartAndSignalPickerRespectUnitsAndDisableExport() {
        compose.setContent {
            VoltTheme { SignalHistoryScreen(SignalHistoryUiState.demo(), metric = true, demo = true) }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/signal-history-metric.png")
        compose.onNodeWithText("EGR cooler 1").performClick()
        compose.onNodeWithText("Adapter voltage").performScrollTo().performClick()
        compose.onNodeWithText("Low 13.9 · high 13.9").assertIsDisplayed()
        compose.onNodeWithText("Export diagnostic CSV").performScrollTo().assertIsNotEnabled()
    }

    @Test fun missingHistoryShowsAnEmptyStateAndAllowsRetry() {
        var refreshes = 0
        compose.setContent {
            VoltTheme { SignalHistoryScreen(SignalHistoryUiState(loading = false), onRefresh = { refreshes++ }) }
        }
        compose.onNodeWithText("No recordings yet").assertIsDisplayed()
        compose.onNodeWithText("Refresh recordings").performClick()
        compose.runOnIdle { assertEquals(2, refreshes) }
    }
}
