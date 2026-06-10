package com.volttracker.obdpoc.data

import android.database.sqlite.SQLiteDatabase

/**
 * Shared read-side classification for sessions/windows that should count as real drives. GPS-only
 * drift and diagnostic connection attempts are useful evidence, but they should not show as trips.
 */
object ObdSessionClassifier {
    private const val MIN_MOVING_SPEED_KPH = 5
    private const val MIN_MEANINGFUL_ROUTE_POINTS = 3
    private const val MIN_MEANINGFUL_DISTANCE_METERS = 100.0

    @JvmStatic
    fun hasUsefulTelemetry(
        db: SQLiteDatabase,
        sessionId: Long,
    ): Boolean =
        ObdStoreSupport.countRowsWhere(
            db,
            VoltTrackerDb.TABLE_TELEMETRY,
            "session_id = ? AND ${ObdStoreSupport.USEFUL_TELEMETRY_WHERE}",
            arrayOf(sessionId.toString()),
        ) > 0

    @JvmStatic
    fun isTripSession(
        db: SQLiteDatabase,
        session: ObdSessionRecord,
    ): Boolean = ObdLocalStore.MODE_OBD == session.mode && hasUsefulTelemetry(db, session.id)

    @JvmStatic
    fun maxSpeedKphForWindow(
        db: SQLiteDatabase,
        sessionId: Long,
        window: DriveWindowDetector.DriveWindow,
    ): Int? =
        db
            .rawQuery(
                "SELECT MAX(speed_kph) FROM ${VoltTrackerDb.TABLE_TELEMETRY} " +
                    "WHERE session_id = ? AND captured_at_ms >= ? " +
                    "AND captured_at_ms <= ? AND speed_kph IS NOT NULL",
                arrayOf(sessionId.toString(), window.startedAtMs.toString(), window.endedAtMs.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else null
            }

    @JvmStatic
    fun isMeaningfulTrip(
        pointCount: Int,
        distanceMeters: Double,
        maxSpeedKph: Int?,
    ): Boolean {
        if (maxSpeedKph != null && maxSpeedKph >= MIN_MOVING_SPEED_KPH) {
            return true
        }
        return pointCount >= MIN_MEANINGFUL_ROUTE_POINTS &&
            distanceMeters >= MIN_MEANINGFUL_DISTANCE_METERS
    }
}
