package com.volttracker.obdpoc

import android.os.Looper
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Exercises [VoltBridgeSignalLogs] directly against the [DashboardHost] seam, using the shared
 * [DataBridgeRecordingActivity] fixture. Focus is the positive-id parsing, the
 * `localStore == null`/`isOpen` teardown guards, and the ready/blocked status the delete publishes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoltBridgeSignalLogsTest {
    private var controller: ActivityController<DataBridgeRecordingActivity>? = null
    private lateinit var activity: DataBridgeRecordingActivity
    private lateinit var signalLogs: VoltBridgeSignalLogs

    @Before
    fun setUp() {
        val controller = Robolectric.buildActivity(DataBridgeRecordingActivity::class.java).create()
        this.controller = controller
        activity = controller.get()
        signalLogs = VoltBridgeSignalLogs(activity)
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

    // ---- exportDetailedSignalLog -------------------------------------------------------------

    @Test
    fun exportDetailedSignalLogReturnsStoreExportForValidId() {
        activity.store.singleExport = JSONObject().put("ok", true).put("id", 7)

        val payload = JSONObject(signalLogs.exportDetailedSignalLog("7"))

        assertEquals(7L, activity.store.lastSingleExportId)
        assertTrue(payload.optBoolean("ok"))
        assertEquals(7, payload.optInt("id"))
    }

    @Test
    fun exportDetailedSignalLogRejectsNonPositiveIdBeforeTouchingStore() {
        val payload = JSONObject(signalLogs.exportDetailedSignalLog("-5"))

        assertEquals(Long.MIN_VALUE, activity.store.lastSingleExportId)
        assertFalse(payload.optBoolean("ok", true))
        assertEquals("invalid_id", payload.optString("error"))
    }

    @Test
    fun exportDetailedSignalLogRejectsGarbageId() {
        val payload = JSONObject(signalLogs.exportDetailedSignalLog("not-a-number"))

        assertEquals(Long.MIN_VALUE, activity.store.lastSingleExportId)
        assertEquals("invalid_id", payload.optString("error"))
    }

    @Test
    fun exportDetailedSignalLogDegradesToErrorWhenStoreIsClosed() {
        // Teardown-race contract: a store closed by onDestroy must yield an error payload, not throw.
        activity.store.open = false

        val payload = JSONObject(signalLogs.exportDetailedSignalLog("7"))

        assertEquals("a closed store must not be consulted", Long.MIN_VALUE, activity.store.lastSingleExportId)
        assertEquals("invalid_id", payload.optString("error"))
    }

    @Test
    fun exportDetailedSignalLogDegradesToErrorWhenStorageUnavailable() {
        activity.localStore = null

        val payload = JSONObject(signalLogs.exportDetailedSignalLog("7"))

        assertFalse(payload.optBoolean("ok", true))
        assertEquals("invalid_id", payload.optString("error"))
    }

    // ---- exportDetailedSignalLogs ------------------------------------------------------------

    @Test
    fun exportDetailedSignalLogsReturnsBoundedStoreExport() {
        activity.store.bulkExport = JSONObject().put("ok", true).put("count", 3)

        val payload = JSONObject(signalLogs.exportDetailedSignalLogs())

        // The bridge always asks the store for the most recent 250 capability rows.
        assertEquals(250, activity.store.lastBulkExportLimit)
        assertTrue(payload.optBoolean("ok"))
    }

    @Test
    fun exportDetailedSignalLogsReturnsValidJsonWhenStorageUnavailable() {
        activity.localStore = null

        val payload = JSONObject(signalLogs.exportDetailedSignalLogs())

        assertFalse(payload.optBoolean("ok", true))
        assertEquals("storage_unavailable", payload.optString("error"))
        assertEquals("Local storage is not ready.", payload.optString("message"))
    }

    @Test
    fun exportDetailedSignalLogsReturnsValidJsonWhenStoreIsClosed() {
        activity.store.open = false

        val payload = JSONObject(signalLogs.exportDetailedSignalLogs())

        assertEquals(Int.MIN_VALUE, activity.store.lastBulkExportLimit)
        assertEquals("storage_unavailable", payload.optString("error"))
    }

    // ---- deleteDetailedSignalLog -------------------------------------------------------------

    @Test
    fun deleteDetailedSignalLogRemovesRowAndReportsReadyOnSuccess() {
        activity.store.deleteReturn = 1

        signalLogs.deleteDetailedSignalLog("42")
        drain()

        assertEquals(42L, activity.store.lastDeletedId)
        assertEquals("ready", activity.lastStatusState)
        assertFalse(activity.lastStatusBlocked)
        assertTrue(activity.storageSummaryCalls > 0)
    }

    @Test
    fun deleteDetailedSignalLogReportsBlockedWhenNothingWasRemoved() {
        activity.store.deleteReturn = 0

        signalLogs.deleteDetailedSignalLog("42")
        drain()

        assertEquals(42L, activity.store.lastDeletedId)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    @Test
    fun deleteDetailedSignalLogReportsBlockedWhenStoreThrows() {
        activity.store.throwDeleteEnhancedCapability = true

        signalLogs.deleteDetailedSignalLog("42")
        drain()

        assertEquals(42L, activity.store.lastDeletedId)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }

    @Test
    fun deleteDetailedSignalLogRejectsInvalidIdWithoutTouchingStore() {
        signalLogs.deleteDetailedSignalLog("0")
        drain()

        assertEquals(Long.MIN_VALUE, activity.store.lastDeletedId)
        assertEquals("blocked", activity.lastStatusState)
        assertTrue(activity.lastStatusBlocked)
    }
}
