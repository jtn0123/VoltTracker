package com.volttracker.obdpoc.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import org.json.JSONObject
import java.io.Closeable

/**
 * Owns the prepared [SQLiteStatement] the telemetry write path reuses, plus bind helpers that
 * translate a telemetry JSON snapshot into placeholder values.
 */
class ObdStatementCache : Closeable {
    private var telemetryInsertStmt: SQLiteStatement? = null

    /**
     * The [SQLiteDatabase] [telemetryInsertStmt] was compiled against. A prepared statement binds
     * to one native handle, so if the DB is recycled (closed and reopened) a statement cached
     * against the old handle would make [SQLiteStatement.executeInsert] throw. We key the cache on
     * this instance and recompile when a different db is seen.
     */
    private var compiledAgainst: SQLiteDatabase? = null

    /**
     * Binds the telemetry sample to the prepared statement and executes the INSERT. Returns the
     * inserted row id. Must be invoked inside an existing transaction owned by the caller.
     */
    fun bindAndInsertTelemetry(
        db: SQLiteDatabase,
        sessionId: Long,
        capturedAtMs: Long,
        sample: JSONObject,
    ): Long {
        // Hot path: a cheap reference-identity check. Only when the caller hands us a different
        // db instance (a recycled handle) do we close the stale statement and recompile.
        if (telemetryInsertStmt == null || compiledAgainst !== db) {
            telemetryInsertStmt?.close()
            telemetryInsertStmt = db.compileStatement(SQL_INSERT_TELEMETRY)
            compiledAgainst = db
        }
        val stmt = requireNotNull(telemetryInsertStmt)
        stmt.clearBindings()
        bindTelemetry(stmt, sessionId, capturedAtMs, sample)
        return stmt.executeInsert()
    }

    override fun close() {
        telemetryInsertStmt?.close()
        telemetryInsertStmt = null
        compiledAgainst = null
    }

    companion object {
        /** Promoted from inline string concat so the SQL is greppable and obviously parameterized. */
        @JvmField
        val SQL_UPDATE_SESSION_AFTER_TELEMETRY: String =
            "UPDATE " +
                VoltTrackerDb.TABLE_SESSIONS +
                " SET sample_count = sample_count + 1," +
                " last_event_at_ms = ?," +
                " supported_pids = CASE WHEN ? = '' THEN supported_pids ELSE ? END" +
                " WHERE _id = ?"

        /**
         * Prepared INSERT for telemetry writes. Column list matches [bindTelemetry] 1-to-1;
         * reordering one without the other will silently write data to the wrong columns.
         */
        @JvmField
        val SQL_INSERT_TELEMETRY: String =
            "INSERT INTO " +
                VoltTrackerDb.TABLE_TELEMETRY +
                " (" +
                "session_id," +
                "captured_at_ms," +
                "source," +
                "vehicle_state," +
                "speed_kph," +
                "rpm," +
                "coolant_c," +
                "load_pct," +
                "throttle_pct," +
                "voltage," +
                "soc," +
                "battery_temp," +
                "power_kw," +
                "pack_voltage," +
                "pack_current_a," +
                "latitude," +
                "longitude," +
                "accuracy_m," +
                "gps_speed_mps," +
                "bearing_deg," +
                "location_age_ms," +
                "sample_number," +
                "session_ms," +
                "charge_transition_hint," +
                "app_foreground," +
                "raw," +
                "json" +
                ") VALUES (" +
                "?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"

        private fun bindTelemetry(
            stmt: SQLiteStatement,
            sessionId: Long,
            capturedAtMs: Long,
            sample: JSONObject,
        ) {
            // After clearBindings() every parameter is implicitly NULL, so optional helpers only
            // bind when the JSON has the key.
            stmt.bindLong(1, sessionId)
            stmt.bindLong(2, capturedAtMs)
            stmt.bindString(3, ObdStoreSupport.clean(sample.optString("source", "")))
            stmt.bindString(4, ObdStoreSupport.clean(sample.optString("vehicleState", "")))
            bindOptionalInt(stmt, 5, sample, "speedKph")
            bindOptionalInt(stmt, 6, sample, "rpm")
            bindOptionalInt(stmt, 7, sample, "coolantC")
            bindOptionalInt(stmt, 8, sample, "loadPct")
            bindOptionalInt(stmt, 9, sample, "throttlePct")
            bindOptionalDouble(stmt, 10, sample, "voltage")
            bindOptionalDouble(stmt, 11, sample, "soc")
            bindOptionalDouble(stmt, 12, sample, "batteryTemp")
            bindOptionalDouble(stmt, 13, sample, "powerKw")
            bindOptionalDouble(stmt, 14, sample, "packVoltage")
            bindOptionalDouble(stmt, 15, sample, "packCurrentA")
            bindOptionalDouble(stmt, 16, sample, "latitude")
            bindOptionalDouble(stmt, 17, sample, "longitude")
            bindOptionalDouble(stmt, 18, sample, "accuracyM")
            bindOptionalDouble(stmt, 19, sample, "gpsSpeedMps")
            bindOptionalDouble(stmt, 20, sample, "bearingDeg")
            bindOptionalLong(stmt, 21, sample, "locationAgeMs")
            bindOptionalInt(stmt, 22, sample, "sampleCount")
            bindOptionalLong(stmt, 23, sample, "sessionMs")
            bindOptionalBool(stmt, 24, sample, "chargeTransitionHint")
            bindOptionalBool(stmt, 25, sample, "appForeground")
            stmt.bindString(26, ObdStoreSupport.clean(sample.optString("raw", "")))
            stmt.bindString(27, sample.toString())
        }

        private fun bindOptionalInt(
            stmt: SQLiteStatement,
            index: Int,
            sample: JSONObject,
            key: String,
        ) {
            if (sample.has(key) && !sample.isNull(key)) {
                stmt.bindLong(index, sample.optInt(key).toLong())
            }
        }

        private fun bindOptionalLong(
            stmt: SQLiteStatement,
            index: Int,
            sample: JSONObject,
            key: String,
        ) {
            if (sample.has(key) && !sample.isNull(key)) {
                stmt.bindLong(index, sample.optLong(key))
            }
        }

        private fun bindOptionalDouble(
            stmt: SQLiteStatement,
            index: Int,
            sample: JSONObject,
            key: String,
        ) {
            if (sample.has(key) && !sample.isNull(key)) {
                stmt.bindDouble(index, sample.optDouble(key))
            }
        }

        private fun bindOptionalBool(
            stmt: SQLiteStatement,
            index: Int,
            sample: JSONObject,
            key: String,
        ) {
            if (sample.has(key) && !sample.isNull(key)) {
                stmt.bindLong(index, if (sample.optBoolean(key)) 1L else 0L)
            }
        }
    }
}
