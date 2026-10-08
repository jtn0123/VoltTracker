package com.volttracker.obdpoc.sim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.ContextCompat
import com.volttracker.obdpoc.ComposeDashboardSupport
import com.volttracker.obdpoc.engine.SwcanListenRunner
import com.volttracker.obdpoc.service.ObdService
import com.volttracker.obdpoc.sim.VirtualVoltCatalog.Mode
import com.volttracker.obdpoc.sim.VirtualVoltScorecardTest.VirtualVoltService
import com.volttracker.obdpoc.ui.VoltApp
import com.volttracker.obdpoc.ui.VoltRoute
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.live.LiveUiStateStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The virtual Volt all the way to the Compose screens: the real service → polling engine →
 * SW-CAN listener → status/telemetry broadcasts, received the way [com.volttracker.obdpoc.ComposeDashboardActivity]
 * receives them (a registered receiver routing through [ComposeDashboardSupport.routeServiceBroadcast]
 * into a [LiveUiStateStore]), then [VoltApp] rendered from that store. Each assertion is the text a
 * driver would read, derived from the [VirtualVoltCatalog] bytes and the screens' formatting.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w412dp-h1400dp-420dpi")
class VirtualVoltComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private val controllers = mutableListOf<ServiceController<VirtualVoltService>>()
    private val store = LiveUiStateStore()

    @After
    fun tearDown() {
        for (controller in controllers) {
            controller.get().running.set(false)
            try {
                controller.destroy()
            } catch (ignored: RuntimeException) {
                // Robolectric can complain when destroying a foreground service in JVM-only tests.
            }
        }
        VirtualVoltService.nextConnection = null
        VirtualVoltService.nextSwcanPolicy = null
    }

    @Test
    fun drivingTelemetryReachesDriveAndCar() {
        val last = drive(Mode.DRIVING)
        show(VoltTab.DRIVE)

        // 228334 = 0x8C → 54.9 % on the cluster's scale; the range card shows whole percent.
        text("54% battery")
        // 2241A6 = 0x0DC0 / 64 = 55 km electric; the gas range is the SW-CAN fuelRangeKm frame.
        val evMiles = milesWhole(EV_RANGE_KM)
        val gasMiles = milesWhole(last.getDouble("fuelRangeKm"))
        assertTrue("the gas range frame decoded to a real distance", gasMiles > 0)
        text("$evMiles mi")
        text("$gasMiles mi")
        text("${milesWhole(EV_RANGE_KM + last.getDouble("fuelRangeKm"))} mi total")

        tab(VoltTab.CAR)
        assertCar(last, aux12Line = "car on · charging")
    }

    @Test
    fun chargingTelemetryReachesChargeDriveAndCar() {
        val last = drive(Mode.CHARGING)
        show(VoltTab.CHARGE)

        // 22436B/22436C: 336 V × 10 A on the HV side = 3.4 kW; 224368/224369: 240 V, 14 A at the
        // wall; 224531 = 02 (AC_2) → Level 2.
        // Pills render their text in capitals.
        text("CHARGING · LEVEL 2")
        text("3.4 kW · 240 V · 14 A")
        // 14 kWh usable × (100 − 54.9) % at 3.4 kW: 1 hr 51 min to full from the newest sample.
        val remainingMs = (USABLE_KWH * (100 - DISPLAYED_SOC) / 100 / CHARGER_KW * 3_600_000).toLong()
        val fullBy = SimpleDateFormat("h:mm a", Locale.US).format(Date(last.getLong("updatedAt") + remainingMs))
        text("Full by $fullBy")
        text("1 hr 51 min remaining")
        // The small ring's spoken figure truncates like every visible one: 54.9 % is 54, not 55.
        compose.onNodeWithContentDescription("Battery 54 percent", substring = true).assertIsDisplayed()

        tab(VoltTab.DRIVE)
        compose
            .onNodeWithContentDescription(
                "Battery 54 percent, Full by $fullBy, 1 hr 51 min, charging at 3.4 kilowatts",
            ).assertIsDisplayed()
        text("Level 2")
        text("3.4 kW onboard")

        tab(VoltTab.CAR)
        assertCar(last, aux12Line = "plugged in · charging")
    }

    @Test
    fun drivingTelemetryReachesLiveSignals() {
        // Oil and motor temperatures are read every 48 cycles, first at cycles 42 and 44.
        drive(Mode.DRIVING, samples = TEMPERATURE_SAMPLES)
        compose.setContent {
            val state by store.state.collectAsState()
            VoltApp(state, initialRoutes = listOf(VoltRoute.SIGNALS))
        }
        // 221154 = 0x74 → 76 °C oil; 2228CB = 0x61 → 57 °C motor A; 22368F = 0x60 → 56 °C motor B.
        signal("Oil temperature", "168°F")
        signal("Motor A temperature", "134°F")
        signal("Motor B temperature", "132°F")
        // The car never answers 221940, but its SW-CAN broadcast carries the drive-unit oil:
        // 0x102E0040 byte 3 = 0x7B → 83 °C.
        signal("Transmission temperature", "181°F")
        // The car refuses 221C26 (7F 22 31) and 22119F: no blank rows.
        for (refused in listOf("Inverter temperature", "Oil life", "Torque")) {
            compose.onNodeWithText(refused).assertDoesNotExist()
        }
    }

    @Test
    fun drivingTelemetryReachesTheOdometer() {
        // 2234B2 is read every 240 cycles, first at cycle 150.
        drive(Mode.DRIVING, samples = ODOMETER_SAMPLES)
        show(VoltTab.CAR)
        // 0x005D6E60 / 64 = 95,673.5 km = 59,448.8 mi, cut down like the cluster.
        text("Odometer 59,448 mi")
    }

    @Test
    fun drivingTelemetryReachesTheBatteryCards() {
        // The slowest of these, 2240E9, is read every 240 cycles, first at cycle 210.
        drive(Mode.DRIVING, samples = BATTERY_SAMPLES)
        compose.setContent {
            val state by store.state.collectAsState()
            VoltApp(state, initialTab = VoltTab.CAR, initialRoutes = listOf(VoltRoute.HEALTH))
        }
        // 2240E9 = 0x024A / 2 = 293 mΩ; 2243A5 = 0x04D2 = 1,234 charges; 224389 = 0x0012D687 x 10 Wh.
        text("Internal resistance 293 mΩ · charged 1,234 times (12,346 kWh)")
        // Sections 2240D7..2240E1 = 0x44..0x46 → 28..30 °C = 82..86 °F.
        text("82–86°F")
        // 2241B2 = 0x04C4 rpm; 2241B6 = 0 W; 221C43 = 0x50 → 40 °C = 104 °F.
        signal("coolant pump", "1,220 rpm")
        signal("pack heater", "Off")
        signal("electronics coolant", "104°F")
    }

    /** The Car tab: lock, 12 V and climate from the SW-CAN broadcasts the catalog puts on the bus. */
    private fun assertCar(
        last: JSONObject,
        aux12Line: String,
    ) {
        // 0x0C414040 data 00 01 7A 01: locked from the panel.
        text("LOCKED")
        compose.onNodeWithContentDescription("Car locked", substring = true).assertIsDisplayed()
        // 0x10248040 data 00 00 61 …: 0x61 × 0.1 + 3 = 12.7 V; a moving or plugged-in car is charging it.
        text("12.7 V")
        text(aux12Line)
        // 0x10734099 A/C on leads; 0x10440099 cabin air estimate 30.5 °C = 86 °F; outside from 22801F.
        // The A/C leads the tile, with the compressor's draw beside it.
        compose.onNodeWithText("A/C on", substring = true).performScrollTo().assertIsDisplayed()
        val outsideF = (last.getDouble("outsideTempC") * 9 / 5 + 32).roundToInt()
        text("Cabin 86°F · Outside $outsideF°F")
    }

    /**
     * Drives the virtual Volt in [mode] until it has produced [samples] samples, delivering every
     * broadcast so far into [store] while the session is still live; returns the newest sample.
     */
    private fun drive(
        mode: Mode,
        samples: Int = SAMPLES,
    ): JSONObject {
        val adapter = VirtualVolt(mode, stn = true)
        VirtualVoltService.nextConnection = adapter
        VirtualVoltService.nextSwcanPolicy =
            SwcanListenRunner.Policy(
                firstWindowDelayMs = 0L,
                intervalMs = 0L,
                listenMs = 0L,
                stopTimeoutMs = 0L,
                parkedIntervalMs = 0L,
                parkedListenMs = 0L,
                tireHuntMaxWindows = 0,
            )
        val controller = Robolectric.buildService(VirtualVoltService::class.java).create()
        controllers.add(controller)
        val service = controller.get()
        service.localStore!!.clearAllData()
        val routed = mutableListOf<String>()
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    val json = intent.getStringExtra(ObdService.EXTRA_JSON)
                    if (ComposeDashboardSupport.routeServiceBroadcast(intent.action, json, store)) {
                        routed += intent.action.orEmpty()
                    }
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(ObdService.BROADCAST_TELEMETRY)
                addAction(ObdService.BROADCAST_STATUS)
            }
        // Registered the way ComposeDashboardActivity registers it. Below API 33 ContextCompat guards a
        // not-exported receiver with the app's own signature permission, which an installed app holds
        // and Robolectric has to be told about.
        val app = service.application
        shadowOf(app).grantPermissions("${app.packageName}$RECEIVER_PERMISSION_SUFFIX")
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        service.onStartCommand(VirtualVoltTestSupport.connectIntent(service, "Virtual OBDLink"), 0, 1)
        VirtualVoltTestSupport.waitFor("$mode drive to collect $samples samples", WAIT_TIMEOUT_MS) {
            service.engineSamples.size >= samples
        }
        // Deliver what the service has broadcast so far, then stop listening before the session
        // ends: the screens are read mid-session, not after the disconnect clears them.
        shadowOf(Looper.getMainLooper()).idle()
        app.unregisterReceiver(receiver)
        val telemetry = synchronized(service.wirePayloads) { service.wirePayloads.toList() }
        service.running.set(false)
        VirtualVoltTestSupport.waitFor("$mode adapter to close", WAIT_TIMEOUT_MS) { adapter.closeCalls.get() > 0 }

        assertTrue("$mode: a status reached the store", routed.contains(ObdService.BROADCAST_STATUS))
        val delivered = routed.count { it == ObdService.BROADCAST_TELEMETRY }
        assertTrue("$mode: telemetry reached the store ($delivered)", delivered >= samples)
        assertTrue("$mode: the store is live", store.state.value.drive.connected)
        val newest = telemetry[delivered - 1]
        assertEquals(newest.getLong("updatedAt"), store.state.value.drive.sampleAtMs)
        return newest
    }

    private fun show(tab: VoltTab) {
        compose.setContent {
            val state by store.state.collectAsState()
            VoltApp(state, initialTab = tab)
        }
    }

    private fun tab(tab: VoltTab) {
        compose.onNodeWithContentDescription(tab.label, substring = true).performClick()
    }

    private fun text(value: String): SemanticsNodeInteraction =
        compose.onNodeWithText(value).performScrollTo().assertIsDisplayed()

    /** A Live signals row: its label, with [value] on the same row. */
    private fun signal(
        label: String,
        value: String,
    ) {
        text(label)
        compose.onNode(hasText(value) and hasAnySibling(hasText(label))).assertIsDisplayed()
    }

    private fun milesWhole(km: Double): Int = (km * MI_PER_KM).roundToInt()

    private companion object {
        const val SAMPLES = 40
        const val TEMPERATURE_SAMPLES = 50
        const val ODOMETER_SAMPLES = 160
        const val BATTERY_SAMPLES = 220
        const val WAIT_TIMEOUT_MS = 60_000L
        const val MI_PER_KM = 0.621371

        /** androidx.core's per-app permission for not-exported receivers before API 33. */
        const val RECEIVER_PERMISSION_SUFFIX = ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

        /** 2241A6 = 0x0DC0 at 1/64 km. */
        const val EV_RANGE_KM = 55.0

        /** 228334 = 0x8C, as the decoder reports it. */
        const val DISPLAYED_SOC = 54.9

        /** 336 V × 10 A, rounded to a tenth like the decoder. */
        const val CHARGER_KW = 3.4

        /** The 2016+ Volt's usable pack energy the time-to-full uses before the car reports health. */
        const val USABLE_KWH = 14.0
    }
}
