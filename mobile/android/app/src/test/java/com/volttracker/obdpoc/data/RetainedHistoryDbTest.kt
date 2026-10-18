package com.volttracker.obdpoc.data

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetainedHistoryDbTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var store: ObdLocalStore
    private lateinit var donor: File
    private val old = System.currentTimeMillis() - 100L * 86_400_000L

    @Before
    fun setUp() {
        store = ObdLocalStore(context)
        store.clearAllData()
        donor = context.getDatabasePath("retained-donor.db")
        context.deleteDatabase(donor.name)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase(donor.name)
    }

    private fun twoTrips(): Long =
        ParkStopDrives(store).record(old, parkFromMinute = 10, parkToMinute = 35, endMinute = 45)

    @Test
    fun hideAndRestoreAfterPruningKeepsSiblingTripsAndTotals() {
        twoTrips()
        val before = store.getTripsJson(20)
        assertEquals(2, before.length())
        val key = before.getJSONObject(0).getString("id")
        val total = store.getInsightsJson().getDouble("totalDistanceMeters")
        store.pruneRawDataOlderThan(60)
        assertEquals(2, store.getTripsJson(20).length())
        assertTrue(store.tripEdits.setTripHidden(key, true))
        assertEquals("hiding one retained trip must keep its sibling", 1, store.getTripsJson(20).length())
        assertTrue(store.tripEdits.setTripHidden(key, false))
        assertEquals(2, store.getTripsJson(20).length())
        assertEquals(total, store.getInsightsJson().getDouble("totalDistanceMeters"), 0.001)
    }

    @Test
    fun aTripHiddenBeforePruningCanStillBeRestored() {
        twoTrips()
        val key = store.getTripsJson(20).getJSONObject(0).getString("id")
        store.tripEdits.setTripHidden(key, true)
        assertEquals(1, store.getTripsJson(20).length())
        store.pruneRawDataOlderThan(60)
        store.tripEdits.setTripHidden(key, false)
        assertEquals("hidden facts must survive raw expiry too", 2, store.getTripsJson(20).length())
    }

    @Test
    fun aFinalizedSessionSpanningTheCutoffKeepsAllItsTripFacts() {
        val cutoff = System.currentTimeMillis() - 60L * 86_400_000L
        ParkStopDrives(store).record(cutoff - 20 * 60_000L, parkFromMinute = 10, parkToMinute = 35, endMinute = 45)
        val key = store.getTripsJson(20).getJSONObject(0).getString("id")
        store.pruneRawDataOlderThan(60)
        store.tripEdits.setTripHidden(key, true)
        assertEquals(1, store.getTripsJson(20).length())
        store.tripEdits.setTripHidden(key, false)
        assertEquals(2, store.getTripsJson(20).length())
    }

    @Test
    fun legacyPrunedDonorCachesBecomeRemappedDurableFacts() {
        twoTrips()
        store.pruneRawDataOlderThan(60)
        saveDonor()
        store.clearAllData()
        SQLiteDatabase.openDatabase(donor.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE ${VoltTrackerDb.TABLE_RETAINED_SESSION_HISTORY}")
        }
        assertTrue(store.dbMaintenance.mergeFrom(donor).ok)
        val key = store.getTripsJson(20).getJSONObject(0).getString("id")
        store.tripEdits.setTripHidden(key, true)
        assertEquals(1, store.getTripsJson(20).length())
        store.tripEdits.setTripHidden(key, false)
        assertEquals(2, store.getTripsJson(20).length())
    }

    @Test
    fun aVersion17PrunedBackupCanBeUpgradedThenMergedWithoutLosingHistory() {
        twoTrips()
        store.pruneRawDataOlderThan(60)
        saveDonor()
        store.clearAllData()
        SQLiteDatabase.openDatabase(donor.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE ${VoltTrackerDb.TABLE_RETAINED_SESSION_HISTORY}")
            db.version = 17
        }
        assertEquals(BackupMigrator.Result.MIGRATED, BackupMigrator.migrateToCurrentVersion(context, donor))
        assertTrue(store.dbMaintenance.mergeFrom(donor).ok)
        assertEquals(2, store.getTripsJson(20).length())
        val key = store.getTripsJson(20).getJSONObject(0).getString("id")
        store.tripEdits.setTripHidden(key, true)
        store.tripEdits.setTripHidden(key, false)
        assertEquals(2, store.getTripsJson(20).length())
    }

    @Test
    fun mergingAPrunedBackupRemapsRetainedTripsAndIsIdempotent() {
        twoTrips()
        val originalKey = store.getTripsJson(20).getJSONObject(0).getString("id")
        store.tripEdits.setTripLabel(originalKey, "Retained drive")
        store.pruneRawDataOlderThan(60)
        saveDonor()
        store.clearAllData()
        assertTrue(store.dbMaintenance.mergeFrom(donor).ok)
        val merged = store.getTripsJson(20)
        assertEquals(2, merged.length())
        val labeled =
            (0 until merged.length()).map { merged.getJSONObject(it) }.single {
                it.optString("label") ==
                    "Retained drive"
            }
        assertTrue("session IDs must be rewritten in JSON keys", labeled.getString("id") != originalKey)
        assertEquals(labeled.getLong("sessionId").toString(), labeled.getString("id").substringBefore(":"))
        assertTrue(store.dbMaintenance.mergeFrom(donor).ok)
        assertEquals(2, store.getTripsJson(20).length())
    }

    @Test
    fun mergingAnOverlappingBackupDoesNotEraseRetainedTargetHistory() {
        twoTrips()
        store.pruneRawDataOlderThan(60)
        saveDonor()
        val expected = store.getTripsJson(20).toString()
        assertTrue(store.dbMaintenance.mergeFrom(donor).ok)
        assertEquals(expected, store.getTripsJson(20).toString())
    }

    @Test
    fun malformedRetainedBackupRollsBackTheEntireMerge() {
        twoTrips()
        store.pruneRawDataOlderThan(60)
        saveDonor()
        store.clearAllData()
        SQLiteDatabase.openDatabase(donor.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE ${VoltTrackerDb.TABLE_RETAINED_SESSION_HISTORY} SET trip_json = 'broken'")
        }
        assertTrue(!store.dbMaintenance.mergeFrom(donor).ok)
        assertEquals(0, store.getTripsJson(20).length())
        VoltTrackerDb(context).use { helper ->
            helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM ${VoltTrackerDb.TABLE_SESSIONS}", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("no partial session import", 0L, it.getLong(0))
            }
        }
    }

    @Test
    fun inferredChargeBoundariesSurvivePruningAndRemappedBackupMerge() {
        drive(old, 50)
        drive(old + 3_600_000L, 80)
        assertEquals(1, store.projections().chargeSessionsForExport(20).length())
        store.pruneRawDataOlderThan(60)
        assertEquals(
            "SOC boundaries must outlive raw telemetry",
            1,
            store.projections().chargeSessionsForExport(20).length(),
        )
        saveDonor()
        store.clearAllData()
        assertTrue(store.dbMaintenance.mergeFrom(donor).ok)
        assertEquals(1, store.projections().chargeSessionsForExport(20).length())
    }

    private fun saveDonor() {
        assertTrue(store.checkpoint())
        store.getDatabaseFile().copyTo(donor, overwrite = true)
    }

    private fun drive(
        start: Long,
        soc: Int,
    ) {
        val id = store.startSession("obd", "00:11", "Adapter", start)
        repeat(3) { i ->
            store.recordTelemetry(
                id,
                JSONObject()
                    .put("source", "obd")
                    .put("updatedAt", start + i * 30_000L)
                    .put("speedKph", 45)
                    .put("soc", soc)
                    .put("rpm", 0)
                    .put("latitude", 34.05 + i * 0.002)
                    .put("longitude", -118.25),
            )
        }
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, start + 60_000L, "")
    }
}
