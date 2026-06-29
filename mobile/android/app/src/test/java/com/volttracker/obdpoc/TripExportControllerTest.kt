package com.volttracker.obdpoc

import android.content.Context
import android.content.Intent
import com.volttracker.obdpoc.data.ObdLocalStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Directly drives [TripExportController] — the export glue the audit weights most (report item D2) —
 * against a fake [DashboardHost] + [ObdLocalStore], rather than only reaching it through the bridge
 * fake in `VoltBridgeDispatchTest`. Pins every `ok=false` error branch by its code, the happy GPX
 * path (honest bytes/pointCount), that a throwing `recordExport` does NOT sink the share, and that a
 * share-launch failure publishes a blocked `share_failed` status. Covers both the single-trip and the
 * new all-trips bulk path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripExportControllerTest {
    private lateinit var context: Context
    private lateinit var host: FakeHost
    private lateinit var controller: TripExportController

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        host = FakeHost()
        controller = TripExportController(context, host)
        wipe(File(context.cacheDir, "trip-exports"))
        // FileProvider caches a per-authority PathStrategy keyed to the cacheDir it first saw. Each
        // Robolectric test gets a fresh temp cacheDir, so a strategy cached by an earlier test points
        // at a stale root and makes buildShareIntent's getUriForFile throw (-> share_failed). Clear it
        // so every test rebuilds against its own cacheDir.
        resetFileProviderCache()
    }

    @After
    fun tearDown() {
        wipe(File(context.cacheDir, "trip-exports"))
    }

    // ---- single-trip error branches ----------------------------------------------------

    @Test
    fun unrecognizedFormatReturnsInvalidFormat() {
        host.store = FakeStore(context)
        val result = parse(controller.exportAndShare("7", "kml"))
        assertFalse(result.optBoolean("ok", true))
        assertEquals("invalid_format", result.optString("error"))
        assertFalse("an invalid format must not launch a share", host.shareLaunched)
    }

    @Test
    fun blankRouteKeyReturnsInvalidId() {
        host.store = FakeStore(context)
        val result = parse(controller.exportAndShare("   ", "gpx"))
        assertFalse(result.optBoolean("ok", true))
        assertEquals("invalid_id", result.optString("error"))
    }

    @Test
    fun missingStoreReturnsStorageUnavailable() {
        host.store = null
        val result = parse(controller.exportAndShare("7", "gpx"))
        assertEquals("storage_unavailable", result.optString("error"))
    }

    @Test
    fun closedStoreReturnsStorageUnavailable() {
        host.store = FakeStore(context).apply { openValue = false }
        val result = parse(controller.exportAndShare("7", "gpx"))
        assertEquals("storage_unavailable", result.optString("error"))
    }

    @Test
    fun routeReadFailureReturnsRouteReadFailed() {
        host.store = FakeStore(context).apply { routeReadThrows = true }
        val result = parse(controller.exportAndShare("7", "gpx"))
        assertEquals("route_read_failed", result.optString("error"))
    }

    @Test
    fun emptyRouteReturnsEmptyRoute() {
        host.store = FakeStore(context).apply { route = emptyRoute() }
        val result = parse(controller.exportAndShare("7", "gpx"))
        assertEquals("empty_route", result.optString("error"))
        assertFalse("an empty route never launches a share", host.shareLaunched)
    }

    // ---- single-trip happy path --------------------------------------------------------

    @Test
    fun validGpxRouteReturnsOkWithHonestBytesAndPointCount() {
        host.store = FakeStore(context).apply { route = sampleRoute() }
        val result = parse(controller.exportAndShare("7:10000:30000", "gpx"))

        assertTrue("a valid route succeeds", result.optBoolean("ok"))
        assertEquals("gpx", result.optString("format"))
        assertEquals("the honest sample count is returned", 3, result.optInt("pointCount"))
        assertTrue("a real byte size is reported", result.optLong("bytes") > 0L)
        assertTrue("the GPX file name is returned", result.optString("fileName").endsWith(".gpx"))

        assertTrue("a successful export launches the share chooser", host.shareLaunched)
        assertEquals("ready", host.lastStatusState)
        assertFalse(host.lastStatusBlocked)
        assertEquals("the export is recorded best-effort", 1, host.store!!.recordExportCalls)
    }

    @Test
    fun validCsvRouteReturnsCsvFormat() {
        host.store = FakeStore(context).apply { route = sampleRoute() }
        val result = parse(controller.exportAndShare("7", "csv"))
        assertTrue(result.optBoolean("ok"))
        assertEquals("csv", result.optString("format"))
        assertTrue(result.optString("fileName").endsWith(".csv"))
    }

    @Test
    fun recordExportThrowingDoesNotSinkTheShare() {
        host.store =
            FakeStore(context).apply {
                route = sampleRoute()
                recordExportThrows = true
            }
        val result = parse(controller.exportAndShare("7", "gpx"))

        // Recording is best-effort bookkeeping: its failure must be swallowed and the share still go out.
        assertTrue("a throwing recordExport must not fail the export", result.optBoolean("ok"))
        assertTrue("the share still launches despite the recording failure", host.shareLaunched)
        assertEquals("ready", host.lastStatusState)
    }

    @Test
    fun shareLaunchFailurePublishesBlockedShareFailed() {
        host.store = FakeStore(context).apply { route = sampleRoute() }
        host.startActivityThrows = true

        val result = parse(controller.exportAndShare("7", "gpx"))

        // The synchronous result is still ok (file written); the UI-thread share launch then fails and
        // surfaces a blocked status to the dashboard.
        assertTrue(result.optBoolean("ok"))
        assertEquals("blocked", host.lastStatusState)
        assertTrue(host.lastStatusBlocked)
        assertEquals(
            context.getString(R.string.status_trip_export_share_failed),
            host.lastStatusDetail,
        )
    }

    // ---- all-trips bulk path -----------------------------------------------------------

    @Test
    fun allTripsExportWithNoTripsReturnsEmptyRoute() {
        host.store = FakeStore(context).apply { allTrips = JSONArray() }
        val result = parse(controller.exportAndShare(null, "csv_all"))
        assertEquals("empty_route", result.optString("error"))
        assertFalse(host.shareLaunched)
    }

    @Test
    fun allTripsExportWithTripsReturnsOkCsvWithTripAndPointCounts() {
        host.store = FakeStore(context).apply { allTrips = sampleAllTrips() }
        val result = parse(controller.exportAndShare(null, "csv_all"))

        assertTrue("the bulk export succeeds", result.optBoolean("ok"))
        assertEquals("csv", result.optString("format"))
        assertEquals("both trips are counted", 2, result.optInt("tripCount"))
        assertEquals("the combined sample count is returned", 5, result.optInt("pointCount"))
        assertTrue(result.optLong("bytes") > 0L)
        assertTrue(result.optString("fileName").startsWith("volt-all-trips-"))

        assertTrue(host.shareLaunched)
        assertEquals("ready", host.lastStatusState)
        assertEquals("the bulk export is recorded", 1, host.store!!.recordAllTripsExportCalls)
    }

    @Test
    fun allTripsExportWithMissingStoreReturnsStorageUnavailable() {
        host.store = null
        val result = parse(controller.exportAndShare(null, "  CSV_ALL  "))
        assertEquals("storage_unavailable", result.optString("error"))
    }

    // ---- charge-sessions bulk path (M1) ------------------------------------------------

    @Test
    fun chargeSessionsExportWithNoChargesReturnsEmptyRoute() {
        host.store = FakeStore(context).apply { chargeRows = JSONArray() }
        val result = parse(controller.exportAndShare(null, "csv_charges"))
        assertEquals("empty_route", result.optString("error"))
        assertFalse(host.shareLaunched)
    }

    @Test
    fun chargeSessionsExportWithMissingStoreReturnsStorageUnavailable() {
        host.store = null
        val result = parse(controller.exportAndShare("0.15", "csv_charges"))
        assertEquals("storage_unavailable", result.optString("error"))
    }

    @Test
    fun chargeSessionsExportWithChargesReturnsOkCsvWithSessionCount() {
        host.store = FakeStore(context).apply { chargeRows = sampleChargeRows() }
        // No rate in the routeKey slot → no cost column.
        val result = parse(controller.exportAndShare(null, "csv_charges"))

        assertTrue("the charge export succeeds", result.optBoolean("ok"))
        assertEquals("csv", result.optString("format"))
        assertEquals("both charges are counted", 2, result.optInt("sessionCount"))
        assertFalse("no rate → no cost column", result.optBoolean("withCost"))
        assertTrue(result.optLong("bytes") > 0L)
        assertTrue(result.optString("fileName").startsWith("volt-charges-"))

        assertTrue(host.shareLaunched)
        assertEquals("ready", host.lastStatusState)
        assertEquals("the charge export is recorded", 1, host.store!!.recordAllTripsExportCalls)
    }

    @Test
    fun chargeSessionsExportWithValidRateSetsWithCost() {
        host.store = FakeStore(context).apply { chargeRows = sampleChargeRows() }
        val result = parse(controller.exportAndShare("0.155", "csv_charges"))

        assertTrue(result.optBoolean("ok"))
        assertTrue("a positive rate enables the cost column", result.optBoolean("withCost"))
    }

    @Test
    fun chargeSessionsExportWithInvalidRateOmitsCost() {
        host.store = FakeStore(context).apply { chargeRows = sampleChargeRows() }
        // A non-numeric / non-positive rate must degrade to "no cost column", never throw.
        for (badRate in listOf("not-a-number", "0", "-0.2", "")) {
            val result = parse(controller.exportAndShare(badRate, "csv_charges"))
            assertTrue("invalid rate '$badRate' still exports", result.optBoolean("ok"))
            assertFalse("invalid rate '$badRate' omits the cost column", result.optBoolean("withCost"))
        }
    }

    private fun parse(json: String): JSONObject = JSONObject(json)

    /**
     * A [DashboardHost] whose only live members are the four [TripExportController] touches
     * (`localStore`, `runOnUiThread`, `startActivity`, `publishStatus`); every other member throws so
     * an unexpected reach is caught. `runOnUiThread` runs inline so the posted share body completes
     * before the test asserts.
     */
    private class FakeHost : DashboardHost {
        var store: FakeStore? = null
        var shareLaunched = false
        var startActivityThrows = false
        var lastStatusState: String? = null
        var lastStatusDetail: String? = null
        var lastStatusBlocked = false

        override val localStore: ObdLocalStore? get() = store

        override fun runOnUiThread(action: Runnable) = action.run()

        override fun startActivity(intent: Intent?) {
            if (startActivityThrows) {
                throw IllegalStateException("no activity to handle the chooser")
            }
            shareLaunched = true
        }

        override fun publishStatus(
            state: String?,
            detail: String?,
            blocked: Boolean,
        ) {
            lastStatusState = state
            lastStatusDetail = detail
            lastStatusBlocked = blocked
        }

        private fun unused(): Nothing = throw UnsupportedOperationException("not exercised by TripExportController")

        override fun runOnBackground(task: Runnable) = unused()

        override fun confirmBridgeAction(
            title: String,
            message: String,
            positiveLabel: String,
            onConfirmed: Runnable,
        ) = unused()

        override fun requireDeviceCatalog() = unused()

        override fun rememberDevice(
            address: String?,
            name: String?,
        ) = unused()

        override fun startObdService(
            action: String?,
            address: String?,
            name: String?,
        ) = unused()

        override fun startObdService(
            action: String?,
            address: String?,
            name: String?,
            detailStage: String?,
        ) = unused()

        override fun stopObdService() = unused()

        override fun isLoggingActive() = unused()

        override fun requirePermissionGate() = unused()

        override fun getAutoConnectStateJson() = unused()

        override fun setAutoConnectEnabledFromBridge(enabled: Boolean) = unused()

        override fun requireDataBackup() = unused()

        override fun requireBackupController() = unused()

        override fun forceStopPackageFromBridge(packageName: String?) = unused()

        override fun cancelRetryFromBridge() = unused()

        override fun openBluetoothSettingsFromBridge() = unused()

        override fun getRecentSessionsJson(n: Int) = unused()

        override fun shareDiagnosticsFromBridge() = unused()

        override fun shareDiagnosticsDigestFromBridge() = unused()

        override fun startTestConnectionFromBridge() = unused()

        override fun scheduleAdapterReadyNotifyFromBridge(mins: Int) = unused()

        override fun cancelAdapterReadyNotifyFromBridge() = unused()

        override fun openSetupGuideFromBridge() = unused()

        override fun onDashboardReady() = unused()

        override fun publishDeviceList() = unused()

        override fun publishStorageSummary() = unused()

        override fun publishDashboardPayload(
            functionName: String,
            jsonPayload: String?,
        ) = unused()

        override fun publishRestoreProgress(
            visible: Boolean,
            busy: Boolean,
            title: String?,
            detail: String?,
            tone: String?,
            phase: String?,
            bytesDone: Long,
            bytesTotal: Long,
            rowsDone: Long,
            rowsTotal: Long,
            percent: Int,
            etaSeconds: Long,
            operation: String?,
        ) = unused()

        override fun getStorageSummaryJson() = unused()

        override fun getStorageDetailsJson() = unused()

        override fun getAppStateJson() = unused()

        override fun getTripsJson() = unused()

        override fun getInsightsJson() = unused()

        override fun getTripRouteJson(routeKey: String?) = unused()

        override fun exportTripFromBridge(
            routeKey: String?,
            format: String?,
        ) = unused()

        override fun getCurrentSessionRouteJson() = unused()

        override fun getBatterySohHistoryJson() = unused()

        override fun eventNotifications() = unused()
    }

    /** A store that serves canned route/all-trips data and records (or fails) the bookkeeping calls. */
    private class FakeStore(
        context: Context,
    ) : ObdLocalStore(context) {
        var openValue = true
        var route: JSONObject = sampleRoute()
        var allTrips: JSONArray = JSONArray()
        var chargeRows: JSONArray = JSONArray()
        var routeReadThrows = false
        var recordExportThrows = false
        var recordExportCalls = 0
        var recordAllTripsExportCalls = 0

        override val isOpen: Boolean get() = openValue

        override fun getTripRouteJson(routeKey: String?): JSONObject {
            if (routeReadThrows) {
                throw IllegalStateException("route read failed")
            }
            return route
        }

        override fun getAllTripsForExportJson(
            tripLimit: Int,
            pointLimit: Int,
        ): JSONArray = allTrips

        // Serve canned charge rows without standing up a real DB: the charge export reads through the
        // projections() sub-accessor (ObdLocalStore is at the detekt function ceiling), so the fake
        // returns a StoreProjections subclass that overrides only chargeSessionsForExport.
        override fun projections(): StoreProjections =
            object : StoreProjections() {
                override fun chargeSessionsForExport(limit: Int): JSONArray = chargeRows
            }

        override fun recordExport(
            routeKey: String?,
            exportType: String,
            fileName: String,
            mimeType: String,
            bytes: Long,
        ): Long {
            recordExportCalls += 1
            if (recordExportThrows) {
                throw IllegalStateException("recordExport failed")
            }
            return 1L
        }

        override fun recordAllTripsExport(
            exportType: String,
            fileName: String,
            mimeType: String,
            bytes: Long,
        ): Long {
            recordAllTripsExportCalls += 1
            return 1L
        }
    }

    private companion object {
        private fun sampleRoute(): JSONObject {
            val points = JSONArray()
            points.put(
                JSONObject()
                    .put("atMs", 10_000L)
                    .put("lat", 34.05)
                    .put("lng", -118.25)
                    .put("speedMps", 10.0),
            )
            points.put(
                JSONObject()
                    .put("atMs", 20_000L)
                    .put("lat", 34.16)
                    .put("lng", -118.32)
                    .put("speedMps", 11.0),
            )
            points.put(
                JSONObject()
                    .put("atMs", 30_000L)
                    .put("lat", 34.0)
                    .put("lng", -118.32)
                    .put("speedMps", 12.0),
            )
            return JSONObject()
                .put(
                    "session",
                    JSONObject().put("id", "7:10000:30000").put("sessionId", 7).put("adapterName", "OBDLink"),
                ).put("points", points)
        }

        private fun emptyRoute(): JSONObject =
            JSONObject().put("session", JSONObject().put("id", "7:0:0")).put("points", JSONArray())

        private fun sampleAllTrips(): JSONArray {
            val trips = JSONArray()
            trips.put(JSONObject().put("tripId", 7).put("label", "Commute").put("route", sampleRoute()))
            // A second trip with 2 points so the combined point count is 3 + 2 = 5.
            val secondPoints = JSONArray()
            secondPoints.put(
                JSONObject()
                    .put("atMs", 40_000L)
                    .put("lat", 34.1)
                    .put("lng", -118.45)
                    .put("speedMps", 9.0),
            )
            secondPoints.put(
                JSONObject()
                    .put("atMs", 50_000L)
                    .put("lat", 34.2)
                    .put("lng", -118.50)
                    .put("speedMps", 8.0),
            )
            val secondRoute =
                JSONObject()
                    .put("session", JSONObject().put("id", "8:40000:50000").put("sessionId", 8))
                    .put("points", secondPoints)
            trips.put(JSONObject().put("tripId", 8).put("label", "Errand").put("route", secondRoute))
            return trips
        }

        private fun sampleChargeRows(): JSONArray {
            val rows = JSONArray()
            rows.put(
                JSONObject()
                    .put("id", 1)
                    .put("startedAtMs", 10_000L)
                    .put("endedAtMs", 30_000L)
                    .put("startSoc", 40.0)
                    .put("endSoc", 80.0)
                    .put("energyKwh", 6.0)
                    .put("powerKw", 7.2)
                    .put("chargerType", "L2")
                    .put("confidence", 0.9),
            )
            rows.put(
                JSONObject()
                    .put("id", 2)
                    .put("startedAtMs", 100_000L)
                    .put("endedAtMs", 200_000L)
                    .put("startSoc", 20.0)
                    .put("endSoc", 55.0)
                    .put("energyKwh", 4.5)
                    .put("chargerType", "L1")
                    .put("confidence", 0.7),
            )
            return rows
        }

        private fun resetFileProviderCache() {
            try {
                val field = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
                field.isAccessible = true
                (field.get(null) as? MutableMap<*, *>)?.clear()
            } catch (_: ReflectiveOperationException) {
                // Field renamed in a future androidx version: the share-intent tests may become
                // order-sensitive again, but we don't want to mask that with a crash here.
            }
        }

        private fun wipe(dir: File) {
            if (dir.isDirectory) {
                dir.listFiles()?.forEach { it.delete() }
                dir.delete()
            }
        }
    }
}
