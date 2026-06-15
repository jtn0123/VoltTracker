package com.volttracker.obdpoc.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Pins [ObdSessionClassifier], the read-side gate that decides which stored sessions/windows count
 * as real drives. Both the trips/maps projections and the storage rollups read its output, so a
 * silent shift in any threshold here surfaces GPS drift and bare connection attempts as fake trips
 * (or hides real ones). The pure-arithmetic [ObdSessionClassifier.isMeaningfulTrip] gets exhaustive
 * boundary coverage; the SQL-backed reads run against a real Robolectric-hosted SQLite database so
 * the WHERE clauses and window bounds are exercised end to end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObdSessionClassifierTest {
    private lateinit var helper: VoltTrackerDb
    private lateinit var db: SQLiteDatabase

    @Before
    fun setUp() {
        helper = VoltTrackerDb(RuntimeEnvironment.getApplication(), "obd-session-classifier-test.db")
        db = helper.writableDatabase
        // Start from a clean slate so row ids and counts are deterministic across runs.
        db.delete(VoltTrackerDb.TABLE_TELEMETRY, null, null)
        db.delete(VoltTrackerDb.TABLE_SESSIONS, null, null)
    }

    @After
    fun tearDown() {
        helper.close()
        RuntimeEnvironment.getApplication().deleteDatabase("obd-session-classifier-test.db")
    }

    // ---- isMeaningfulTrip: pure threshold arithmetic ----------------------------------------

    @Test
    fun speedAtMovingThresholdMakesTripMeaningfulRegardlessOfRoute() {
        // 5 kph is the inclusive floor: any sample at/above it proves the car actually moved, so
        // route point count and distance are irrelevant.
        assertTrue(ObdSessionClassifier.isMeaningfulTrip(0, 0.0, 5))
    }

    @Test
    fun speedAboveMovingThresholdMakesTripMeaningful() {
        assertTrue(ObdSessionClassifier.isMeaningfulTrip(0, 0.0, 120))
    }

    @Test
    fun speedJustBelowMovingThresholdFallsBackToRouteEvidence() {
        // 4 kph is below the 5 kph floor, so the route gate decides. With no route it is not a trip.
        assertFalse(ObdSessionClassifier.isMeaningfulTrip(0, 0.0, 4))
    }

    @Test
    fun nullMaxSpeedFallsBackToRouteEvidence() {
        // No speed reading at all → route gate decides. Below both route thresholds: not a trip.
        assertFalse(ObdSessionClassifier.isMeaningfulTrip(0, 0.0, null))
    }

    @Test
    fun routeAtBothThresholdsIsMeaningfulWhenSpeedIsUnknown() {
        // 3 points AND 100 m are the inclusive floors; meeting both with no speed still counts.
        assertTrue(ObdSessionClassifier.isMeaningfulTrip(3, 100.0, null))
    }

    @Test
    fun routeAtBothThresholdsIsMeaningfulWhenSpeedIsBelowMoving() {
        // A slow (<5 kph) sample does not by itself disqualify a trip that clears the route gate.
        assertTrue(ObdSessionClassifier.isMeaningfulTrip(3, 100.0, 1))
    }

    @Test
    fun routePointCountJustBelowThresholdIsNotMeaningful() {
        // 2 points is one short of the 3-point floor, even with ample distance.
        assertFalse(ObdSessionClassifier.isMeaningfulTrip(2, 100.0, null))
    }

    @Test
    fun routeDistanceJustBelowThresholdIsNotMeaningful() {
        // 99.999 m is below the 100 m floor, even with enough points.
        assertFalse(ObdSessionClassifier.isMeaningfulTrip(3, 99.999, null))
    }

    @Test
    fun routeNeedsBothPointsAndDistance() {
        // Plenty of points but no distance (GPS jitter in place) is not a trip; and vice versa.
        assertFalse(ObdSessionClassifier.isMeaningfulTrip(50, 0.0, null))
        assertFalse(ObdSessionClassifier.isMeaningfulTrip(0, 5000.0, null))
    }

    @Test
    fun ampleRouteIsMeaningful() {
        assertTrue(ObdSessionClassifier.isMeaningfulTrip(40, 1500.0, null))
    }

    // ---- hasUsefulTelemetry: counts rows with at least one real reading ----------------------

    @Test
    fun hasUsefulTelemetryIsFalseWhenSessionHasNoRows() {
        val sessionId = insertSession(MODE_OBD)
        assertFalse(ObdSessionClassifier.hasUsefulTelemetry(db, sessionId))
    }

    @Test
    fun hasUsefulTelemetryIsFalseWhenRowsCarryNoRealReadings() {
        val sessionId = insertSession(MODE_OBD)
        // A bare heartbeat: timestamp present but every telemetry channel is NULL.
        insertTelemetry(sessionId, capturedAtMs = 1_000, speedKph = null)
        assertFalse(ObdSessionClassifier.hasUsefulTelemetry(db, sessionId))
    }

    @Test
    fun hasUsefulTelemetryIsTrueWhenAnyRowHasASpeed() {
        val sessionId = insertSession(MODE_OBD)
        insertTelemetry(sessionId, capturedAtMs = 1_000, speedKph = null)
        insertTelemetry(sessionId, capturedAtMs = 2_000, speedKph = 30)
        assertTrue(ObdSessionClassifier.hasUsefulTelemetry(db, sessionId))
    }

    @Test
    fun hasUsefulTelemetryIsTrueForAGpsOnlyRow() {
        val sessionId = insertSession(MODE_OBD)
        // No OBD speed/rpm, but a latitude counts as useful telemetry per USEFUL_TELEMETRY_WHERE.
        insertTelemetry(sessionId, capturedAtMs = 1_000, speedKph = null, latitude = 32.7)
        assertTrue(ObdSessionClassifier.hasUsefulTelemetry(db, sessionId))
    }

    @Test
    fun hasUsefulTelemetryIsScopedToTheGivenSession() {
        val withData = insertSession(MODE_OBD)
        val empty = insertSession(MODE_OBD)
        insertTelemetry(withData, capturedAtMs = 1_000, speedKph = 40)
        assertTrue(ObdSessionClassifier.hasUsefulTelemetry(db, withData))
        assertFalse("a sibling session's rows must not leak in", ObdSessionClassifier.hasUsefulTelemetry(db, empty))
    }

    // ---- isTripSession: OBD-mode AND useful telemetry ----------------------------------------

    @Test
    fun isTripSessionIsTrueForObdSessionWithUsefulTelemetry() {
        val sessionId = insertSession(MODE_OBD)
        insertTelemetry(sessionId, capturedAtMs = 1_000, speedKph = 40)
        assertTrue(ObdSessionClassifier.isTripSession(db, record(sessionId, MODE_OBD)))
    }

    @Test
    fun isTripSessionIsFalseForObdSessionWithNoUsefulTelemetry() {
        val sessionId = insertSession(MODE_OBD)
        insertTelemetry(sessionId, capturedAtMs = 1_000, speedKph = null)
        assertFalse(ObdSessionClassifier.isTripSession(db, record(sessionId, MODE_OBD)))
    }

    @Test
    fun isTripSessionIsFalseForNonObdModeEvenWithTelemetry() {
        // A scan/demo session that happened to log a real reading must not surface as a trip.
        val sessionId = insertSession("scan")
        insertTelemetry(sessionId, capturedAtMs = 1_000, speedKph = 40)
        assertFalse(ObdSessionClassifier.isTripSession(db, record(sessionId, "scan")))
        assertFalse(ObdSessionClassifier.isTripSession(db, record(sessionId, "demo")))
    }

    // ---- maxSpeedKphForWindow: MAX over the [start, end] window ------------------------------

    @Test
    fun maxSpeedKphForWindowReturnsNullWhenNoRowsInWindow() {
        val sessionId = insertSession(MODE_OBD)
        insertTelemetry(sessionId, capturedAtMs = 5_000, speedKph = 80)
        // Window ends before the only sample.
        assertNull(ObdSessionClassifier.maxSpeedKphForWindow(db, sessionId, window(sessionId, 1_000, 2_000)))
    }

    @Test
    fun maxSpeedKphForWindowReturnsNullWhenWindowRowsHaveNullSpeed() {
        val sessionId = insertSession(MODE_OBD)
        insertTelemetry(sessionId, capturedAtMs = 1_500, speedKph = null, latitude = 32.7)
        assertNull(ObdSessionClassifier.maxSpeedKphForWindow(db, sessionId, window(sessionId, 1_000, 2_000)))
    }

    @Test
    fun maxSpeedKphForWindowReturnsTheMaxAcrossInWindowRows() {
        val sessionId = insertSession(MODE_OBD)
        insertTelemetry(sessionId, capturedAtMs = 1_100, speedKph = 30)
        insertTelemetry(sessionId, capturedAtMs = 1_500, speedKph = 75)
        insertTelemetry(sessionId, capturedAtMs = 1_900, speedKph = 50)
        assertEquals(75, ObdSessionClassifier.maxSpeedKphForWindow(db, sessionId, window(sessionId, 1_000, 2_000)))
    }

    @Test
    fun maxSpeedKphForWindowBoundsAreInclusive() {
        val sessionId = insertSession(MODE_OBD)
        // Samples sitting exactly on the start and end edges must both be considered.
        insertTelemetry(sessionId, capturedAtMs = 1_000, speedKph = 20)
        insertTelemetry(sessionId, capturedAtMs = 2_000, speedKph = 90)
        assertEquals(90, ObdSessionClassifier.maxSpeedKphForWindow(db, sessionId, window(sessionId, 1_000, 2_000)))
    }

    @Test
    fun maxSpeedKphForWindowIgnoresSamplesOutsideTheWindow() {
        val sessionId = insertSession(MODE_OBD)
        insertTelemetry(sessionId, capturedAtMs = 999, speedKph = 200) // just before start
        insertTelemetry(sessionId, capturedAtMs = 1_500, speedKph = 60)
        insertTelemetry(sessionId, capturedAtMs = 2_001, speedKph = 200) // just after end
        assertEquals(60, ObdSessionClassifier.maxSpeedKphForWindow(db, sessionId, window(sessionId, 1_000, 2_000)))
    }

    @Test
    fun maxSpeedKphForWindowIsScopedToTheGivenSession() {
        val target = insertSession(MODE_OBD)
        val other = insertSession(MODE_OBD)
        insertTelemetry(target, capturedAtMs = 1_500, speedKph = 40)
        insertTelemetry(other, capturedAtMs = 1_500, speedKph = 150)
        assertEquals(40, ObdSessionClassifier.maxSpeedKphForWindow(db, target, window(target, 1_000, 2_000)))
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun insertSession(mode: String): Long {
        val values =
            ContentValues().apply {
                put("mode", mode)
                put("started_at_ms", 0L)
                put("status", "complete")
                put("created_at_ms", 0L)
            }
        return db.insertOrThrow(VoltTrackerDb.TABLE_SESSIONS, null, values)
    }

    private fun insertTelemetry(
        sessionId: Long,
        capturedAtMs: Long,
        speedKph: Int?,
        latitude: Double? = null,
    ) {
        val values =
            ContentValues().apply {
                put("session_id", sessionId)
                put("captured_at_ms", capturedAtMs)
                // json is NOT NULL in the schema; the classifier never reads it, so a stub is fine.
                put("json", "{}")
                if (speedKph != null) put("speed_kph", speedKph)
                if (latitude != null) put("latitude", latitude)
            }
        db.insertOrThrow(VoltTrackerDb.TABLE_TELEMETRY, null, values)
    }

    private fun record(
        sessionId: Long,
        mode: String,
    ): ObdSessionRecord =
        ObdSessionRecord(
            sessionId,
            mode,
            "AA:BB",
            "Adapter",
            0L,
            0L,
            "complete",
            "",
            0,
            0L,
        )

    private fun window(
        sessionId: Long,
        startMs: Long,
        endMs: Long,
    ): DriveWindowDetector.DriveWindow = DriveWindowDetector.DriveWindow(sessionId, 0, startMs, endMs)

    private companion object {
        private val MODE_OBD = ObdLocalStore.MODE_OBD
    }
}
