package com.volttracker.obdpoc.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for the user-authored maintenance log (M5) — exercised through
 * [ObdLocalStore.addMaintenanceEntry] / [ObdLocalStore.getMaintenanceLogJson] /
 * [ObdLocalStore.deleteMaintenanceEntry], which run against the real `maintenance_log` table created
 * by [VoltTrackerSchema.createMaintenanceLog] at the current schema version.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObdStoreMaintenanceLogDbTest {
    private lateinit var store: ObdLocalStore

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        store = ObdLocalStore(context)
        store.clearAllData()
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun emptyLogReturnsEmptyArray() {
        assertEquals(0, store.getMaintenanceLogJson(50).length())
    }

    @Test
    fun addMaintenanceEntryPersistsAndIsReturned() {
        val id = store.addMaintenanceEntry(5_000L, 12_345.6, "Tire rotation", "Front to back")
        assertTrue("insert should return a positive row id", id > 0L)

        val log = store.getMaintenanceLogJson(50)
        assertEquals(1, log.length())
        val entry = log.getJSONObject(0)
        assertEquals(id, entry.optLong("id"))
        assertEquals(5_000L, entry.optLong("createdAtMs"))
        assertEquals(12_345.6, entry.optDouble("odometerKm"), 0.001)
        assertEquals("Tire rotation", entry.optString("type"))
        assertEquals("Front to back", entry.optString("note"))
    }

    @Test
    fun nullOdometerIsStoredAsJsonNull() {
        store.addMaintenanceEntry(1_000L, null, "Oil change", "")
        val entry = store.getMaintenanceLogJson(50).getJSONObject(0)
        assertTrue("a missing odometer reading must come back as JSON null", entry.isNull("odometerKm"))
    }

    @Test
    fun serviceIntervalPersistsAndIsReturned() {
        // M1/C4: the optional service interval round-trips through storage and the read JSON.
        val id =
            store.addMaintenanceEntry(
                5_000L,
                16_000.0,
                "Oil change",
                "Synthetic",
                intervalKm = 8_000.0,
                intervalMonths = 12,
            )
        assertTrue(id > 0L)
        val entry = store.getMaintenanceLogJson(50).getJSONObject(0)
        assertEquals(8_000.0, entry.optDouble("intervalKm"), 0.001)
        assertEquals(12, entry.optInt("intervalMonths"))
    }

    @Test
    fun absentIntervalsComeBackAsJsonNull() {
        // A plain history entry (no interval) leaves both interval fields JSON-null, so the
        // dashboard renders no due/overdue line for it.
        store.addMaintenanceEntry(1_000L, null, "Tire rotation", "")
        val entry = store.getMaintenanceLogJson(50).getJSONObject(0)
        assertTrue("absent interval_km must come back as JSON null", entry.isNull("intervalKm"))
        assertTrue("absent interval_months must come back as JSON null", entry.isNull("intervalMonths"))
    }

    @Test
    fun nonPositiveIntervalsAreRejected() {
        // A zero/negative interval can never come "due", so it is treated as absent.
        store.addMaintenanceEntry(1_000L, null, "Brake fluid", "", intervalKm = 0.0, intervalMonths = -3)
        val entry = store.getMaintenanceLogJson(50).getJSONObject(0)
        assertTrue(entry.isNull("intervalKm"))
        assertTrue(entry.isNull("intervalMonths"))
    }

    @Test
    fun getMaintenanceLogReturnsEntriesNewestFirst() {
        store.addMaintenanceEntry(1_000L, null, "First", "")
        store.addMaintenanceEntry(3_000L, null, "Third", "")
        store.addMaintenanceEntry(2_000L, null, "Second", "")

        val log = store.getMaintenanceLogJson(50)
        assertEquals(3, log.length())
        assertEquals("Third", log.getJSONObject(0).optString("type"))
        assertEquals("Second", log.getJSONObject(1).optString("type"))
        assertEquals("First", log.getJSONObject(2).optString("type"))
    }

    @Test
    fun getMaintenanceLogRespectsLimit() {
        for (i in 0 until 5) {
            store.addMaintenanceEntry(1_000L + i, null, "Entry $i", "")
        }
        assertEquals(2, store.getMaintenanceLogJson(2).length())
    }

    @Test
    fun deleteMaintenanceEntryRemovesOnlyThatRow() {
        val keep = store.addMaintenanceEntry(1_000L, null, "Keep", "")
        val drop = store.addMaintenanceEntry(2_000L, null, "Drop", "")

        assertEquals(1, store.deleteMaintenanceEntry(drop))

        val log = store.getMaintenanceLogJson(50)
        assertEquals(1, log.length())
        assertEquals(keep, log.getJSONObject(0).optLong("id"))
        // Deleting a non-existent row is a no-op (0 rows affected).
        assertEquals(0, store.deleteMaintenanceEntry(drop))
    }

    @Test
    fun maintenanceLogSurvivesClearAllData() {
        store.addMaintenanceEntry(1_000L, null, "Coolant flush", "")
        // clearAllData wipes OBD sessions/telemetry but intentionally keeps the user's
        // maintenance log (it is service history, not session-derived data).
        store.clearAllData()
        assertEquals(1, store.getMaintenanceLogJson(50).length())
    }
}
