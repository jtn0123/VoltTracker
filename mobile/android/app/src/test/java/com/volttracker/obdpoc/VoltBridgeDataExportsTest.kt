package com.volttracker.obdpoc

import android.content.Context
import android.os.Bundle
import android.os.Looper
import com.volttracker.obdpoc.data.ObdLocalStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Exercises the store-backed surface of [VoltBridgeDataExports] directly against the
 * [DashboardHost]/[BridgeStateProvider] seam, mirroring the fake-host construction in
 * [VoltBridgeDispatchTest]. Focus is the export/delete/clear/markTrip logic the sub-bridge owns:
 * the positive-id parsing, the `localStore == null`/`isOpen` teardown guards, the logging-active
 * gate, and the ready/blocked status it publishes. (The backup paths — exportDebugBundle,
 * shareBackup, restore* — route through the concrete DataBackup/BackupController collaborators that
 * only exist after a full Activity onCreate, so they are out of scope for this unit test.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoltBridgeDataExportsTest {
    private var controller: ActivityController<RecordingActivity>? = null
    private lateinit var activity: RecordingActivity
    private lateinit var dataExports: VoltBridgeDataExports

    @Before
    fun setUp() {
        val controller = Robolectric.buildActivity(RecordingActivity::class.java).create()
        this.controller = controller
        activity = controller.get()
        dataExports = VoltBridgeDataExports(activity, activity)
    }

    @After
    fun tearDown() {
        activity.store.close()
        try {
            controller?.destroy()
        } catch (ignored: RuntimeException) {
            // Activity teardown can race in Robolectric; it must not fail the assertions above.
        }
    }

    /** Drains the main looper so the `runOnUiThread` body the bridge posts actually executes. */
    private fun drain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    // ---- clearStoredData ---------------------------------------------------------------------

    @Test
    fun clearStoredDataConfirmsClearsStoreAndReportsReadyWhenIdle() {
        activity.loggingActive = false

        dataExports.clearStoredData()
        drain()

        assertEquals("Clear stored data?", activity.lastConfirmationTitle)
        assertEquals(1, activity.store.clearAllDataCalls)
        assertEquals("ready", activity.lastStatusState)
        assertFalse(activity.lastStatusBlocked)
        assertTrue("storage summary should be refreshed after a clear", activity.storageSummaryCalls > 0)
    }

    @Test
    fun clearStoredDataBlocksWithoutConfirmingOrTouchingStoreWhileLogging() {
        activity.loggingActive = true

        dataExports.clearStoredData()
        drain()

        assertNull("logging gate short-circuits before the confirm dialog", activity.lastConfirmationTitle)
        assertEquals(0, activity.store.clearAllDataCalls)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    // ---- exportDetailedSignalLog -------------------------------------------------------------

    @Test
    fun exportDetailedSignalLogReturnsStoreExportForValidId() {
        activity.store.singleExport = JSONObject().put("ok", true).put("id", 7)

        val payload = JSONObject(dataExports.exportDetailedSignalLog("7"))

        assertEquals(7L, activity.store.lastSingleExportId)
        assertTrue(payload.optBoolean("ok"))
        assertEquals(7, payload.optInt("id"))
    }

    @Test
    fun exportDetailedSignalLogRejectsNonPositiveIdBeforeTouchingStore() {
        val payload = JSONObject(dataExports.exportDetailedSignalLog("-5"))

        assertEquals(Long.MIN_VALUE, activity.store.lastSingleExportId)
        assertFalse(payload.optBoolean("ok", true))
        assertEquals("invalid_id", payload.optString("error"))
    }

    @Test
    fun exportDetailedSignalLogRejectsGarbageId() {
        val payload = JSONObject(dataExports.exportDetailedSignalLog("not-a-number"))

        assertEquals(Long.MIN_VALUE, activity.store.lastSingleExportId)
        assertEquals("invalid_id", payload.optString("error"))
    }

    @Test
    fun exportDetailedSignalLogDegradesToErrorWhenStoreIsClosed() {
        // Teardown-race contract: a store closed by onDestroy must yield an error payload, not throw.
        activity.store.open = false

        val payload = JSONObject(dataExports.exportDetailedSignalLog("7"))

        assertEquals("a closed store must not be consulted", Long.MIN_VALUE, activity.store.lastSingleExportId)
        assertEquals("invalid_id", payload.optString("error"))
    }

    @Test
    fun exportDetailedSignalLogDegradesToErrorWhenStorageUnavailable() {
        activity.localStore = null

        val payload = JSONObject(dataExports.exportDetailedSignalLog("7"))

        assertFalse(payload.optBoolean("ok", true))
        assertEquals("invalid_id", payload.optString("error"))
    }

    // ---- exportDetailedSignalLogs ------------------------------------------------------------

    @Test
    fun exportDetailedSignalLogsReturnsBoundedStoreExport() {
        activity.store.bulkExport = JSONObject().put("ok", true).put("count", 3)

        val payload = JSONObject(dataExports.exportDetailedSignalLogs())

        // The bridge always asks the store for the most recent 250 capability rows.
        assertEquals(250, activity.store.lastBulkExportLimit)
        assertTrue(payload.optBoolean("ok"))
    }

    @Test
    fun exportDetailedSignalLogsReturnsValidJsonWhenStorageUnavailable() {
        activity.localStore = null

        val payload = JSONObject(dataExports.exportDetailedSignalLogs())

        assertFalse(payload.optBoolean("ok", true))
        assertEquals("storage_unavailable", payload.optString("error"))
        assertEquals("Local storage is not ready.", payload.optString("message"))
    }

    @Test
    fun exportDetailedSignalLogsReturnsValidJsonWhenStoreIsClosed() {
        activity.store.open = false

        val payload = JSONObject(dataExports.exportDetailedSignalLogs())

        assertEquals(Int.MIN_VALUE, activity.store.lastBulkExportLimit)
        assertEquals("storage_unavailable", payload.optString("error"))
    }

    // ---- deleteDetailedSignalLog -------------------------------------------------------------

    @Test
    fun deleteDetailedSignalLogRemovesRowAndReportsReadyOnSuccess() {
        activity.store.deleteReturn = 1

        dataExports.deleteDetailedSignalLog("42")
        drain()

        assertEquals(42L, activity.store.lastDeletedId)
        assertEquals("ready", activity.lastStatusState)
        assertFalse(activity.lastStatusBlocked)
        assertTrue(activity.storageSummaryCalls > 0)
    }

    @Test
    fun deleteDetailedSignalLogReportsBlockedWhenNothingWasRemoved() {
        activity.store.deleteReturn = 0

        dataExports.deleteDetailedSignalLog("42")
        drain()

        assertEquals(42L, activity.store.lastDeletedId)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    @Test
    fun deleteDetailedSignalLogRejectsInvalidIdWithoutTouchingStore() {
        dataExports.deleteDetailedSignalLog("0")
        drain()

        assertEquals(Long.MIN_VALUE, activity.store.lastDeletedId)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    // ---- markTripNotTrip ---------------------------------------------------------------------

    @Test
    fun markTripNotTripConfirmsAndRefreshesStorageOnSuccess() {
        activity.store.markTripReturn = true

        dataExports.markTripNotTrip("12:1000:2000")
        drain()

        assertEquals("Mark as not a trip?", activity.lastConfirmationTitle)
        assertEquals("12:1000:2000", activity.store.lastMarkedTripRouteKey)
        assertEquals("ready", activity.lastStatusState)
        assertFalse(activity.lastStatusBlocked)
        assertTrue(activity.storageSummaryCalls > 0)
    }

    @Test
    fun markTripNotTripReportsBlockedWhenStoreReportsNoChange() {
        activity.store.markTripReturn = false

        dataExports.markTripNotTrip("12:1000:2000")
        drain()

        assertEquals("12:1000:2000", activity.store.lastMarkedTripRouteKey)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    @Test
    fun markTripNotTripRejectsBlankRouteKeyWithoutConfirming() {
        dataExports.markTripNotTrip("   ")
        drain()

        assertNull("blank route key short-circuits before the confirm dialog", activity.lastConfirmationTitle)
        assertNull(activity.store.lastMarkedTripRouteKey)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    // ---- setTripLabel (M4) -------------------------------------------------------------------

    @Test
    fun setTripLabelPersistsAndRefreshesStorageOnSuccess() {
        activity.store.setTripLabelReturn = true

        dataExports.setTripLabel("12:1000:2000", "Commute")
        drain()

        assertEquals("12:1000:2000", activity.store.lastLabelRouteKey)
        assertEquals("Commute", activity.store.lastLabelValue)
        assertEquals("ready", activity.lastStatusState)
        assertFalse(activity.lastStatusBlocked)
        assertTrue(activity.storageSummaryCalls > 0)
    }

    @Test
    fun setTripLabelClearsWithEmptyLabel() {
        activity.store.setTripLabelReturn = true

        dataExports.setTripLabel("12:1000:2000", "   ")
        drain()

        // Whitespace-only label is sanitized to empty before reaching the store.
        assertEquals("", activity.store.lastLabelValue)
        assertEquals("ready", activity.lastStatusState)
    }

    @Test
    fun setTripLabelReportsBlockedWhenStoreReportsNoChange() {
        activity.store.setTripLabelReturn = false

        dataExports.setTripLabel("12:1000:2000", "x")
        drain()

        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    @Test
    fun setTripLabelRejectsBlankRouteKeyWithoutTouchingStore() {
        dataExports.setTripLabel("   ", "x")
        drain()

        assertNull(activity.store.lastLabelRouteKey)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    // ---- maintenance log (M5) ----------------------------------------------------------------

    @Test
    fun addMaintenanceEntryParsesJsonAndRefreshesStorageOnSuccess() {
        activity.store.addMaintenanceReturn = 9L

        dataExports.addMaintenanceEntry(
            JSONObject()
                .put("type", "Tire rotation")
                .put("note", "Front to back")
                .put("odometerKm", 12_345.6)
                .put("date", 5_000L)
                .put("intervalKm", 8_000.0)
                .put("intervalMonths", 12)
                .toString(),
        )
        drain()

        assertEquals("Tire rotation", activity.store.lastMaintenanceType)
        assertEquals("Front to back", activity.store.lastMaintenanceNote)
        assertEquals(12_345.6, activity.store.lastMaintenanceOdometerKm!!, 0.001)
        assertEquals(5_000L, activity.store.lastMaintenanceCreatedAtMs)
        // M1/C4: the optional service interval forwards to the store.
        assertEquals(8_000.0, activity.store.lastMaintenanceIntervalKm!!, 0.001)
        assertEquals(12, activity.store.lastMaintenanceIntervalMonths)
        assertEquals("ready", activity.lastStatusState)
        assertTrue(activity.storageSummaryCalls > 0)
    }

    @Test
    fun addMaintenanceEntryDefaultsMissingOdometerToNullAndDateToNow() {
        activity.store.addMaintenanceReturn = 3L

        dataExports.addMaintenanceEntry(JSONObject().put("type", "Oil change").toString())
        drain()

        assertNull("missing odometer must reach the store as null", activity.store.lastMaintenanceOdometerKm)
        assertTrue("missing date defaults to a real timestamp", activity.store.lastMaintenanceCreatedAtMs > 0L)
        // Absent interval fields reach the store as null (no due tracking for this entry).
        assertNull("missing interval_km must reach the store as null", activity.store.lastMaintenanceIntervalKm)
        assertNull("missing interval_months must reach the store as null", activity.store.lastMaintenanceIntervalMonths)
        assertEquals("ready", activity.lastStatusState)
    }

    @Test
    fun addMaintenanceEntryRejectsEmptyTypeAndNoteWithoutTouchingStore() {
        dataExports.addMaintenanceEntry(JSONObject().toString())
        drain()

        assertEquals(Long.MIN_VALUE, activity.store.lastMaintenanceCreatedAtMs)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    @Test
    fun addMaintenanceEntryReportsBlockedWhenStoreInsertFails() {
        activity.store.addMaintenanceReturn = -1L

        dataExports.addMaintenanceEntry(JSONObject().put("type", "Coolant").toString())
        drain()

        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    @Test
    fun getMaintenanceLogReturnsStoreArray() {
        activity.store.maintenanceLog =
            org.json.JSONArray().put(JSONObject().put("id", 1).put("type", "Oil change"))

        val log = org.json.JSONArray(dataExports.getMaintenanceLog())

        assertEquals(1, log.length())
        assertEquals("Oil change", log.getJSONObject(0).optString("type"))
    }

    @Test
    fun getMaintenanceLogReturnsErrorWhenStorageUnavailable() {
        activity.localStore = null

        val result = JSONObject(dataExports.getMaintenanceLog())

        assertEquals(false, result.getBoolean("ok"))
        assertEquals("storage_unavailable", result.getString("error"))
    }

    @Test
    fun getMaintenanceLogReturnsErrorWhenStoreClosed() {
        activity.store.open = false

        val result = JSONObject(dataExports.getMaintenanceLog())

        assertEquals(false, result.getBoolean("ok"))
        assertEquals("storage_unavailable", result.getString("error"))
        assertEquals(Int.MIN_VALUE, activity.store.lastMaintenanceLogLimit)
    }

    @Test
    fun deleteMaintenanceEntryRemovesRowAndReportsReadyOnSuccess() {
        activity.store.deleteMaintenanceReturn = 1

        dataExports.deleteMaintenanceEntry("42")
        drain()

        assertEquals(42L, activity.store.lastDeletedMaintenanceId)
        assertEquals("ready", activity.lastStatusState)
        assertTrue(activity.storageSummaryCalls > 0)
    }

    @Test
    fun deleteMaintenanceEntryRejectsInvalidIdWithoutTouchingStore() {
        dataExports.deleteMaintenanceEntry("0")
        drain()

        assertEquals(Long.MIN_VALUE, activity.store.lastDeletedMaintenanceId)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    /**
     * A [MainActivity] with recording overrides for exactly the data-export seams, modeled on
     * [VoltBridgeDispatchTest.RecordingActivity]. `super.onCreate()` is skipped so no WebView or
     * foreground service starts; only the recording store + the confirm/status/storage hooks are
     * wired up.
     */
    class RecordingActivity : MainActivity() {
        lateinit var store: RecordingStore

        var loggingActive = false
        var storageSummaryCalls = 0

        var lastConfirmationTitle: String? = null

        var lastStatusState: String? = null
        var lastStatusBlocked = false

        override fun onCreate(savedInstanceState: Bundle?) {
            store = RecordingStore(this)
            localStore = store
        }

        override fun runOnBackground(task: Runnable) {
            task.run()
        }

        override fun isLoggingActive(): Boolean = loggingActive

        override fun publishStatus(
            state: String?,
            detail: String?,
            blocked: Boolean,
        ) {
            lastStatusState = state
            lastStatusBlocked = blocked
        }

        override fun publishStorageSummary() {
            storageSummaryCalls += 1
        }

        override fun confirmBridgeAction(
            title: String,
            message: String,
            positiveLabel: String,
            onConfirmed: Runnable,
        ) {
            lastConfirmationTitle = title
            onConfirmed.run()
        }
    }

    /** Records the store mutations the bridge drives and serves canned export payloads. */
    class RecordingStore(
        context: Context,
    ) : ObdLocalStore(context) {
        var open = true

        var clearAllDataCalls = 0

        var deleteReturn = 0
        var lastDeletedId = Long.MIN_VALUE

        var singleExport = JSONObject()
        var lastSingleExportId = Long.MIN_VALUE

        var bulkExport = JSONObject()
        var lastBulkExportLimit = Int.MIN_VALUE

        var markTripReturn = false
        var lastMarkedTripRouteKey: String? = null

        var setTripLabelReturn = false
        var lastLabelRouteKey: String? = null
        var lastLabelValue: String? = null

        var addMaintenanceReturn = 7L
        var lastMaintenanceCreatedAtMs = Long.MIN_VALUE
        var lastMaintenanceOdometerKm: Double? = Double.NaN
        var lastMaintenanceType: String? = null
        var lastMaintenanceNote: String? = null
        var lastMaintenanceIntervalKm: Double? = Double.NaN
        var lastMaintenanceIntervalMonths: Int? = Int.MIN_VALUE

        var maintenanceLog = org.json.JSONArray()
        var lastMaintenanceLogLimit = Int.MIN_VALUE

        var deleteMaintenanceReturn = 0
        var lastDeletedMaintenanceId = Long.MIN_VALUE

        override val isOpen: Boolean
            get() = open

        override fun clearAllData() {
            clearAllDataCalls += 1
        }

        override fun deleteEnhancedCapability(id: Long): Int {
            lastDeletedId = id
            return deleteReturn
        }

        override fun getEnhancedCapabilityExportJson(id: Long): JSONObject {
            lastSingleExportId = id
            return singleExport
        }

        override fun getEnhancedCapabilitiesExportJson(limit: Int): JSONObject {
            lastBulkExportLimit = limit
            return bulkExport
        }

        override fun markTripNotTrip(routeKey: String?): Boolean {
            lastMarkedTripRouteKey = routeKey
            return markTripReturn
        }

        override fun setTripLabel(
            routeKey: String?,
            label: String?,
        ): Boolean {
            lastLabelRouteKey = routeKey
            lastLabelValue = label
            return setTripLabelReturn
        }

        override fun addMaintenanceEntry(
            createdAtMs: Long,
            odometerKm: Double?,
            type: String?,
            note: String?,
            intervalKm: Double?,
            intervalMonths: Int?,
        ): Long {
            lastMaintenanceCreatedAtMs = createdAtMs
            lastMaintenanceOdometerKm = odometerKm
            lastMaintenanceType = type
            lastMaintenanceNote = note
            lastMaintenanceIntervalKm = intervalKm
            lastMaintenanceIntervalMonths = intervalMonths
            return addMaintenanceReturn
        }

        override fun getMaintenanceLogJson(limit: Int): org.json.JSONArray {
            lastMaintenanceLogLimit = limit
            return maintenanceLog
        }

        override fun deleteMaintenanceEntry(id: Long): Int {
            lastDeletedMaintenanceId = id
            return deleteMaintenanceReturn
        }
    }
}
