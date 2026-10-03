package com.volttracker.obdpoc.sim

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.ContextCompat
import com.volttracker.obdpoc.AppPrefs
import com.volttracker.obdpoc.ComposeDashboardSupport
import com.volttracker.obdpoc.ComposeDtcActions
import com.volttracker.obdpoc.ComposeHistoryLoader
import com.volttracker.obdpoc.DeviceCatalog
import com.volttracker.obdpoc.DeviceCommands
import com.volttracker.obdpoc.DiagnosticScanProfile
import com.volttracker.obdpoc.MainActivityUtils
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.service.ObdService
import com.volttracker.obdpoc.sim.VirtualVoltCatalog.Mode
import com.volttracker.obdpoc.sim.VirtualVoltScorecardTest.VirtualVoltService
import com.volttracker.obdpoc.ui.VoltApp
import com.volttracker.obdpoc.ui.VoltRoute
import com.volttracker.obdpoc.ui.components.VoltTab
import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.diag.DtcCatalog
import com.volttracker.obdpoc.ui.live.LiveUiStateStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Trouble codes and the freeze frame end to end against the [VirtualVolt]: the real service runs a
 * scan or a clear ([ObdService.ACTION_SCAN] / [ObdService.ACTION_CLEAR_DTC]), the car's
 * [VirtualVolt.Faults] answer Modes 03 / 07 / 0A / 02 / 04, the recorder saves the codes, and the
 * results reach Health the way [com.volttracker.obdpoc.ComposeDashboardActivity] carries them
 * (scan telemetry → [ComposeDtcActions] → [ComposeHistoryLoader] → [LiveUiStateStore] → [VoltApp]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w412dp-h1400dp-420dpi")
class VirtualVoltDtcTest {
    @get:Rule
    val compose = createComposeRule()

    private val controllers = mutableListOf<ServiceController<VirtualVoltService>>()
    private val loaderExecutor: ExecutorService = Executors.newSingleThreadExecutor()
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
        loaderExecutor.shutdownNow()
        VirtualVoltService.nextConnection = null
        VirtualVoltService.nextSwcanPolicy = null
    }

    @Test
    fun quickScanOfACleanCarFindsNoCodesAndSkipsTheFreezeFrame() {
        val car = Car()
        val scan = car.scan(DiagnosticScanProfile.QUICK)

        assertTrue("five modules answered 4300", scan.getBoolean("dtcScanValid"))
        assertEquals(0, scan.getJSONArray("dtcCodes").length())
        assertFalse(scan.has("freezeFrame"))
        val sent = car.commands()
        assertTrue(sent.containsAll(listOf("03", "07", "0A")))
        assertTrue("a clean car has no freeze frame to read", sent.none { it.startsWith("02") })
        assertTrue("a quick scan skips the module sweep", sent.none { it == "ATSH7E4" })
    }

    @Test
    fun quickScanReadsAStoredCodeAndItsFreezeFrame() {
        val car = Car()
        car.adapter.faults.set("P0128")
        val scan = car.scan(DiagnosticScanProfile.QUICK)

        assertTrue(scan.getBoolean("dtcScanValid"))
        assertEquals(listOf("P0128"), scan.getJSONArray("dtcCodes").strings())
        val frame = scan.getJSONObject("freezeFrame")
        assertEquals("P0128", frame.getString("dtc"))
        // Every frame-00 PID the app asks for is in the car's snapshot.
        val readings = frame.getJSONArray("readings")
        assertEquals(FREEZE_FRAME_PIDS, readings.length())
        val byName =
            (0 until readings.length()).associate {
                readings.getJSONObject(it).let { r ->
                    r.getString("name") to
                        r
                }
            }
        assertEquals(72.0, byName.getValue("vehicle speed").getDouble("value"), 0.0)
        assertEquals(1726.0, byName.getValue("engine rpm").getDouble("value"), 0.0)
        assertEquals(50.0, byName.getValue("coolant temperature").getDouble("value"), 0.0)

        // The recorder keeps the code from each service that reported it.
        val saved = car.savedCodes { it.size == 3 }
        assertEquals(setOf("P0128"), saved.map { it.code }.toSet())
        assertEquals(setOf("stored", "permanent", "freeze-frame"), saved.map { it.status }.toSet())
    }

    @Test
    fun fullScanSweepsEveryModuleAndReadsTheFreezeFrame() {
        val car = Car()
        car.adapter.faults.set("P0128")
        val scan = car.scan(DiagnosticScanProfile.FULL)

        assertEquals("full", scan.getString("scanProfile"))
        assertEquals(listOf("P0128"), scan.getJSONArray("dtcCodes").strings())
        assertEquals("P0128", scan.getJSONObject("freezeFrame").getString("dtc"))
        val sent = car.commands()
        assertTrue(sent.contains("0200"))
        for (header in listOf("ATSH7E4", "ATSH7E1", "ATSH7E0", "ATSH7E2", "ATSH7E6", "ATSH7E7")) {
            assertTrue("full scan addressed $header", sent.contains(header))
        }
        // The Volt only speaks 11-bit CAN: the 29-bit probes go unanswered, and the VIN read on CAN 11-bit
        // still identifies the car.
        val replies = car.adapter.exchanges.map { it.command to it.reply }
        val probe29 = replies.indexOfFirst { it.first == "ATSP7" }
        assertEquals("NO DATA", replies.drop(probe29).first { it.first == "0902" }.second)
        val vehicle =
            car.service.localStore!!
                .projections()
                .latestVehicle()
        assertEquals("Chevrolet", vehicle.getString("make"))
        assertEquals(2017, vehicle.getInt("year"))
        assertEquals("…0000", vehicle.getString("vin"))
    }

    @Test
    fun clearingErasesTheStoredCodeAndFreezeFrameButNotThePermanentCode() {
        val car = Car()
        car.adapter.faults.set("P0128")
        car.scan(DiagnosticScanProfile.QUICK)

        val clear = car.clear()
        assertTrue(clear.getBoolean("clearDtcOk"))
        assertEquals(null, car.adapter.faults.stored)

        val rescan = car.scan(DiagnosticScanProfile.QUICK)
        assertTrue(rescan.getBoolean("dtcScanValid"))
        assertEquals(0, rescan.getJSONArray("dtcCodes").length())
        assertFalse("the freeze frame went with the stored code", rescan.has("freezeFrame"))
        assertNotNull("the permanent code stays", car.adapter.faults.permanent)
    }

    @Test
    fun aStoredCodeReachesHealthAndTheFreezeFrameScreen() {
        val car = Car()
        car.adapter.faults.set("P0128")
        car.scan(DiagnosticScanProfile.QUICK)
        car.deliverToScreens({ it.size == 3 }) { it.freezeFrame != null && it.codes?.isNotEmpty() == true }

        // Saved three times over (stored, permanent, freeze frame), it's still one code, shown as stored.
        assertEquals(
            "stored",
            store.state.value.diag.codes!!
                .single()
                .status,
        )
        show(VoltRoute.HEALTH)
        text("1 trouble code")
        text("P0128")
        text("Coolant thermostat - below regulating temperature")
        text("Captured with P0128")

        text("Captured with P0128").performClick()
        // 72 km/h, 50 °C coolant, 15 °C intake air, 14.13 V, 240 s: the car's frame 00 as a driver reads it.
        text("45 mph")
        text("1726 rpm")
        text("122°F")
        text("59°F")
        text("14.1 V")
        text("4 min 0 s")
    }

    @Test
    fun afterAClearHealthShowsOnlyThePermanentCode() {
        val car = Car()
        car.adapter.faults.set("P0128")
        car.scan(DiagnosticScanProfile.QUICK)
        car.clear()
        car.scan(DiagnosticScanProfile.QUICK)
        // The rescan's Mode 0A read is the permanent row's second sighting.
        car.deliverToScreens({ rows -> rows.any { it.status == "permanent" && it.seen == 2 } }) {
            it.freezeFrame == null && it.codes?.singleOrNull()?.status == "permanent"
        }

        show(VoltRoute.HEALTH)
        // The rescan's Mode 0A still reports P0128: the car keeps a permanent code until it passes
        // its own test, so Health keeps it, without the erased freeze frame.
        text("1 trouble code")
        compose.onNodeWithText("1 permanent", substring = true).performScrollTo().assertIsDisplayed()
        text("P0128")
        text("None stored")
    }

    /**
     * One virtual car on one service, with the Compose activity's trouble-code plumbing: scans and
     * clears run as separate sessions on the same adapter, like Health's buttons send them.
     */
    private inner class Car {
        val adapter = VirtualVolt(Mode.CHARGING, stn = true)
        val service: VirtualVoltService
        private val broadcasts = mutableListOf<Pair<String, String?>>()

        init {
            VirtualVoltService.nextConnection = adapter
            val controller = Robolectric.buildService(VirtualVoltService::class.java).create()
            controllers.add(controller)
            service = controller.get()
            service.localStore!!.clearAllData()
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        broadcasts += intent.action.orEmpty() to intent.getStringExtra(ObdService.EXTRA_JSON)
                    }
                }
            val filter =
                IntentFilter().apply {
                    addAction(ObdService.BROADCAST_TELEMETRY)
                    addAction(ObdService.BROADCAST_STATUS)
                }
            // As in VirtualVoltComposeTest: below API 33 the not-exported receiver needs the app's permission.
            val app = service.application
            shadowOf(app).grantPermissions("${app.packageName}$RECEIVER_PERMISSION_SUFFIX")
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }

        fun scan(profile: DiagnosticScanProfile): JSONObject =
            session(ObdService.ACTION_SCAN, profile.wireName) { it.optString("source") == "scan" }

        fun clear(): JSONObject = session(ObdService.ACTION_CLEAR_DTC, null) { it.optString("source") == "clear-dtc" }

        /** Runs one session to its result sample and waits for it to hang up. */
        private fun session(
            action: String,
            stage: String?,
            isResult: (JSONObject) -> Boolean,
        ): JSONObject {
            val before = service.engineSamples.size
            val closes = adapter.closeCalls.get()
            val intent =
                Intent(service, VirtualVoltService::class.java).apply {
                    this.action = action
                    putExtra(ObdService.EXTRA_ADDRESS, "AA:BB:CC:DD:EE:FF")
                    putExtra(ObdService.EXTRA_NAME, "Virtual OBDLink")
                    stage?.let { putExtra(ObdService.EXTRA_DETAIL_STAGE, it) }
                }
            service.onStartCommand(intent, 0, before + 1)
            var result: JSONObject? = null
            VirtualVoltTestSupport.waitFor("$action result", WAIT_TIMEOUT_MS) {
                result =
                    synchronized(service.engineSamples) { service.engineSamples.drop(before).firstOrNull(isResult) }
                result != null
            }
            VirtualVoltTestSupport.waitFor("$action to hang up", WAIT_TIMEOUT_MS) { adapter.closeCalls.get() > closes }
            return result!!
        }

        fun commands(): List<String> = synchronized(adapter.exchanges) { adapter.exchanges.map { it.command } }

        /**
         * The saved codes once [ready] holds them: the recorder writes them on its own thread, so they
         * can land after the scan has reported.
         */
        fun savedCodes(ready: (List<Saved>) -> Boolean): List<Saved> {
            var rows = emptyList<Saved>()
            VirtualVoltTestSupport.waitFor("the saved codes", WAIT_TIMEOUT_MS) {
                val latest =
                    service.localStore!!
                        .projections()
                        .diagnosticsSummary(50)
                        .getJSONArray("latestDiagnosticCodes")
                rows =
                    (0 until latest.length()).map {
                        latest.getJSONObject(it).let { r ->
                            Saved(r.getString("dtc"), r.getString("status"), r.getInt("seenCount"))
                        }
                    }
                ready(rows)
            }
            return rows
        }

        /**
         * Hands every broadcast so far to the screens the way the Compose activity's receiver does,
         * once the recorder has saved the codes, and waits for Health's re-read to land.
         */
        fun deliverToScreens(
            saved: (List<Saved>) -> Boolean,
            settled: (DiagUiState) -> Boolean,
        ) {
            savedCodes(saved)
            shadowOf(Looper.getMainLooper()).idle()
            val app = service.application
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val prefs = app.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
            val catalog =
                app.assets
                    .open(
                        DtcCatalog.ASSET_PATH,
                    ).bufferedReader()
                    .use { DtcCatalog.parse(it.readText()) }
            assertTrue("the APK's DTC table loaded", catalog.size > 0)
            lateinit var history: ComposeHistoryLoader
            val dtc =
                ComposeDtcActions(
                    activity,
                    NoAdapterCommands(activity, prefs),
                    { prefs },
                    store,
                    {},
                ) { history.loadHealth() }
            history =
                ComposeHistoryLoader(
                    loaderExecutor,
                    store,
                    Runnable::run,
                    openStore = { ObdLocalStore(app) },
                    dtcCatalog = { catalog },
                    dtcChecks = { dtc.lastScanAtMs() to dtc.lastClearAtMs() },
                    freezeFrame = dtc::freezeFrame,
                )
            for ((action, json) in broadcasts.toList()) {
                ComposeDashboardSupport.routeServiceBroadcast(action, json, store)
                if (action == ObdService.BROADCAST_TELEMETRY) dtc.onTelemetry(MainActivityUtils.parseJson(json))
            }
            VirtualVoltTestSupport.waitFor(
                "Health to load the saved codes",
                WAIT_TIMEOUT_MS,
            ) { settled(store.state.value.diag) }
        }
    }

    /** One saved code row: P0128 · stored · seen once. */
    private data class Saved(
        val code: String,
        val status: String,
        val seen: Int,
    )

    /** Health's buttons aren't pressed here; the actions only need a device catalog to exist. */
    private class NoAdapterCommands(
        activity: Activity,
        prefs: android.content.SharedPreferences,
    ) : DeviceCommands {
        private val catalog = DeviceCatalog(activity, prefs)

        override fun requireDeviceCatalog() = catalog

        override fun rememberDevice(
            address: String?,
            name: String?,
        ) = Unit

        override fun startObdService(
            action: String?,
            address: String?,
            name: String?,
        ) = Unit

        override fun startObdService(
            action: String?,
            address: String?,
            name: String?,
            detailStage: String?,
        ) = Unit

        override fun stopObdService() = Unit

        override fun isLoggingActive() = false
    }

    private fun show(route: VoltRoute) {
        compose.setContent {
            val state by store.state.collectAsState()
            VoltApp(state, initialTab = VoltTab.CAR, initialRoutes = listOf(route))
        }
    }

    private fun text(value: String): SemanticsNodeInteraction =
        compose.onNodeWithText(value).performScrollTo().assertIsDisplayed()

    private fun org.json.JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    private companion object {
        const val WAIT_TIMEOUT_MS = 60_000L
        const val FREEZE_FRAME_PIDS = 9

        /** androidx.core's per-app permission for not-exported receivers before API 33. */
        const val RECEIVER_PERMISSION_SUFFIX = ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    }
}
