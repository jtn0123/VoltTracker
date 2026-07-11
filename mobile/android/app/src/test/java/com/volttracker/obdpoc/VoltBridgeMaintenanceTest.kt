package com.volttracker.obdpoc

import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Exercises [VoltBridgeMaintenance] directly against the [DashboardHost] seam, using the shared
 * [DataBridgeRecordingActivity] fixture. Focus is the add-entry form parsing (numeric-field
 * sanitizing, fractional-month rejection, malformed JSON), the store-unavailable/closed guards on
 * the log read, and the ready/blocked status each mutation publishes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoltBridgeMaintenanceTest {
    private var controller: ActivityController<DataBridgeRecordingActivity>? = null
    private lateinit var activity: DataBridgeRecordingActivity
    private lateinit var maintenance: VoltBridgeMaintenance

    @Before
    fun setUp() {
        val controller = Robolectric.buildActivity(DataBridgeRecordingActivity::class.java).create()
        this.controller = controller
        activity = controller.get()
        maintenance = VoltBridgeMaintenance(activity)
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

    @Test
    fun addMaintenanceEntryParsesJsonAndRefreshesStorageOnSuccess() {
        activity.store.addMaintenanceReturn = 9L

        maintenance.addMaintenanceEntry(
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

        maintenance.addMaintenanceEntry(JSONObject().put("type", "Oil change").toString())
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
        maintenance.addMaintenanceEntry(JSONObject().toString())
        drain()

        assertEquals(Long.MIN_VALUE, activity.store.lastMaintenanceCreatedAtMs)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    @Test
    fun addMaintenanceEntryTreatsMalformedJsonAsEmptyForm() {
        maintenance.addMaintenanceEntry("{not-json")
        drain()

        assertEquals(Long.MIN_VALUE, activity.store.lastMaintenanceCreatedAtMs)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    @Test
    fun addMaintenanceEntryReportsBlockedWhenStoreInsertFails() {
        activity.store.addMaintenanceReturn = -1L

        maintenance.addMaintenanceEntry(JSONObject().put("type", "Coolant").toString())
        drain()

        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    @Test
    fun addMaintenanceEntryReportsBlockedWhenStorageUnavailable() {
        activity.localStore = null

        maintenance.addMaintenanceEntry(JSONObject().put("type", "Coolant").toString())
        drain()

        assertEquals(Long.MIN_VALUE, activity.store.lastMaintenanceCreatedAtMs)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    @Test
    fun addMaintenanceEntrySanitizesInvalidNumericFields() {
        activity.store.addMaintenanceReturn = 4L

        maintenance.addMaintenanceEntry(
            JSONObject()
                .put("type", "Brake inspection")
                .put("odometerKm", -5.0)
                .put("intervalKm", 0.0)
                .put("intervalMonths", -1)
                .toString(),
        )
        drain()

        assertNull(activity.store.lastMaintenanceOdometerKm)
        assertNull(activity.store.lastMaintenanceIntervalKm)
        assertNull(activity.store.lastMaintenanceIntervalMonths)
        assertEquals("ready", activity.lastStatusState)
    }

    @Test
    fun addMaintenanceEntryDoesNotTruncateFractionalMonthIntervals() {
        activity.store.addMaintenanceReturn = 5L

        maintenance.addMaintenanceEntry(
            JSONObject()
                .put("type", "Brake inspection")
                .put("intervalMonths", 1.5)
                .toString(),
        )
        drain()

        assertNull(
            "a fractional month must be rejected, not silently truncated to one",
            activity.store.lastMaintenanceIntervalMonths,
        )
    }

    @Test
    fun addMaintenanceEntryReportsBlockedWhenStoreThrows() {
        activity.store.throwAddMaintenance = true

        maintenance.addMaintenanceEntry(JSONObject().put("type", "Coolant").toString())
        drain()

        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    @Test
    fun getMaintenanceLogReturnsStoreArray() {
        activity.store.maintenanceLog =
            JSONArray().put(JSONObject().put("id", 1).put("type", "Oil change"))

        val log = JSONArray(maintenance.getMaintenanceLog())

        assertEquals(1, log.length())
        assertEquals("Oil change", log.getJSONObject(0).optString("type"))
    }

    @Test
    fun getMaintenanceLogReturnsErrorWhenStorageUnavailable() {
        activity.localStore = null

        val result = JSONObject(maintenance.getMaintenanceLog())

        assertEquals(false, result.getBoolean("ok"))
        assertEquals("storage_unavailable", result.getString("error"))
    }

    @Test
    fun getMaintenanceLogReturnsErrorWhenStoreClosed() {
        activity.store.open = false

        val result = JSONObject(maintenance.getMaintenanceLog())

        assertEquals(false, result.getBoolean("ok"))
        assertEquals("storage_unavailable", result.getString("error"))
        assertEquals(Int.MIN_VALUE, activity.store.lastMaintenanceLogLimit)
    }

    @Test
    fun getMaintenanceLogReturnsErrorWhenStoreReadThrows() {
        activity.store.throwMaintenanceLog = true

        val result = JSONObject(maintenance.getMaintenanceLog())

        assertEquals(false, result.getBoolean("ok"))
        assertEquals("maintenance_log_failed", result.getString("error"))
    }

    @Test
    fun deleteMaintenanceEntryRemovesRowAndReportsReadyOnSuccess() {
        activity.store.deleteMaintenanceReturn = 1

        maintenance.deleteMaintenanceEntry("42")
        drain()

        assertEquals(42L, activity.store.lastDeletedMaintenanceId)
        assertEquals("ready", activity.lastStatusState)
        assertTrue(activity.storageSummaryCalls > 0)
    }

    @Test
    fun deleteMaintenanceEntryReportsBlockedWhenNothingWasRemovedOrStoreThrows() {
        activity.store.deleteMaintenanceReturn = 0
        maintenance.deleteMaintenanceEntry("42")
        drain()
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)

        activity.lastStatusState = null
        activity.lastStatusBlocked = false
        activity.store.throwDeleteMaintenance = true
        maintenance.deleteMaintenanceEntry("42")
        drain()
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    @Test
    fun deleteMaintenanceEntryRejectsInvalidIdWithoutTouchingStore() {
        maintenance.deleteMaintenanceEntry("0")
        drain()

        assertEquals(Long.MIN_VALUE, activity.store.lastDeletedMaintenanceId)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }
}
