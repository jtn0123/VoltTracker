package com.volttracker.obdpoc.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.volttracker.obdpoc.materialize.ChargeSession;
import com.volttracker.obdpoc.materialize.LocationSample;
import com.volttracker.obdpoc.materialize.PidObservation;
import com.volttracker.obdpoc.materialize.TelemetrySample;
import com.volttracker.obdpoc.materialize.Trip;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read and write paths used exclusively by the session materializers. Split out of {@link
 * ObdLocalStore} so the façade stays under the 500-line split limit; package-private and stateless
 * apart from the {@link VoltTrackerDb} handle.
 *
 * <p><b>Reads</b> return oldest-first lists scoped to a single session id. Heavy serialisation (the
 * {@code json} blob on telemetry, the raw response on PID observations) is deliberately not
 * deserialised here — the materializers only look at the columns that are already typed.
 *
 * <p><b>Writes</b> batch the entire output of one materializer into a single transaction so a
 * partial write cannot leave half a trip set on disk if the second insert throws.
 */
final class ObdStoreMaterialize {

    private final VoltTrackerDb helper;

    ObdStoreMaterialize(VoltTrackerDb helper) {
        this.helper = helper;
    }

    // ---- reads --------------------------------------------------------------------

    List<LocationSample> readLocationSamples(long sessionId) {
        List<LocationSample> rows = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor cursor =
                db.query(
                        VoltTrackerDb.TABLE_LOCATION_SAMPLES,
                        new String[] {
                            "captured_at_ms", "latitude", "longitude", "speed_mps", "accuracy_m"
                        },
                        "session_id = ?",
                        new String[] {String.valueOf(sessionId)},
                        null,
                        null,
                        "captured_at_ms ASC")) {
            while (cursor.moveToNext()) {
                rows.add(
                        new LocationSample(
                                cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")),
                                cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")),
                                cursor.getDouble(cursor.getColumnIndexOrThrow("longitude")),
                                nullableDouble(cursor, "speed_mps"),
                                nullableFloat(cursor, "accuracy_m")));
            }
        }
        return Collections.unmodifiableList(rows);
    }

    List<PidObservation> readPidObservations(long sessionId) {
        List<PidObservation> rows = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor cursor =
                db.query(
                        VoltTrackerDb.TABLE_PID_OBSERVATIONS,
                        new String[] {
                            "observed_at_ms", "command", "header", "raw_response", "value_numeric"
                        },
                        "session_id = ?",
                        new String[] {String.valueOf(sessionId)},
                        null,
                        null,
                        "observed_at_ms ASC")) {
            while (cursor.moveToNext()) {
                rows.add(
                        new PidObservation(
                                cursor.getLong(cursor.getColumnIndexOrThrow("observed_at_ms")),
                                cursor.getString(cursor.getColumnIndexOrThrow("command")),
                                cursor.getString(cursor.getColumnIndexOrThrow("header")),
                                cursor.getString(cursor.getColumnIndexOrThrow("raw_response")),
                                // No parser-key column exists; future Volt-specific parsers will
                                // populate this. See PidObservation javadoc for the contract.
                                null,
                                nullableDouble(cursor, "value_numeric")));
            }
        }
        return Collections.unmodifiableList(rows);
    }

    List<TelemetrySample> readTelemetrySamples(long sessionId) {
        List<TelemetrySample> rows = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor cursor =
                db.query(
                        VoltTrackerDb.TABLE_TELEMETRY,
                        new String[] {
                            "captured_at_ms",
                            "speed_kph",
                            "rpm",
                            "voltage",
                            "pack_voltage",
                            "pack_current_a",
                            "power_kw",
                            "soc"
                        },
                        "session_id = ?",
                        new String[] {String.valueOf(sessionId)},
                        null,
                        null,
                        "captured_at_ms ASC")) {
            while (cursor.moveToNext()) {
                Integer speed = nullableInt(cursor, "speed_kph");
                rows.add(
                        new TelemetrySample(
                                cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")),
                                speed == null ? null : speed.doubleValue(),
                                nullableInt(cursor, "rpm"),
                                nullableDouble(cursor, "voltage"),
                                nullableDouble(cursor, "pack_voltage"),
                                nullableDouble(cursor, "pack_current_a"),
                                nullableDouble(cursor, "power_kw"),
                                nullableDouble(cursor, "soc")));
            }
        }
        return Collections.unmodifiableList(rows);
    }

    // ---- writes -------------------------------------------------------------------

    void persistTrips(long sessionId, List<Trip> trips) {
        if (trips == null || trips.isEmpty()) {
            return;
        }
        long createdAt = System.currentTimeMillis();
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            for (Trip trip : trips) {
                ContentValues values = new ContentValues();
                values.put("session_id", sessionId);
                values.put("started_at_ms", trip.startedAtMs);
                values.put("ended_at_ms", trip.endedAtMs);
                values.put("route_available", trip.hasRoute ? 1 : 0);
                values.put("distance_m", trip.distanceMeters);
                values.put("max_speed_kph", trip.maxSpeedKph);
                if (trip.durationMs > 0L) {
                    values.put(
                            "avg_speed_kph",
                            (trip.distanceMeters / 1000.0) / (trip.durationMs / 3_600_000.0));
                }
                // classification is the driving regime ("city"/"highway"/"mixed"/"unknown").
                // confidence is how sure the materializer is the trip is real (0..1 score).
                // Earlier code stored Confidence.name() in both fields — that bug is fixed here.
                values.put("classification", trip.classification);
                values.put("confidence", trip.confidence.asScore());
                if (trip.energyKwh != null) {
                    values.put("energy_kwh", trip.energyKwh);
                }
                values.put("created_at_ms", createdAt);
                db.insertOrThrow(VoltTrackerDb.TABLE_TRIP_SEGMENTS, null, values);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void persistChargeSessions(long sessionId, List<ChargeSession> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        long createdAt = System.currentTimeMillis();
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            for (ChargeSession session : sessions) {
                ContentValues values = new ContentValues();
                values.put("session_id", sessionId);
                values.put("started_at_ms", session.startedAtMs);
                values.put("ended_at_ms", session.endedAtMs);
                values.put("charger_type", session.chargerType);
                values.put("interrupted", session.interruptionCount > 0 ? 1 : 0);
                values.put("confidence", session.confidence.asScore());
                if (session.voltageStart != null) {
                    values.put("voltage", session.voltageStart);
                }
                values.put("created_at_ms", createdAt);
                // summary_json carries the data the schema has no dedicated column for
                // (interruption count, voltage end, confidence label).
                values.put(
                        "summary_json",
                        "{\"interruptionCount\":"
                                + session.interruptionCount
                                + ",\"confidence\":\""
                                + session.confidence.name()
                                + "\","
                                + "\"voltageStart\":"
                                + (session.voltageStart == null ? "null" : session.voltageStart)
                                + ",\"voltageEnd\":"
                                + (session.voltageEnd == null ? "null" : session.voltageEnd)
                                + ",\"durationMs\":"
                                + session.durationMs
                                + "}");
                db.insertOrThrow(VoltTrackerDb.TABLE_CHARGE_SESSIONS, null, values);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ---- helpers ------------------------------------------------------------------

    private static Double nullableDouble(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? null : cursor.getDouble(index);
    }

    private static Integer nullableInt(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? null : cursor.getInt(index);
    }

    private static Float nullableFloat(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? null : cursor.getFloat(index);
    }
}
