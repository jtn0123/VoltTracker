package com.volttracker.obdpoc.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.volttracker.obdpoc.materialize.TripSplitRules
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Gear-aware trip splitting through the real SQLite layer: the writer stamps the rules version and
 * gear columns, [DriveWindowDetector] (trip list, route keys, edits) splits on Park, the route
 * projection exposes in-trip Park stops — and a session recorded before the cutover, with the very
 * same rows, keeps exactly the windows and route keys it always had.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GearAwareTripSplitDbTest {
    private lateinit var store: ObdLocalStore

    @Before
    fun setUp() {
        store = ObdLocalStore(RuntimeEnvironment.getApplication())
        store.clearAllData()
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun newSessionsAreStampedWithTheCurrentRules() {
        val id = store.startSession("obd", "00:11", "Adapter", minute(0))
        assertEquals(TripSplitRules.CURRENT, store.getSession(id)!!.tripRulesVersion)
        assertEquals(TripSplitRules.CURRENT, store.getRecentSessions(5).single().tripRulesVersion)
    }

    @Test
    fun writerStoresFreshGearAndDoorState() {
        val id = store.startSession("obd", "00:11", "Adapter", minute(0))
        store.recordTelemetry(id, row(minute(0), 45, PARK, staleMs = 30_000L))
        store.recordTelemetry(id, row(minute(1), 45, PARK, staleMs = 180_000L))
        store.recordTelemetry(id, row(minute(2), 45, null).put("doorFlState", "open"))
        store.recordTelemetry(id, row(minute(3), 45, null).put("doorFlState", "closed").put("trunkState", "closed"))

        withDb { db ->
            val rows = mutableListOf<Pair<Int?, Int?>>()
            db
                .rawQuery(
                    "SELECT prndl_raw, door_open FROM ${VoltTrackerDb.TABLE_TELEMETRY} ORDER BY captured_at_ms",
                    null,
                ).use { c ->
                    while (c.moveToNext()) {
                        rows.add((if (c.isNull(0)) null else c.getInt(0)) to (if (c.isNull(1)) null else c.getInt(1)))
                    }
                }
            assertEquals(listOf<Pair<Int?, Int?>>(PARK to null, null to null, null to 1, null to 0), rows)
        }
    }

    @Test
    fun gearAwareSessionSplitsAtALongParkAndLegacySessionDoesNot() {
        val gearAware = recordParkedWithClimateDrive(minute(0))
        val legacy = recordParkedWithClimateDrive(minute(100), legacy = true)

        withDb { db ->
            val gearWindows = DriveWindowDetector.windowsForSession(db, store.getSession(gearAware))
            val legacyWindows = DriveWindowDetector.windowsForSession(db, store.getSession(legacy))

            assertEquals("15 min in Park with the climate on ends the trip", 2, gearWindows.size)
            assertTrue(gearWindows[0].endedAtMs <= minute(10))
            assertTrue(gearWindows[1].startedAtMs > minute(24))

            assertEquals("the legacy rules never saw the Park stretch", 1, legacyWindows.size)
            assertEquals(legacyKeyFor(legacy, minute(100)), legacyWindows.single().routeKey())
        }
    }

    @Test
    fun legacySessionRouteKeysMatchTheSameRowsWithoutAnyGearData() {
        // The strongest "old trips are unchanged" check: the legacy session's windows are
        // identical whether or not its rows carry gear/door columns at all.
        val withGear = recordParkedWithClimateDrive(minute(0), legacy = true)
        val withoutGear = recordParkedWithClimateDrive(minute(0), legacy = true, gear = false)

        withDb { db ->
            val a = DriveWindowDetector.windowsForSession(db, store.getSession(withGear))
            val b = DriveWindowDetector.windowsForSession(db, store.getSession(withoutGear))
            assertEquals(b.map { it.startedAtMs to it.endedAtMs }, a.map { it.startedAtMs to it.endedAtMs })
        }
    }

    @Test
    fun tripListFollowsTheSessionRules() {
        val gearAware = recordParkedWithClimateDrive(minute(0))
        val legacy = recordParkedWithClimateDrive(minute(100), legacy = true)

        val trips = store.getTripsJson(20)
        val bySession = (0 until trips.length()).map { trips.getJSONObject(it) }.groupBy { it.getLong("sessionId") }
        assertEquals(2, bySession[gearAware]!!.size)
        assertEquals(1, bySession[legacy]!!.size)
        assertEquals(legacyKeyFor(legacy, minute(100)), bySession[legacy]!!.single().getString("id"))
    }

    @Test
    fun routeDetailCarriesInTripParkStopsOnlyForGearAwareSessions() {
        val gearAware = recordShortParkDrive(minute(0))
        val legacy = recordShortParkDrive(minute(100), legacy = true)

        val gearKey = singleRouteKey(gearAware)
        val gearRoute = store.routes.getTripRouteJson(gearKey)
        val stops = gearRoute.getJSONArray("parkStops")
        assertEquals(1, stops.length())
        val stop = stops.getJSONObject(0)
        assertEquals(minute(10), stop.getLong("startMs"))
        assertEquals(minute(14) + HALF_MINUTE_MS, stop.getLong("endMs"))
        assertEquals(4 * 60_000L + HALF_MINUTE_MS, stop.getLong("durationMs"))
        assertFalse(stop.getBoolean("doorOpened"))

        val legacyRoute = store.routes.getTripRouteJson(singleRouteKey(legacy))
        assertTrue(legacyRoute.has("points"))
        assertFalse("legacy route payloads are unchanged", legacyRoute.has("parkStops"))
        assertFalse(store.routes.getTripRouteJson(gearAware).has("parkStops"))
    }

    @Test
    fun materializedSegmentsRecordParkStopsForGearAwareSessionsOnly() {
        val gearAware = recordShortParkDrive(minute(0))
        val legacy = recordShortParkDrive(minute(100), legacy = true)
        store.materializeSession(gearAware, minute(0), minute(25))
        store.materializeSession(legacy, minute(100), minute(125))

        assertNotNull(summaryJson(gearAware)?.optJSONArray("parkStops"))
        assertEquals(1, summaryJson(gearAware)!!.getJSONArray("parkStops").length())
        assertNull(summaryJson(legacy)?.optJSONArray("parkStops"))
    }

    // ---- helpers -------------------------------------------------------------------------

    /** 10 min driving, 15 min in Park with climate drawing 2 kW, 10 min driving. */
    private fun recordParkedWithClimateDrive(
        baseMs: Long,
        legacy: Boolean = false,
        gear: Boolean = true,
    ): Long = recordDrive(baseMs, parkFromMinute = 10, parkToMinute = 25, endMinute = 35, gear = gear, legacy = legacy)

    /** 10 min driving, a 5-min Park stop, 10 min driving. */
    private fun recordShortParkDrive(
        baseMs: Long,
        legacy: Boolean = false,
    ): Long = recordDrive(baseMs, parkFromMinute = 10, parkToMinute = 15, endMinute = 25, gear = true, legacy = legacy)

    private fun recordDrive(
        baseMs: Long,
        parkFromMinute: Int,
        parkToMinute: Int,
        endMinute: Int,
        gear: Boolean,
        legacy: Boolean,
    ): Long {
        val id = store.startSession("obd", "00:11", "Adapter", baseMs)
        // A session recorded before the cutover: the rules version is fixed when it starts.
        if (legacy) markLegacy(id)
        var lat = 34.05
        var atMs = baseMs
        val endMs = baseMs + endMinute * 60_000L
        while (atMs < endMs) {
            val parked = atMs >= baseMs + parkFromMinute * 60_000L && atMs < baseMs + parkToMinute * 60_000L
            if (!parked) lat += 0.0015
            val raw =
                if (!gear) {
                    null
                } else if (parked) {
                    PARK
                } else {
                    DRIVE
                }
            store.recordLocationSample(id, atMs, "gps", lat, -118.25, 5.0, null, null, null, null, null)
            store.recordTelemetry(
                id,
                row(atMs, if (parked) 0 else 45, raw)
                    .put("latitude", lat)
                    .put("longitude", -118.25)
                    .put("powerKw", if (parked) 2.0 else 11.0),
            )
            atMs += HALF_MINUTE_MS
        }
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, endMs - HALF_MINUTE_MS, "")
        return id
    }

    private fun row(
        atMs: Long,
        speedKph: Int,
        prndlRaw: Int?,
        staleMs: Long = 0L,
    ): JSONObject {
        val sample = JSONObject()
        sample.put("source", "obd")
        sample.put("updatedAt", atMs)
        sample.put("speedKph", speedKph)
        sample.put("voltage", 14.1)
        if (prndlRaw != null) {
            sample.put("prndlRaw", prndlRaw)
            sample.put("prndlStateStaleMs", staleMs)
        }
        return sample
    }

    private fun markLegacy(sessionId: Long) {
        withDb { db ->
            val cv = ContentValues()
            cv.put("trip_rules_version", TripSplitRules.LEGACY)
            db.update(VoltTrackerDb.TABLE_SESSIONS, cv, "_id = ?", arrayOf(sessionId.toString()))
        }
        assertEquals(TripSplitRules.LEGACY, store.getSession(sessionId)!!.tripRulesVersion)
    }

    private fun singleRouteKey(sessionId: Long): String =
        withDb { db -> DriveWindowDetector.windowsForSession(db, store.getSession(sessionId)).single().routeKey() }

    private fun legacyKeyFor(
        sessionId: Long,
        baseMs: Long,
    ): String = "$sessionId:$baseMs:${baseMs + 34 * 60_000L + HALF_MINUTE_MS}"

    private fun summaryJson(sessionId: Long): JSONObject? =
        withDb { db ->
            db
                .rawQuery(
                    "SELECT summary_json FROM ${VoltTrackerDb.TABLE_TRIP_SEGMENTS} WHERE session_id = ? LIMIT 1",
                    arrayOf(sessionId.toString()),
                ).use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) JSONObject(c.getString(0)) else null
                }
        }

    private fun <T> withDb(block: (SQLiteDatabase) -> T): T {
        store.checkpoint()
        return SQLiteDatabase
            .openDatabase(store.getDatabaseFile().path, null, SQLiteDatabase.OPEN_READWRITE)
            .use(block)
    }

    private companion object {
        const val PARK = 8
        const val DRIVE = 3
        const val HALF_MINUTE_MS = 30_000L
        const val BASE_MS = 1_780_000_000_000L

        fun minute(m: Int): Long = BASE_MS + m * 60_000L
    }
}
