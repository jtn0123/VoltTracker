package com.volttracker.obdpoc.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
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
 * Robolectric coverage for the downsampling stride in [ObdStoreRouteProjection]. The regression
 * pinned here: with floor division the stride collapsed to 1 for totals just above the target
 * (total in (target, 2*target-2]), so the projection kept the first target-1 rows plus the final
 * row — replacing the route's middle-to-tail with one straight chord. Ceiling division must spread
 * the kept samples evenly across the whole time span instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObdStoreRouteProjectionDbTest {
    private lateinit var helper: VoltTrackerDb
    private lateinit var db: SQLiteDatabase

    @Before
    fun setUp() {
        helper = VoltTrackerDb(RuntimeEnvironment.getApplication(), DB_NAME)
        db = helper.writableDatabase
    }

    @After
    fun tearDown() {
        helper.close()
        RuntimeEnvironment.getApplication().deleteDatabase(DB_NAME)
    }

    @Test
    fun routePointsForTotalJustAboveTargetSpanTheFullTimeRangeEvenly() {
        val sessionId = insertSession(db, FIRST_AT_MS)
        for (i in 0 until TOTAL) {
            insertGpsTelemetry(db, sessionId, FIRST_AT_MS + i * INTERVAL_MS, 34.05 + i * 0.00001, -118.25, null)
        }

        val points = ObdStoreRouteProjection.routePointsForSessionJson(db, sessionId, TARGET)

        assertTrue("count must respect the cap (was ${points.length()})", points.length() <= TARGET)
        assertTrue("downsampling must keep a dense polyline", points.length() >= 100)
        assertEquals("first point must be kept", FIRST_AT_MS, points.getJSONObject(0).optLong("atMs"))
        assertEquals(
            "last point must be kept",
            LAST_AT_MS,
            points.getJSONObject(points.length() - 1).optLong("atMs"),
        )
        val maxGap = maxGapMs(points)
        assertTrue(
            "samples must be spread evenly across the whole span, not clipped at the head " +
                "(max gap ${maxGap}ms for a ${INTERVAL_MS}ms cadence)",
            maxGap <= 5 * INTERVAL_MS,
        )
    }

    @Test
    fun scalarTrackForTotalJustAboveTargetSpansTheFullTimeRangeEvenly() {
        val sessionId = insertSession(db, FIRST_AT_MS)
        for (i in 0 until TOTAL) {
            insertGpsTelemetry(
                db,
                sessionId,
                FIRST_AT_MS + i * INTERVAL_MS,
                34.05 + i * 0.00001,
                -118.25,
                50.0 + i * 0.01,
            )
        }
        val session =
            ObdSessionRecord(
                sessionId,
                ObdLocalStore.MODE_OBD,
                "00:11",
                "Adapter",
                FIRST_AT_MS,
                LAST_AT_MS + 1L,
                "complete",
                "",
                TOTAL,
                LAST_AT_MS,
            )

        val socTrack = ObdStoreRouteProjection.routeForSession(db, session, TARGET).getJSONArray("socTrack")

        assertTrue("count must respect the cap (was ${socTrack.length()})", socTrack.length() <= TARGET)
        assertEquals("first sample must be kept", FIRST_AT_MS, socTrack.getJSONObject(0).optLong("atMs"))
        assertEquals(
            "last sample must be kept",
            LAST_AT_MS,
            socTrack.getJSONObject(socTrack.length() - 1).optLong("atMs"),
        )
        val maxGap = maxGapMs(socTrack)
        assertTrue(
            "scalar samples must be spread evenly across the whole span " +
                "(max gap ${maxGap}ms for a ${INTERVAL_MS}ms cadence)",
            maxGap <= 5 * INTERVAL_MS,
        )
    }

    @Test
    fun routeDistanceUsesRawAcceptedPointsInsteadOfDisplaySimplification() {
        val count = 400
        val sessionId = insertSession(db, FIRST_AT_MS)
        for (i in 0 until count) {
            val latitude = 34.05 + if (i % 2 == 0) -0.000045 else 0.000045
            val longitude = -118.25 + i * 0.000045
            insertGpsTelemetry(db, sessionId, FIRST_AT_MS + i * INTERVAL_MS, latitude, longitude, null)
        }
        val session =
            ObdSessionRecord(
                sessionId,
                ObdLocalStore.MODE_OBD,
                "00:11",
                "Adapter",
                FIRST_AT_MS,
                FIRST_AT_MS + count * INTERVAL_MS,
                "complete",
                "",
                count,
                FIRST_AT_MS + (count - 1) * INTERVAL_MS,
            )

        val route = ObdStoreRouteProjection.routeForSession(db, session, 500)

        assertTrue("display geometry should still be simplified", route.getJSONArray("points").length() < 20)
        assertTrue(
            "distance must retain the real zig-zag path instead of measuring its display chord",
            route.getDouble("distanceMeters") > 3_500.0,
        )
    }

    private companion object {
        private const val DB_NAME = "route-projection-test.db"

        // Keeps TOTAL just above TARGET (the stride-collapse regression zone) while the kept
        // point count stays under SIMPLIFY_MIN_POINTS: this test seeds collinear points, which
        // the geometry simplifier would (correctly) collapse — that behavior is pinned separately
        // in ObdStoreRouteSimplifyTest.
        private const val TOTAL = 360
        private const val TARGET = 300
        private const val INTERVAL_MS = 1_000L
        private const val FIRST_AT_MS = 10_000L
        private const val LAST_AT_MS = FIRST_AT_MS + (TOTAL - 1) * INTERVAL_MS

        private fun maxGapMs(points: JSONArray): Long {
            var maxGap = 0L
            for (i in 1 until points.length()) {
                val gap = points.getJSONObject(i).optLong("atMs") - points.getJSONObject(i - 1).optLong("atMs")
                maxGap = maxOf(maxGap, gap)
            }
            return maxGap
        }

        private fun insertSession(
            db: SQLiteDatabase,
            startedAtMs: Long,
        ): Long {
            val cv = ContentValues()
            cv.put("mode", ObdLocalStore.MODE_OBD)
            cv.put("started_at_ms", startedAtMs)
            cv.put("status", "complete")
            cv.put("sample_count", 0)
            cv.put("created_at_ms", startedAtMs)
            return db.insertOrThrow(VoltTrackerDb.TABLE_SESSIONS, null, cv)
        }

        private fun insertGpsTelemetry(
            db: SQLiteDatabase,
            sessionId: Long,
            capturedAtMs: Long,
            latitude: Double,
            longitude: Double,
            soc: Double?,
        ) {
            val cv = ContentValues()
            cv.put("session_id", sessionId)
            cv.put("captured_at_ms", capturedAtMs)
            cv.put("speed_kph", 40)
            cv.put("latitude", latitude)
            cv.put("longitude", longitude)
            if (soc != null) {
                cv.put("soc", soc)
            }
            cv.put("json", "{}")
            db.insertOrThrow(VoltTrackerDb.TABLE_TELEMETRY, null, cv)
        }
    }
}
