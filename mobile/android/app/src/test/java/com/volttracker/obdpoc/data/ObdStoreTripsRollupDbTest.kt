package com.volttracker.obdpoc.data

import android.content.ContentValues
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

/**
 * Robolectric coverage for the per-session insights rollup cache (schema v9) behind
 * [ObdLocalStore.getInsightsJson]. The cache must preserve the original one-session-=-one-trip
 * numbers exactly while making repeated reads cheap: closed sessions are cached, the active session
 * is folded in live, and a wipe clears everything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObdStoreTripsRollupDbTest {
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

    private fun gpsSample(
        speedKph: Int,
        lat: Double,
        lng: Double,
        atMs: Long,
    ): JSONObject {
        val sample = JSONObject()
        sample.put("source", "obd")
        sample.put("speedKph", speedKph)
        sample.put("rpm", 1500)
        sample.put("latitude", lat)
        sample.put("longitude", lng)
        sample.put("accuracyM", 5.0)
        sample.put("updatedAt", atMs)
        return sample
    }

    /** Seeds one closed OBD trip with two GPS fixes and returns the session id. */
    private fun seedClosedTrip(
        lat0: Double,
        lng0: Double,
        lat1: Double,
        lng1: Double,
        startMs: Long,
    ): Long {
        val id = store.startSession("obd", "00:11", "Adapter")
        store.recordTelemetry(id, gpsSample(40, lat0, lng0, startMs))
        store.recordTelemetry(id, gpsSample(50, lat1, lng1, startMs + 1000L))
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, startMs + 2000L, "")
        return id
    }

    @Test
    fun repeatedReadsReturnIdenticalInsights() {
        seedClosedTrip(34.05, -118.25, 34.06, -118.25, 1000L)
        seedClosedTrip(34.16, -118.40, 34.17, -118.40, 10_000L)

        val first = store.getInsightsJson()
        val second = store.getInsightsJson()

        // Idempotent: the second read comes entirely from the cache and matches the first.
        assertEquals(first.toString(), second.toString())
        assertEquals(2, first.getInt("tripCount"))
        assertTrue(first.getDouble("totalDistanceMeters") > 0)
    }

    @Test
    fun newlyClosedSessionIsBackfilledOnNextRead() {
        seedClosedTrip(34.05, -118.25, 34.06, -118.25, 1000L)
        val afterOne = store.getInsightsJson()
        assertEquals(1, afterOne.getInt("tripCount"))
        val distanceOne = afterOne.getDouble("totalDistanceMeters")

        // A second drive closed after the cache was first built must be picked up.
        seedClosedTrip(34.16, -118.40, 34.18, -118.40, 10_000L)
        val afterTwo = store.getInsightsJson()
        assertEquals(2, afterTwo.getInt("tripCount"))
        assertTrue(afterTwo.getDouble("totalDistanceMeters") > distanceOne)
    }

    @Test
    fun staleRollupVersionIsRefreshedOnRead() {
        val context = RuntimeEnvironment.getApplication()
        val session = seedClosedTrip(34.05, -118.25, 34.07, -118.25, 1000L)
        VoltTrackerDb(context).use { helper ->
            val db = helper.writableDatabase
            val stale = ContentValues()
            stale.put("session_id", session)
            stale.put("counted", 1)
            stale.put("distance_m", 1.0)
            stale.put("duration_ms", 1L)
            stale.put("max_speed_kph", 1)
            stale.put("has_route", 0)
            stale.put("started_at_ms", 1000L)
            stale.put("rollup_version", 0)
            db.insertWithOnConflict(
                VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS,
                null,
                stale,
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }

        val insights = store.getInsightsJson()

        assertTrue(insights.getDouble("totalDistanceMeters") > 1.0)
        VoltTrackerDb(context).use { helper ->
            helper
                .readableDatabase
                .rawQuery(
                    "SELECT rollup_version FROM " +
                        VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS +
                        " WHERE session_id = ?",
                    arrayOf(session.toString()),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    // Refreshed to the current ROLLUP_CACHE_VERSION (bumped to 8 when per-trip
                    // avgOutsideTempC joined energyKwh/evShare in the cached trip JSON).
                    assertEquals(8, cursor.getInt(0))
                }
        }
    }

    @Test
    fun activeSessionIsCountedLiveThenStaysStableOnceClosed() {
        // An open (unfinished) session is folded in live, exactly as the old per-session loop did.
        val open = store.startSession("obd", "00:11", "Adapter")
        store.recordTelemetry(open, gpsSample(40, 34.05, -118.25, 1000L))
        store.recordTelemetry(open, gpsSample(55, 34.07, -118.25, 2000L))

        val whileOpen = store.getInsightsJson()
        assertEquals(1, whileOpen.getInt("tripCount"))
        val openDistance = whileOpen.getDouble("totalDistanceMeters")
        assertTrue(openDistance > 0)

        store.finishSession(open, ObdLocalStore.STATUS_COMPLETE, 3000L, "")
        val afterClose = store.getInsightsJson()
        assertEquals(1, afterClose.getInt("tripCount"))
        // Same fixes, now read from the cache — distance is unchanged.
        assertEquals(openDistance, afterClose.getDouble("totalDistanceMeters"), 0.5)
    }

    @Test
    fun clearAllDataResetsInsights() {
        seedClosedTrip(34.05, -118.25, 34.06, -118.25, 1000L)
        assertEquals(1, store.getInsightsJson().getInt("tripCount"))

        store.clearAllData()
        val empty = store.getInsightsJson()
        assertEquals(0, empty.getInt("tripCount"))
        assertEquals(0.0, empty.getDouble("totalDistanceMeters"), 0.0001)
    }
}
