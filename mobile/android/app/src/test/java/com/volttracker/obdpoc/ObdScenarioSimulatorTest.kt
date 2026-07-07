package com.volttracker.obdpoc

import android.bluetooth.BluetoothDevice
import android.content.Intent
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.data.ObdSessionRecord
import com.volttracker.obdpoc.location.FilteredLocation
import com.volttracker.obdpoc.location.LocationTracker
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end-ish drive simulation: ACTION_CONNECT starts the real service, a scripted ELM adapter
 * feeds the real polling engine, and assertions read the same SQLite-backed dashboard projections
 * the WebView consumes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ObdScenarioSimulatorTest {
    private lateinit var controller: ServiceController<ScenarioService>
    private lateinit var service: ScenarioService
    private lateinit var connection: ScriptedElmConnection

    @Before
    fun setUp() {
        connection = ScriptedElmConnection()
        ScenarioService.nextConnection = connection
        controller = Robolectric.buildService(ScenarioService::class.java).create()
        service = controller.get()
        service.localStore!!.clearAllData()
        service.locationTracker = RouteLocationTracker()
    }

    @After
    fun tearDown() {
        service.running.set(false)
        try {
            controller.destroy()
        } catch (ignored: RuntimeException) {
            // Robolectric can complain when destroying a foreground service in JVM-only tests.
        } finally {
            ScenarioService.nextConnection = null
        }
    }

    @Test
    fun scriptedLiveDrivePersistsSessionAndFeedsDashboardStorage() {
        scriptMovingVoltResponses()
        connection.afterCommand(PidSchedule.RESTORE_BROADCAST_HEADER_COMMAND) {
            if (service.scenarioEngine.sampleCount() >= 7) {
                service.running.set(false)
            }
        }

        service.onStartCommand(connectIntent(), 0, 1)

        waitFor("scripted drive to collect samples") { service.scenarioEngine.sampleCount() >= 7 }
        service.running.set(false)
        waitFor("scripted adapter to close") { connection.closeCalls.get() > 0 }
        val session = waitForFinalizedSession()
        val store = service.localStore!!
        val reader = DashboardStorageReader { store }

        assertEquals(ObdLocalStore.MODE_OBD, session.mode)
        assertEquals(ObdLocalStore.STATUS_COMPLETE, session.status)
        assertTrue("session should keep the simulated adapter name", session.adapterName == ADAPTER_NAME)
        assertTrue("fake Bluetooth socket should have opened", service.scenarioEngine.openCount.get() >= 1)
        assertTrue("ELM init must run through the service", connection.commandLog.contains("ATZ"))
        assertTrue("capability probe must run before live polling", connection.commandLog.contains("0100"))
        assertTrue("deferred VIN probe must run after first telemetry", connection.commandLog.contains("0902"))
        assertTrue("batch support probe must run after first telemetry", connection.commandLog.contains("010D0C"))

        val summary = JSONObject(reader.storageSummaryJson())
        assertEquals(1, summary.optInt("sessionCount"))
        assertEquals(session.id, summary.optLong("lastSessionId"))
        assertEquals(ObdLocalStore.MODE_OBD, summary.optString("lastMode"))
        assertEquals(ObdLocalStore.STATUS_COMPLETE, summary.optString("lastStatus"))
        assertTrue("summary should count raw telemetry", summary.optLong("rawTelemetryCount") >= 7L)
        assertTrue("summary should count useful telemetry", summary.optLong("sampleCount") >= 7L)

        val details = JSONObject(reader.storageDetailsJson())
        val review = details.getJSONObject("latestReview")
        assertEquals(session.id, review.getJSONObject("session").optLong("id"))
        assertTrue("review should count useful persisted samples", review.optLong("usefulTelemetryCount") >= 7L)
        assertTrue("review should report the simulated moving speed", review.optInt("maxSpeedKph") >= 48)

        val route = details.getJSONObject("latestRoute")
        val points = route.getJSONArray("points")
        assertEquals(route.optInt("pointCount"), points.length())
        assertTrue("dashboard route should include multiple GPS-bearing samples", route.optInt("pointCount") >= 3)
        assertTrue("route should accumulate non-zero distance", route.optDouble("distanceMeters") > 0.0)

        val trips = JSONArray(reader.tripsJson())
        assertTrue("moving scenario should appear in the trip list", trips.length() >= 1)
        val trip = trips.getJSONObject(0)
        assertEquals(session.id, trip.optLong("sessionId"))
        assertTrue("trip row should retain a route", trip.optBoolean("hasRoute"))
        assertTrue("trip row should expose points", trip.optInt("pointCount") >= 3)
    }

    private fun scriptMovingVoltResponses() {
        connection.defaultResponse = ">"
        connection.responses["0100"] = "41 00 FF FF FF FF\r>"
        connection.responses["010D"] = "41 0D 30\r>"
        connection.responses["010C"] = "41 0C 1F 40\r>"
        connection.responses["0149"] = "41 49 40\r>"
        connection.responses["0104"] = "41 04 40\r>"
        connection.responses["0111"] = "41 11 32\r>"
        connection.responses["015B"] = "41 5B C0\r>"
        connection.responses["ATRV"] = "14.1V\r>"
        connection.responses["0142"] = "41 42 38 34\r>"
        connection.responses["222414"] = "62 24 14 00 64\r>"
        connection.responses["222429"] = "62 24 29 0D AC\r>"
        connection.responses["010D0C"] = "41 0D 30 41 0C 1F 40\r>"
        connection.responses["010D0C49"] = "41 0D 30 41 0C 1F 40 41 49 40\r>"
        connection.responses["0902"] = mode09VinResponse("1G1ZD5ST8JF" + "202020")
    }

    private fun connectIntent(): Intent =
        Intent(service, ScenarioService::class.java).apply {
            action = ObdService.ACTION_CONNECT
            putExtra(ObdService.EXTRA_ADDRESS, ADAPTER_ADDRESS)
            putExtra(ObdService.EXTRA_NAME, ADAPTER_NAME)
        }

    private fun waitForFinalizedSession(): ObdSessionRecord {
        var latest: ObdSessionRecord? = null
        waitFor("session to finalize with persisted telemetry") {
            latest = service.localStore!!.getRecentSessions(1).firstOrNull()
            latest?.status == ObdLocalStore.STATUS_COMPLETE && latest!!.sampleCount >= 7
        }
        return latest!!
    }

    private fun waitFor(
        label: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(20)
        }
        fail("Timed out waiting for $label")
    }

    class ScenarioEngine(
        private val testService: ScenarioService,
        connection: ScriptedElmConnection,
    ) : ObdPollingEngine(testService, LoopSleeper { true }) {
        val openCount = AtomicInteger()

        init {
            setConnectionForTest(connection)
        }

        override fun isBluetoothReady(): Boolean = true

        override fun openBluetoothSocket(address: String?) {
            openCount.incrementAndGet()
        }
    }

    open class ScenarioService : ObdService() {
        lateinit var scenarioEngine: ScenarioEngine

        override fun createPollingEngine(): ObdPollingEngine {
            val connection = nextConnection ?: error("Scenario connection must be installed before onCreate")
            scenarioEngine = ScenarioEngine(this, connection)
            return scenarioEngine
        }

        companion object {
            @Volatile
            var nextConnection: ScriptedElmConnection? = null
        }
    }

    class ScriptedElmConnection : ElmConnection() {
        val closeCalls = AtomicInteger()
        val commandLog: MutableList<String> = Collections.synchronizedList(ArrayList())
        val responses = LinkedHashMap<String, String>()

        @Volatile
        var defaultResponse = ">"

        private val afterCommandHooks = LinkedHashMap<String, Runnable>()

        fun afterCommand(
            command: String,
            hook: Runnable,
        ) {
            afterCommandHooks[command] = hook
        }

        override fun `open`(
            device: BluetoothDevice,
            uuid: UUID,
            connectTimeoutMs: Long,
        ) = Unit

        override fun wakeNudge(toleranceMs: Long): WakeNudgeResult {
            firstReadMs = 0L
            return WakeNudgeResult(0L, true)
        }

        @Throws(IOException::class)
        override fun transact(
            command: String,
            timeoutMs: Long,
            keepWaiting: KeepWaiting,
        ): String {
            commandLog.add(command)
            val response = responses[command] ?: defaultResponse
            afterCommandHooks[command]?.run()
            return response
        }

        override fun sendEscape(settleMs: Long) = Unit

        override fun close() {
            closeCalls.incrementAndGet()
        }
    }

    private class RouteLocationTracker : LocationTracker {
        private val fixes =
            listOf(
                fix(syntheticLat(0.0), syntheticLng(0.0), 48.0, 92.0),
                fix(syntheticLat(0.00055), syntheticLng(0.00055), 48.0, 96.0),
                fix(syntheticLat(0.00110), syntheticLng(0.00110), 48.0, 102.0),
                fix(syntheticLat(0.00165), syntheticLng(0.00165), 48.0, 108.0),
                fix(syntheticLat(0.00220), syntheticLng(0.00220), 48.0, 113.0),
                fix(syntheticLat(0.00275), syntheticLng(0.00275), 48.0, 118.0),
                fix(syntheticLat(0.00330), syntheticLng(0.00330), 48.0, 123.0),
                fix(syntheticLat(0.00385), syntheticLng(0.00385), 48.0, 128.0),
            )
        private val nextIndex = AtomicInteger()

        override fun start(listener: LocationTracker.Listener) = Unit

        override fun stop() = Unit

        override fun getLastLocation(): FilteredLocation {
            val index = nextIndex.getAndUpdate { current -> minOf(current + 1, fixes.lastIndex) }
            return fixes[index]
        }

        companion object {
            private fun syntheticLat(offset: Double): Double = 10.0 + offset

            private fun syntheticLng(offset: Double): Double = 20.0 + offset

            private fun fix(
                latitude: Double,
                longitude: Double,
                speedKph: Double,
                bearingDeg: Double,
            ): FilteredLocation =
                FilteredLocation(
                    latitude,
                    longitude,
                    4.0,
                    120.0,
                    speedKph / 3.6,
                    bearingDeg,
                    System.currentTimeMillis(),
                    0L,
                    null,
                    "scenario",
                )
        }
    }

    private companion object {
        private const val ADAPTER_ADDRESS = "AA:BB:CC:DD:EE:FF"
        private const val ADAPTER_NAME = "Scenario ELM"
        private const val WAIT_TIMEOUT_MS = 10_000L

        private fun mode09VinResponse(vin: String): String {
            val hex = StringBuilder("490201")
            for (c in vin.toCharArray()) {
                hex.append("%02X".format(c.code))
            }
            return hex.append("\r>").toString()
        }
    }
}
