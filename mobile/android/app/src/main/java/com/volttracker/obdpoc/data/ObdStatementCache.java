package com.volttracker.obdpoc.data;

import static com.volttracker.obdpoc.data.ObdStoreSupport.clean;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import java.io.Closeable;
import org.json.JSONObject;

/**
 * Owns the prepared {@link SQLiteStatement} the telemetry write path reuses, plus the bind helpers
 * that translate a telemetry JSON snapshot into placeholder values. Pulled out of {@link
 * ObdLocalStore} so the façade stays focused on session lifecycle and transaction control while the
 * statement lifecycle + column-to-placeholder mapping live in one cohesive place.
 *
 * <p>Package-private — callers go through {@link ObdLocalStore#recordTelemetry} and {@link
 * ObdLocalStore#close()} so the existing public API is unchanged.
 *
 * <p>{@link SQLiteStatement} is NOT thread-safe; this cache is only used from the {@code
 * SessionRecorder} single-thread executor (see {@code ObdService#recorder}), which serializes all
 * write traffic on the store. If another caller is added in the future, it must synchronize on this
 * instance or use its own statement.
 */
final class ObdStatementCache implements Closeable {

    /** Promoted from inline string concat so the SQL is greppable and obviously parameterized. */
    static final String SQL_UPDATE_SESSION_AFTER_TELEMETRY =
            "UPDATE "
                    + VoltTrackerDb.TABLE_SESSIONS
                    + " SET sample_count = sample_count + 1,"
                    + " last_event_at_ms = ?,"
                    + " supported_pids = CASE WHEN ? = '' THEN supported_pids ELSE ? END"
                    + " WHERE _id = ?";

    /**
     * Prepared INSERT for {@link #bindAndInsertTelemetry}. Column list matches the bind order in
     * {@link #bindTelemetry} 1-to-1; reordering one without the other will silently write data to
     * the wrong columns, so keep them in lockstep with the CREATE TABLE statement in {@link
     * VoltTrackerDb}.
     */
    static final String SQL_INSERT_TELEMETRY =
            "INSERT INTO "
                    + VoltTrackerDb.TABLE_TELEMETRY
                    + " ("
                    + "session_id," // 1
                    + "captured_at_ms," // 2
                    + "source," // 3
                    + "vehicle_state," // 4
                    + "speed_kph," // 5
                    + "rpm," // 6
                    + "coolant_c," // 7
                    + "load_pct," // 8
                    + "throttle_pct," // 9
                    + "voltage," // 10
                    + "soc," // 11
                    + "battery_temp," // 12
                    + "power_kw," // 13
                    + "pack_voltage," // 14
                    + "pack_current_a," // 15
                    + "latitude," // 16
                    + "longitude," // 17
                    + "accuracy_m," // 18
                    + "gps_speed_mps," // 19
                    + "bearing_deg," // 20
                    + "location_age_ms," // 21
                    + "sample_number," // 22
                    + "session_ms," // 23
                    + "charge_transition_hint," // 24
                    + "app_foreground," // 25
                    + "raw," // 26
                    + "json" // 27
                    + ") VALUES ("
                    + "?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private SQLiteStatement telemetryInsertStmt;

    ObdStatementCache() {}

    /**
     * Binds the telemetry sample to the prepared statement and executes the INSERT. Returns the
     * inserted row id. Must be invoked inside an existing transaction owned by the caller; this
     * cache deliberately does not touch the transaction lifecycle.
     */
    long bindAndInsertTelemetry(
            SQLiteDatabase db, long sessionId, long capturedAtMs, JSONObject sample) {
        if (telemetryInsertStmt == null) {
            telemetryInsertStmt = db.compileStatement(SQL_INSERT_TELEMETRY);
        }
        SQLiteStatement stmt = telemetryInsertStmt;
        stmt.clearBindings();
        bindTelemetry(stmt, sessionId, capturedAtMs, sample);
        return stmt.executeInsert();
    }

    /**
     * Binds the {@link #SQL_INSERT_TELEMETRY} placeholders. The order MUST match the column list in
     * that SQL string; both intentionally mirror the {@code CREATE TABLE} order in {@link
     * VoltTrackerDb} so changes stay locally reviewable.
     */
    private static void bindTelemetry(
            SQLiteStatement stmt, long sessionId, long capturedAtMs, JSONObject sample) {
        // After clearBindings() every parameter is implicitly NULL, so the optional
        // helpers below only need to bind when the JSON has the key.
        stmt.bindLong(1, sessionId);
        stmt.bindLong(2, capturedAtMs);
        stmt.bindString(3, clean(sample.optString("source", "")));
        stmt.bindString(4, clean(sample.optString("vehicleState", "")));
        bindOptionalInt(stmt, 5, sample, "speedKph");
        bindOptionalInt(stmt, 6, sample, "rpm");
        bindOptionalInt(stmt, 7, sample, "coolantC");
        bindOptionalInt(stmt, 8, sample, "loadPct");
        bindOptionalInt(stmt, 9, sample, "throttlePct");
        bindOptionalDouble(stmt, 10, sample, "voltage");
        bindOptionalDouble(stmt, 11, sample, "soc");
        bindOptionalDouble(stmt, 12, sample, "batteryTemp");
        bindOptionalDouble(stmt, 13, sample, "powerKw");
        bindOptionalDouble(stmt, 14, sample, "packVoltage");
        bindOptionalDouble(stmt, 15, sample, "packCurrentA");
        bindOptionalDouble(stmt, 16, sample, "latitude");
        bindOptionalDouble(stmt, 17, sample, "longitude");
        bindOptionalDouble(stmt, 18, sample, "accuracyM");
        bindOptionalDouble(stmt, 19, sample, "gpsSpeedMps");
        bindOptionalDouble(stmt, 20, sample, "bearingDeg");
        bindOptionalLong(stmt, 21, sample, "locationAgeMs");
        bindOptionalInt(stmt, 22, sample, "sampleCount");
        bindOptionalLong(stmt, 23, sample, "sessionMs");
        bindOptionalBool(stmt, 24, sample, "chargeTransitionHint");
        bindOptionalBool(stmt, 25, sample, "appForeground");
        stmt.bindString(26, clean(sample.optString("raw", "")));
        stmt.bindString(27, sample.toString());
    }

    private static void bindOptionalInt(
            SQLiteStatement stmt, int index, JSONObject sample, String key) {
        if (sample.has(key) && !sample.isNull(key)) {
            stmt.bindLong(index, sample.optInt(key));
        }
        // else: leave as NULL (set by clearBindings()), matching the previous
        // ContentValues.put-only-when-present behaviour.
    }

    private static void bindOptionalLong(
            SQLiteStatement stmt, int index, JSONObject sample, String key) {
        if (sample.has(key) && !sample.isNull(key)) {
            stmt.bindLong(index, sample.optLong(key));
        }
    }

    private static void bindOptionalDouble(
            SQLiteStatement stmt, int index, JSONObject sample, String key) {
        if (sample.has(key) && !sample.isNull(key)) {
            stmt.bindDouble(index, sample.optDouble(key));
        }
    }

    private static void bindOptionalBool(
            SQLiteStatement stmt, int index, JSONObject sample, String key) {
        if (sample.has(key) && !sample.isNull(key)) {
            stmt.bindLong(index, sample.optBoolean(key) ? 1L : 0L);
        }
    }

    @Override
    public void close() {
        if (telemetryInsertStmt != null) {
            telemetryInsertStmt.close();
            telemetryInsertStmt = null;
        }
    }
}
