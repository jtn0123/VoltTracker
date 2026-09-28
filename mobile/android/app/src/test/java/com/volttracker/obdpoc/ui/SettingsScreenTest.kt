package com.volttracker.obdpoc.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.drive.costLabel
import com.volttracker.obdpoc.ui.settings.SettingChange
import com.volttracker.obdpoc.ui.settings.SettingsActions
import com.volttracker.obdpoc.ui.settings.SettingsCommand
import com.volttracker.obdpoc.ui.settings.SettingsPage
import com.volttracker.obdpoc.ui.settings.SettingsScreen
import com.volttracker.obdpoc.ui.settings.SettingsUiState
import com.volttracker.obdpoc.ui.theme.VoltTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every Settings control reports the edit it stands for, and shows the stored value. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h1200dp-420dpi")
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val changes = mutableListOf<SettingChange>()
    private val commands = mutableListOf<SettingsCommand>()

    private fun show(
        page: SettingsPage,
        state: SettingsUiState = SettingsUiState.demo,
    ) {
        compose.setContent {
            VoltTheme {
                SettingsScreen(
                    state,
                    initialPage = page,
                    actions =
                        SettingsActions(onChange = { changes += it }, onCommand = {
                            commands +=
                                it
                        }),
                )
            }
        }
    }

    /**
     * Types into the open number editor and taps [button]. A focused text field blinks its cursor
     * forever, so the clock is driven by hand while it is on screen.
     */
    private fun typeAndTap(
        text: String?,
        button: String,
    ) {
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        if (text != null) compose.onNodeWithTag("settings-number-input").performTextReplacement(text)
        compose.onNodeWithText(button).performClick()
        compose.mainClock.advanceTimeBy(FRAME_MS)
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun connectionAutoConnectToggles() {
        show(SettingsPage.CONNECTION, SettingsUiState.demo.copy(autoConnect = false))
        compose.onNodeWithText("Auto-connect").performClick()
        assertEquals(listOf<SettingChange>(SettingChange.AutoConnect(true)), changes)
    }

    @Test
    fun connectionToolsRunTheirCommands() {
        show(SettingsPage.CONNECTION)
        compose.onNodeWithText("Test connection").performClick()
        compose.onNodeWithText("Send diagnostics").performClick()
        compose.onNodeWithText("Wait for adapter").performClick()
        compose.onNodeWithText("30 min").performClick()
        assertEquals(
            listOf(
                SettingsCommand.TestConnection,
                SettingsCommand.SendDiagnostics,
                SettingsCommand.WaitForAdapter(on = true, minutes = 10),
                SettingsCommand.WaitForAdapter(on = false, minutes = 30),
            ),
            commands,
        )
    }

    @Test
    fun waitingForAdapterShowsItIsCheckingAndTurnsOff() {
        show(SettingsPage.CONNECTION, SettingsUiState.demo.copy(waitingForAdapter = true, adapterWaitMins = 15))
        compose.onNodeWithText("Checking every 30 s for 15 min · notifies when ready").assertIsDisplayed()
        compose.onNodeWithText("Wait for adapter").performClick()
        assertEquals(listOf<SettingsCommand>(SettingsCommand.WaitForAdapter(on = false, minutes = 15)), commands)
    }

    @Test
    fun dataBacksUpWithAnOptionalPassphrase() {
        show(
            SettingsPage.DATA,
            SettingsUiState.demo.copy(lastBackupLabel = "Last backup today", dataTaskLabel = "Preparing backup · 40%"),
        )
        compose.onNodeWithText("Last backup today").assertIsDisplayed()
        compose.onNodeWithText("Preparing backup · 40%").assertIsDisplayed()
        compose.onNodeWithText("Back up").performClick()
        typeAndTap(null, "Back up now")
        compose.onNodeWithText("Back up").performClick()
        typeAndTap("correct horse", "Back up now")
        assertEquals(
            listOf(SettingsCommand.BackUp(null), SettingsCommand.BackUp("correct horse")),
            commands,
        )
    }

    @Test
    fun dataRestoresAndExports() {
        show(SettingsPage.DATA)
        compose.onNodeWithText("Restore").performClick()
        typeAndTap(null, "Choose file")
        compose.onNodeWithText("Restore").performClick()
        typeAndTap(null, "Cancel")
        compose.onNodeWithText("Export").performClick()
        assertEquals(listOf(SettingsCommand.Restore(null), SettingsCommand.ExportTrips), commands)
    }

    @Test
    fun costsShowStoredValuesAndEditThroughTheNumberDialog() {
        show(SettingsPage.COSTS)
        compose.onNodeWithText("$0.12 / kWh").assertIsDisplayed()
        compose.onNodeWithText("$4.29 / gal").assertIsDisplayed()
        compose.onNodeWithText("30 MPG").assertIsDisplayed()
        compose.onNodeWithText("same as home").assertIsDisplayed()

        compose.onNodeWithText("Home electricity rate").performClick()
        compose.onNodeWithText("0–2 $/kWh").assertExists()
        typeAndTap("0.14", "Save")
        // Out-of-range entries clamp, like the classic dashboard's inputs.
        compose.onNodeWithText("Gas price").performClick()
        typeAndTap("25", "Save")
        compose.onNodeWithText("Gas vehicle MPG").performClick()
        typeAndTap(null, "Clear")
        assertEquals(
            listOf(SettingChange.HomeRate(0.14), SettingChange.GasPrice(10.0), SettingChange.GasMpg(null)),
            changes,
        )
    }

    @Test
    fun aNonNumberCannotBeSavedAndCancelChangesNothing() {
        show(SettingsPage.COSTS)
        compose.onNodeWithText("Public charging rate").performClick()
        typeAndTap("abc", "Save")
        typeAndTap(null, "Cancel")
        assertTrue(changes.isEmpty())
        assertTrue(compose.onAllNodes(hasText("0–2 $/kWh")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun chargeTargetPresetsAndOther() {
        show(SettingsPage.COSTS, SettingsUiState.demo.copy(chargeTargetPct = 85))
        compose.onNodeWithText("Notify at this charge · 85%").assertIsDisplayed()
        compose.onNodeWithText("80%").performClick()
        compose.onNodeWithText("Other").performClick()
        // The charge target always has a value: no Clear.
        assertTrue(compose.onAllNodes(hasText("Clear")).fetchSemanticsNodes().isEmpty())
        typeAndTap("95", "Save")
        assertEquals(listOf(SettingChange.ChargeTarget(80), SettingChange.ChargeTarget(95)), changes)
    }

    @Test
    fun unitsSegmentsReportMetric() {
        show(SettingsPage.UNITS)
        compose.onNodeWithText("mi · °F · psi").assertIsDisplayed()
        compose.onNodeWithText("Metric").performClick()
        assertEquals(listOf<SettingChange>(SettingChange.MetricUnits(true)), changes)
    }

    @Test
    fun appearanceControls() {
        show(SettingsPage.APPEARANCE)
        compose.onNodeWithText("Detailed").performClick()
        compose.onNodeWithText("Large").performClick()
        compose.onNodeWithText("Keep screen awake").performScrollTo().performClick()
        compose.onNodeWithText("High contrast").performScrollTo().performClick()
        compose.onNodeWithText("Quiet live data").performScrollTo().performClick()
        assertEquals(
            listOf(
                SettingChange.DriveDetailed(true),
                SettingChange.TextSize(1.25),
                SettingChange.KeepScreenAwake(true),
                SettingChange.HighContrast(true),
                SettingChange.QuietLiveData(false),
            ),
            changes,
        )
    }

    @Test
    fun alertThresholdsShowOnlyWhileTheAlertIsOn() {
        show(SettingsPage.ALERTS, SettingsUiState.demo.copy(notifyBatteryLow = false))
        assertTrue(compose.onAllNodes(hasText("30%")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Battery low").performClick()
        compose.onNodeWithText("Maintenance overdue").performClick()
        compose.onNodeWithText("End-of-drive recap").performClick()
        compose.onNodeWithText("Auto-scan for codes").performClick()
        compose.onNodeWithText("Charging complete").performClick()
        compose.onNodeWithText("New car code found").performClick()
        assertEquals(
            listOf(
                SettingChange.NotifyBatteryLow(true, 20),
                SettingChange.NotifyMaintenance(true),
                SettingChange.EndOfDriveRecap(true),
                SettingChange.AutoScanCodes(true),
                SettingChange.NotifyChargeComplete(false),
                SettingChange.NotifyNewCode(false),
            ),
            changes,
        )
    }

    @Test
    fun enabledThresholdsPickInTheChosenUnits() {
        show(
            SettingsPage.ALERTS,
            SettingsUiState.demo.copy(notifyBatteryLow = true, notifyPackTempHigh = true, packTempHighC = 50),
        )
        compose.onNodeWithText("above 122°F").assertIsDisplayed()
        compose.onNodeWithText("30%").performClick()
        compose.onNodeWithText("131°F").performClick()
        compose.onNodeWithText("Pack temperature high").performClick()
        assertEquals(
            listOf(
                SettingChange.NotifyBatteryLow(true, 30),
                SettingChange.NotifyPackTempHigh(true, 55),
                SettingChange.NotifyPackTempHigh(false, 50),
            ),
            changes,
        )
    }

    @Test
    fun labelsFollowTheStoredValues() {
        val metric = SettingsUiState(metricUnits = true, packTempHighC = 45, fontScale = 1.5, gasMpg = 32.5)
        assertEquals("Metric", metric.unitsLabel)
        assertEquals("above 45°C", metric.packTempHighLabel)
        assertEquals("Largest", metric.textSizeLabel)
        assertEquals("7.2 L/100 km", metric.gasMpgLabel)
        assertEquals("32.5 MPG", SettingsUiState(gasMpg = 32.5).gasMpgLabel)
        assertEquals("$1.13 / L", SettingsUiState(metricUnits = true, gasPrice = 4.29).gasPriceLabel)
        assertEquals("262 kPa", metric.tirePlacardLabel)
        assertEquals("not set", metric.homeRateLabel)
        assertEquals("Imperial", SettingsUiState().unitsLabel)
        assertEquals("above 113°F", SettingsUiState().packTempHighLabel)
        assertEquals("$0.16 / kWh", SettingsUiState(publicRate = 0.155).publicRateLabel)
        assertEquals(3, SettingsUiState.demo.alertsOnCount)
    }

    @Test
    fun costLabelNeedsARate() {
        assertEquals("$0.52", costLabel(4.3, 0.12))
        assertNull(costLabel(4.3, 0.0))
        assertNull(costLabel(null, 0.12))
    }

    @Test
    fun screenNamesMatchTheClassicDashboardViews() {
        assertEquals("drive", screenViewName(VoltTab.DRIVE, null))
        assertEquals("map", screenViewName(VoltTab.TRIPS, null))
        assertEquals("charge", screenViewName(VoltTab.CHARGE, null))
        assertEquals("insights", screenViewName(VoltTab.INSIGHTS, null))
        assertEquals("diagnostics", screenViewName(VoltTab.CAR, null))
        assertEquals("settings", screenViewName(VoltTab.DRIVE, VoltRoute.SETTINGS))
        assertEquals("diagnostics", screenViewName(VoltTab.DRIVE, VoltRoute.HEALTH))
    }

    @Test
    fun theAppReportsTheVisibleScreenAndScalesText() {
        val shown = mutableListOf<String>()
        compose.setContent {
            VoltApp(
                VoltAppUiState(settings = SettingsUiState.demo.copy(fontScale = 1.5)),
                initialRoutes = listOf(VoltRoute.SETTINGS),
                actions = VoltAppActions(onScreenShown = { shown += it }),
            )
        }
        compose.waitForIdle()
        assertEquals(listOf("settings"), shown)
    }

    private companion object {
        const val FRAME_MS = 100L
    }
}
