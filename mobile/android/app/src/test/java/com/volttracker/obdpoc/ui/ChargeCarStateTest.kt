package com.volttracker.obdpoc.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.volttracker.obdpoc.ui.charge.ChargeScreen
import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.charge.weekTimeLabel
import com.volttracker.obdpoc.ui.components.LocalVoltPrefs
import com.volttracker.obdpoc.ui.components.VoltPrefs
import com.volttracker.obdpoc.ui.live.LiveUiStateStore
import com.volttracker.obdpoc.ui.theme.VoltTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The idle Charge hero speaks for the car's own charger state while the adapter is connected. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ChargeCarStateTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(
        state: ChargeUiState,
        prefs: VoltPrefs = VoltPrefs(),
    ) {
        compose.setContent {
            VoltTheme { CompositionLocalProvider(LocalVoltPrefs provides prefs) { ChargeScreen(state) } }
        }
    }

    private fun count(text: String): Int = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    @Test
    fun engineRunningInParkDoesNotShowThePlugInChargingHero() {
        val store = LiveUiStateStore()
        store.onStatus(JSONObject().put("state", "connected"))
        store.onTelemetry(
            JSONObject()
                .put("updatedAt", 1000L)
                .put("speedKph", 0)
                .put("prndlState", "P")
                .put("rpm", 1400)
                .put("vehicleState", "ready")
                .put("soc", 62)
                .put("packCurrentA", -20)
                .put("powerKw", -7)
                .put("chargerPowerKw", 3.6)
                .put("chargeState", "None")
                .put("chargeStateStaleMs", 0)
                .put("chargerAcVoltage", 0)
                .put("chargerAcCurrentA", 0),
        )
        show(store.state.value.charge)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/parked-engine-charge.png")
        compose.onNodeWithText("NOT CHARGING").assertIsDisplayed()
        compose.onNodeWithText("CHARGING").assertDoesNotExist()
        compose.onNodeWithText("Full by", substring = true).assertDoesNotExist()
    }

    @Test
    fun weekTimesFollowThePhonesClock() {
        assertEquals("Tue 11:05 PM", weekTimeLabel("Tue 23:05", h24 = false))
        assertEquals("Sun 12:00 AM", weekTimeLabel("Sun 00:00", h24 = false))
        assertEquals("Sat 12:30 PM", weekTimeLabel("Sat 12:30", h24 = false))
        assertEquals("Wed 4:30 AM", weekTimeLabel("Wed 04:30", h24 = false))
        assertEquals("Tue 23:05", weekTimeLabel("Tue 23:05", h24 = true))
        assertEquals("not a schedule time passes through", "soon", weekTimeLabel("soon", h24 = false))
    }

    @Test
    fun aCarWaitingOnItsScheduleSaysWhenItStarts() {
        show(ChargeUiState(connected = true, carChargeState = "Waiting", carChargeStart = "Tue 23:05"))
        // Pills are set in caps.
        compose.onNodeWithText("PLUGGED IN · WAITING").assertIsDisplayed()
        // Non-breaking spaces keep the day and time on one line.
        compose.onNodeWithText("The car starts charging Tue\u00A011:05\u00A0PM").assertIsDisplayed()
    }

    @Test
    fun aWaitingCarWithNoScheduleTimeStillSaysItIsWaiting() {
        show(ChargeUiState(connected = true, carChargeState = "Waiting"), VoltPrefs(clock24h = true))
        compose.onNodeWithText("Waiting for the car's charge schedule").assertIsDisplayed()
    }

    @Test
    fun aCompleteChargeGetsItsOwnPill() {
        show(ChargeUiState(connected = true, carChargeState = "Complete"))
        compose.onNodeWithText("CHARGE COMPLETE").assertIsDisplayed()
        assertEquals(0, count("NOT CHARGING"))
    }

    @Test
    fun aChargeErrorSaysWhatToCheck() {
        show(ChargeUiState(connected = true, carChargeState = "Error"))
        compose.onNodeWithText("CHARGE ERROR").assertIsDisplayed()
        compose.onNodeWithText("The car stopped the charge. Check the cord and the outlet.").assertIsDisplayed()
    }

    @Test
    fun theCarsStateIsIgnoredOnceTheAdapterDisconnects() {
        show(ChargeUiState(connected = false, carChargeState = "Waiting", carChargeStart = "Tue 23:05"))
        compose.onNodeWithText("NOT CONNECTED").assertIsDisplayed()
        assertEquals(0, count("PLUGGED IN · WAITING"))
    }

    @Test
    fun noChargerStateKeepsTheUsualIdleHero() {
        show(ChargeUiState(connected = true, carChargeState = "None"))
        compose.onNodeWithText("NOT CHARGING").assertIsDisplayed()
        compose.onNodeWithText("Plug in the car to see time to full").assertIsDisplayed()
    }
}
