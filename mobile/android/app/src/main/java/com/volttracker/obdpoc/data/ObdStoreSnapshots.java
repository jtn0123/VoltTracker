package com.volttracker.obdpoc.data;

import static com.volttracker.obdpoc.data.ObdStoreSupport.chooseLatest;
import static com.volttracker.obdpoc.data.ObdStoreSupport.clean;
import static com.volttracker.obdpoc.data.ObdStoreSupport.countForMode;
import static com.volttracker.obdpoc.data.ObdStoreSupport.putNullable;
import static com.volttracker.obdpoc.data.ObdStoreSupport.putOptionalDouble;
import static com.volttracker.obdpoc.data.ObdStoreSupport.putOptionalLong;
import static com.volttracker.obdpoc.data.ObdStoreSupport.readAdapterHistory;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Builds the per-write JSON / {@link ContentValues} "snapshots" the write path inserts into SQLite,
 * plus the diagnostic-code and adapter-history upserts that need a row lookup before writing. Split
 * out of {@link ObdLocalStore} so the façade owns transaction control while this class owns the
 * mechanical field-by-field mapping. Package-private; stateless.
 */
final class ObdStoreSnapshots {

    // ---- pid observations ----------------------------------------------------------

    /** Packs the typed parameters into the JSON shape the JSON-overload accepts. */
    JSONObject pidObservationPayload(
            long observedAtMs,
            String command,
            String header,
            String pid,
            String name,
            String valueText,
            Double valueNumeric,
            String unit,
            String rawRequest,
            String rawResponse) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("observedAtMs", observedAtMs);
            payload.put("command", clean(command));
            payload.put("header", clean(header));
            payload.put("pid", clean(pid));
            payload.put("name", clean(name));
            payload.put("valueText", clean(valueText));
            if (valueNumeric != null) {
                payload.put("valueNumeric", valueNumeric.doubleValue());
            }
            payload.put("unit", clean(unit));
            payload.put("rawRequest", clean(rawRequest));
            payload.put("rawResponse", clean(rawResponse));
        } catch (JSONException ignored) {
            // Local numeric/string values are safe.
        }
        return payload;
    }

    ContentValues pidObservationValues(long sessionId, JSONObject observation, long observedAtMs) {
        ContentValues values = new ContentValues();
        values.put("session_id", sessionId);
        values.put("observed_at_ms", observedAtMs);
        values.put("command", clean(observation.optString("command", "")));
        values.put("header", clean(observation.optString("header", "")));
        values.put("pid", clean(observation.optString("pid", "")));
        values.put("name", clean(observation.optString("name", "")));
        values.put(
                "value_text",
                clean(observation.optString("valueText", observation.optString("value", ""))));
        putOptionalDouble(values, "value_numeric", observation, "valueNumeric", "value");
        values.put("unit", clean(observation.optString("unit", "")));
        values.put(
                "raw_request",
                clean(observation.optString("rawRequest", observation.optString("request", ""))));
        values.put(
                "raw_response",
                clean(observation.optString("rawResponse", observation.optString("raw", ""))));
        values.put("json", observation.toString());
        return values;
    }

    // ---- location samples ----------------------------------------------------------

    /** Packs the typed parameters into the JSON shape the JSON-overload accepts. */
    JSONObject locationSamplePayload(
            long capturedAtMs,
            String provider,
            double latitude,
            double longitude,
            Double accuracyM,
            Double altitudeM,
            Double speedMps,
            Double bearingDeg,
            Long locationAgeMs,
            Long elapsedRealtimeNanos) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("capturedAtMs", capturedAtMs);
            payload.put("provider", clean(provider));
            payload.put("latitude", latitude);
            payload.put("longitude", longitude);
            putNullable(payload, "accuracyM", accuracyM);
            putNullable(payload, "altitudeM", altitudeM);
            putNullable(payload, "speedMps", speedMps);
            putNullable(payload, "bearingDeg", bearingDeg);
            putNullable(payload, "locationAgeMs", locationAgeMs);
            putNullable(payload, "elapsedRealtimeNanos", elapsedRealtimeNanos);
        } catch (JSONException ignored) {
            // Local numeric/string values are safe.
        }
        return payload;
    }

    ContentValues locationSampleValues(long sessionId, JSONObject sample, long capturedAtMs) {
        ContentValues values = new ContentValues();
        values.put("session_id", sessionId);
        values.put("captured_at_ms", capturedAtMs);
        values.put(
                "provider",
                clean(sample.optString("provider", sample.optString("locationProvider", ""))));
        values.put("latitude", sample.optDouble("latitude", 0d));
        values.put("longitude", sample.optDouble("longitude", 0d));
        putOptionalDouble(values, "accuracy_m", sample, "accuracyM");
        putOptionalDouble(values, "altitude_m", sample, "altitudeM");
        putOptionalDouble(values, "speed_mps", sample, "speedMps", "gpsSpeedMps");
        putOptionalDouble(values, "bearing_deg", sample, "bearingDeg");
        putOptionalLong(values, "location_age_ms", sample, "locationAgeMs");
        putOptionalLong(values, "elapsed_realtime_nanos", sample, "elapsedRealtimeNanos");
        values.put("json", sample.toString());
        return values;
    }

    // ---- diagnostic codes ----------------------------------------------------------

    /**
     * Upserts a diagnostic-code row inside the caller's transaction. Returns the row id, or {@code
     * -1L} if the payload had no usable {@code dtc}.
     */
    long upsertDiagnosticCode(
            SQLiteDatabase db, long sessionId, JSONObject diagnosticCode, long seenAtMs) {
        String dtc =
                clean(diagnosticCode.optString("dtc", diagnosticCode.optString("code", "")))
                        .toUpperCase(Locale.US);
        String status = clean(diagnosticCode.optString("status", "stored"));
        String moduleKey = clean(diagnosticCode.optString("moduleKey", "generic-obd"));
        if (dtc.isEmpty()) {
            return -1L;
        }

        long existingId = -1L;
        long firstSeenMs = seenAtMs;
        long seenCount = 0L;
        long lastSessionId = -1L;
        try (Cursor cursor =
                db.query(
                        VoltTrackerDb.TABLE_DIAGNOSTIC_CODES,
                        new String[] {"_id", "first_seen_ms", "seen_count", "last_session_id"},
                        "module_key = ? AND dtc = ? AND status = ?",
                        new String[] {moduleKey, dtc, status},
                        null,
                        null,
                        null)) {
            if (cursor.moveToFirst()) {
                existingId = cursor.getLong(cursor.getColumnIndexOrThrow("_id"));
                firstSeenMs = cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_ms"));
                seenCount = cursor.getLong(cursor.getColumnIndexOrThrow("seen_count"));
                lastSessionId = cursor.getLong(cursor.getColumnIndexOrThrow("last_session_id"));
            }
        }
        long nextSeenCount = lastSessionId == sessionId ? seenCount : seenCount + 1L;

        ContentValues values = new ContentValues();
        values.put("dtc", dtc);
        values.put("status", status);
        values.put("status_label", clean(diagnosticCode.optString("statusLabel", status)));
        values.put("module_key", moduleKey);
        values.put("module_name", clean(diagnosticCode.optString("moduleName", "")));
        values.put("header", clean(diagnosticCode.optString("header", "")));
        values.put("first_seen_ms", firstSeenMs);
        values.put("last_seen_ms", seenAtMs);
        values.put("seen_count", Math.max(1L, nextSeenCount));
        values.put("last_session_id", sessionId);
        values.put(
                "raw_response",
                clean(
                        diagnosticCode.optString(
                                "rawResponse", diagnosticCode.optString("raw", ""))));
        values.put("json", diagnosticCode.toString());

        if (existingId > 0) {
            db.update(
                    VoltTrackerDb.TABLE_DIAGNOSTIC_CODES,
                    values,
                    "_id = ?",
                    new String[] {String.valueOf(existingId)});
            return existingId;
        }
        return db.insertOrThrow(VoltTrackerDb.TABLE_DIAGNOSTIC_CODES, null, values);
    }

    // ---- adapter history -----------------------------------------------------------

    /** Reads the existing adapter-history row for {@code key}, or {@code null} if none. */
    AdapterHistoryRecord findAdapterHistory(SQLiteDatabase db, String key) {
        try (Cursor cursor =
                db.query(
                        VoltTrackerDb.TABLE_ADAPTER_HISTORY,
                        null,
                        "adapter_key = ?",
                        new String[] {key},
                        null,
                        null,
                        null)) {
            return cursor.moveToFirst() ? readAdapterHistory(cursor) : null;
        }
    }

    /**
     * Builds the upsert row for adapter history. Caller resolves {@code existing} via {@link
     * #findAdapterHistory} so this method stays pure. {@code mode} and {@code status} must already
     * be cleaned.
     */
    ContentValues adapterHistoryValues(
            AdapterHistoryRecord existing,
            String key,
            String address,
            String name,
            String mode,
            String status,
            int samples,
            String supportedPids,
            String lastEventDetail,
            long sessionId,
            long now) {
        ContentValues values = new ContentValues();
        values.put("adapter_key", key);
        values.put("address", clean(address));
        values.put("name", chooseLatest(clean(name), existing == null ? "" : existing.name));
        values.put("first_seen_ms", existing == null ? now : existing.firstSeenMs);
        values.put("last_seen_ms", now);
        values.put("connect_count", countForMode(existing, ObdLocalStore.MODE_OBD, mode));
        values.put("scan_count", countForMode(existing, ObdLocalStore.MODE_SCAN, mode));
        values.put("demo_count", countForMode(existing, ObdLocalStore.MODE_DEMO, mode));
        values.put(
                "sample_count",
                Math.max(0, existing == null ? 0 : existing.sampleCount) + Math.max(0, samples));
        if (sessionId > 0) {
            values.put("last_session_id", sessionId);
        }
        values.put("last_mode", mode);
        values.put("last_status", status);
        values.put(
                "supported_pids",
                chooseLatest(clean(supportedPids), existing == null ? "" : existing.supportedPids));
        values.put("last_event_detail", clean(lastEventDetail));
        return values;
    }
}
