package com.volttracker.obdpoc.data;

import static com.volttracker.obdpoc.data.ObdStoreSupport.adapterKey;
import static com.volttracker.obdpoc.data.ObdStoreSupport.chooseLatest;
import static com.volttracker.obdpoc.data.ObdStoreSupport.clean;
import static com.volttracker.obdpoc.data.ObdStoreSupport.cleanMode;
import static com.volttracker.obdpoc.data.ObdStoreSupport.cleanStatus;
import static com.volttracker.obdpoc.data.ObdStoreSupport.countForMode;
import static com.volttracker.obdpoc.data.ObdStoreSupport.isUsefulTelemetry;
import static com.volttracker.obdpoc.data.ObdStoreSupport.optTimestamp;
import static com.volttracker.obdpoc.data.ObdStoreSupport.putNullable;
import static com.volttracker.obdpoc.data.ObdStoreSupport.putOptionalBool;
import static com.volttracker.obdpoc.data.ObdStoreSupport.putOptionalDouble;
import static com.volttracker.obdpoc.data.ObdStoreSupport.putOptionalInt;
import static com.volttracker.obdpoc.data.ObdStoreSupport.putOptionalLong;
import static com.volttracker.obdpoc.data.ObdStoreSupport.readAdapterHistory;
import static com.volttracker.obdpoc.data.ObdStoreSupport.updateSessionLastEvent;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * On-device SQLite store for OBD sessions, telemetry, GPS, events and adapter history.
 *
 * <p>This class is the stable public facade. The write path lives here; read-side JSON
 * projections are delegated to {@link ObdStoreReports} and {@link ObdStoreTrips}, and
 * stateless helpers to {@link ObdStoreSupport}, so every file stays focused and small.
 */
public final class ObdLocalStore implements Closeable {
    public static final String MODE_OBD = "obd";
    public static final String MODE_SCAN = "scan";
    public static final String MODE_DEMO = "demo";

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_COMPLETE = "complete";
    public static final String STATUS_ERROR = "error";
    public static final String STATUS_DISCONNECTED = "disconnected";

    private final Context context;
    private final VoltTrackerDb helper;
    private final ObdStoreTrips trips;
    private final ObdStoreReports reports;

    public ObdLocalStore(Context context) {
        this.context = context.getApplicationContext();
        helper = new VoltTrackerDb(this.context);
        trips = new ObdStoreTrips(helper);
        reports = new ObdStoreReports(helper, trips);
    }

    // ---- session lifecycle ---------------------------------------------------------

    public long startSession(String mode, String adapterAddress, String adapterName) {
        return startSession(mode, adapterAddress, adapterName, System.currentTimeMillis());
    }

    public long startSession(String mode, String adapterAddress, String adapterName, long startedAtMs) {
        ContentValues values = new ContentValues();
        values.put("mode", cleanMode(mode));
        values.put("adapter_address", clean(adapterAddress));
        values.put("adapter_name", clean(adapterName));
        values.put("started_at_ms", startedAtMs);
        values.put("status", STATUS_ACTIVE);
        values.put("created_at_ms", System.currentTimeMillis());
        return helper.getWritableDatabase().insertOrThrow(VoltTrackerDb.TABLE_SESSIONS, null, values);
    }

    public void finishSession(long sessionId, String status) {
        finishSession(sessionId, status, System.currentTimeMillis(), null);
    }

    public void finishSession(long sessionId, String status, long endedAtMs, String supportedPids) {
        ContentValues values = new ContentValues();
        values.put("ended_at_ms", endedAtMs);
        values.put("status", cleanStatus(status));
        if (supportedPids != null) {
            values.put("supported_pids", supportedPids);
        }
        helper.getWritableDatabase().update(
                VoltTrackerDb.TABLE_SESSIONS, values, "_id = ?",
                new String[]{String.valueOf(sessionId)});
    }

    // ---- telemetry / observations / location --------------------------------------

    public long recordTelemetry(long sessionId, JSONObject sample) {
        long capturedAtMs = sample == null
                ? System.currentTimeMillis()
                : sample.optLong("updatedAt", System.currentTimeMillis());
        return recordTelemetry(sessionId, sample, capturedAtMs);
    }

    public long recordTelemetry(long sessionId, JSONObject sample, long capturedAtMs) {
        JSONObject safeSample = sample == null ? new JSONObject() : sample;
        if (!isUsefulTelemetry(safeSample)) {
            return -1L;
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("session_id", sessionId);
            values.put("captured_at_ms", capturedAtMs);
            values.put("source", clean(safeSample.optString("source", "")));
            values.put("vehicle_state", clean(safeSample.optString("vehicleState", "")));
            putOptionalInt(values, "speed_kph", safeSample, "speedKph");
            putOptionalInt(values, "rpm", safeSample, "rpm");
            putOptionalInt(values, "coolant_c", safeSample, "coolantC");
            putOptionalInt(values, "load_pct", safeSample, "loadPct");
            putOptionalInt(values, "throttle_pct", safeSample, "throttlePct");
            putOptionalDouble(values, "voltage", safeSample, "voltage");
            putOptionalDouble(values, "soc", safeSample, "soc");
            putOptionalDouble(values, "battery_temp", safeSample, "batteryTemp");
            putOptionalDouble(values, "power_kw", safeSample, "powerKw");
            putOptionalDouble(values, "latitude", safeSample, "latitude");
            putOptionalDouble(values, "longitude", safeSample, "longitude");
            putOptionalDouble(values, "accuracy_m", safeSample, "accuracyM");
            putOptionalDouble(values, "gps_speed_mps", safeSample, "gpsSpeedMps");
            putOptionalDouble(values, "bearing_deg", safeSample, "bearingDeg");
            putOptionalLong(values, "location_age_ms", safeSample, "locationAgeMs");
            putOptionalInt(values, "sample_number", safeSample, "sampleCount");
            putOptionalLong(values, "session_ms", safeSample, "sessionMs");
            putOptionalBool(values, "charge_transition_hint", safeSample, "chargeTransitionHint");
            putOptionalBool(values, "app_foreground", safeSample, "appForeground");
            values.put("raw", clean(safeSample.optString("raw", "")));
            values.put("json", safeSample.toString());

            long id = db.insertOrThrow(VoltTrackerDb.TABLE_TELEMETRY, null, values);
            String supportedPids = clean(safeSample.optString("supportedPids", ""));
            db.execSQL("UPDATE " + VoltTrackerDb.TABLE_SESSIONS
                            + " SET sample_count = sample_count + 1,"
                            + " last_event_at_ms = ?,"
                            + " supported_pids = CASE WHEN ? = '' THEN supported_pids ELSE ? END"
                            + " WHERE _id = ?",
                    new Object[]{capturedAtMs, supportedPids, supportedPids, sessionId});
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    public long recordPidObservation(long sessionId, JSONObject observation) {
        JSONObject safeObservation = observation == null ? new JSONObject() : observation;
        long observedAtMs = optTimestamp(safeObservation, "observedAtMs",
                optTimestamp(safeObservation, "observedAt", safeObservation.optLong("updatedAt", System.currentTimeMillis())));
        return recordPidObservation(sessionId, safeObservation, observedAtMs);
    }

    public long recordPidObservation(long sessionId, JSONObject observation, long observedAtMs) {
        JSONObject safeObservation = observation == null ? new JSONObject() : observation;
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("session_id", sessionId);
            values.put("observed_at_ms", observedAtMs);
            values.put("command", clean(safeObservation.optString("command", "")));
            values.put("header", clean(safeObservation.optString("header", "")));
            values.put("pid", clean(safeObservation.optString("pid", "")));
            values.put("name", clean(safeObservation.optString("name", "")));
            values.put("value_text", clean(safeObservation.optString("valueText",
                    safeObservation.optString("value", ""))));
            putOptionalDouble(values, "value_numeric", safeObservation, "valueNumeric", "value");
            values.put("unit", clean(safeObservation.optString("unit", "")));
            values.put("raw_request", clean(safeObservation.optString("rawRequest",
                    safeObservation.optString("request", ""))));
            values.put("raw_response", clean(safeObservation.optString("rawResponse",
                    safeObservation.optString("raw", ""))));
            values.put("json", safeObservation.toString());

            long id = db.insertOrThrow(VoltTrackerDb.TABLE_PID_OBSERVATIONS, null, values);
            updateSessionLastEvent(db, sessionId, observedAtMs);
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    public long recordPidObservation(
            long sessionId,
            long observedAtMs,
            String command,
            String header,
            String pid,
            String name,
            String valueText,
            Double valueNumeric,
            String unit,
            String rawRequest,
            String rawResponse
    ) {
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
        return recordPidObservation(sessionId, payload, observedAtMs);
    }

    public long recordDiagnosticCode(long sessionId, JSONObject diagnosticCode) {
        JSONObject safeCode = diagnosticCode == null ? new JSONObject() : diagnosticCode;
        long seenAtMs = optTimestamp(safeCode, "seenAtMs",
                optTimestamp(safeCode, "observedAtMs", System.currentTimeMillis()));
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            String dtc = clean(safeCode.optString("dtc", safeCode.optString("code", "")))
                    .toUpperCase(Locale.US);
            String status = clean(safeCode.optString("status", "stored"));
            String moduleKey = clean(safeCode.optString("moduleKey", "generic-obd"));
            if (dtc.isEmpty()) {
                return -1L;
            }

            long existingId = -1L;
            long firstSeenMs = seenAtMs;
            long seenCount = 0L;
            long lastSessionId = -1L;
            try (Cursor cursor = db.query(
                    VoltTrackerDb.TABLE_DIAGNOSTIC_CODES,
                    new String[]{"_id", "first_seen_ms", "seen_count", "last_session_id"},
                    "module_key = ? AND dtc = ? AND status = ?",
                    new String[]{moduleKey, dtc, status}, null, null, null)) {
                if (cursor.moveToFirst()) {
                    existingId = cursor.getLong(cursor.getColumnIndexOrThrow("_id"));
                    firstSeenMs = cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_ms"));
                    seenCount = cursor.getLong(cursor.getColumnIndexOrThrow("seen_count"));
                    lastSessionId = cursor.getLong(cursor.getColumnIndexOrThrow("last_session_id"));
                }
            }

            ContentValues values = new ContentValues();
            values.put("dtc", dtc);
            values.put("status", status);
            values.put("status_label", clean(safeCode.optString("statusLabel", status)));
            values.put("module_key", moduleKey);
            values.put("module_name", clean(safeCode.optString("moduleName", "")));
            values.put("header", clean(safeCode.optString("header", "")));
            values.put("first_seen_ms", firstSeenMs);
            values.put("last_seen_ms", seenAtMs);
            long nextSeenCount = lastSessionId == sessionId ? seenCount : seenCount + 1L;
            values.put("seen_count", Math.max(1L, nextSeenCount));
            values.put("last_session_id", sessionId);
            values.put("raw_response", clean(safeCode.optString("rawResponse",
                    safeCode.optString("raw", ""))));
            values.put("json", safeCode.toString());

            long id;
            if (existingId > 0) {
                db.update(VoltTrackerDb.TABLE_DIAGNOSTIC_CODES, values, "_id = ?",
                        new String[]{String.valueOf(existingId)});
                id = existingId;
            } else {
                id = db.insertOrThrow(VoltTrackerDb.TABLE_DIAGNOSTIC_CODES, null, values);
            }
            updateSessionLastEvent(db, sessionId, seenAtMs);
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    public long recordLocationSample(long sessionId, JSONObject sample) {
        JSONObject safeSample = sample == null ? new JSONObject() : sample;
        long capturedAtMs = optTimestamp(safeSample, "capturedAtMs",
                optTimestamp(safeSample, "timestampMs", safeSample.optLong("updatedAt", System.currentTimeMillis())));
        return recordLocationSample(sessionId, safeSample, capturedAtMs);
    }

    public long recordLocationSample(long sessionId, JSONObject sample, long capturedAtMs) {
        JSONObject safeSample = sample == null ? new JSONObject() : sample;
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("session_id", sessionId);
            values.put("captured_at_ms", capturedAtMs);
            values.put("provider", clean(safeSample.optString("provider",
                    safeSample.optString("locationProvider", ""))));
            values.put("latitude", safeSample.optDouble("latitude", 0d));
            values.put("longitude", safeSample.optDouble("longitude", 0d));
            putOptionalDouble(values, "accuracy_m", safeSample, "accuracyM");
            putOptionalDouble(values, "altitude_m", safeSample, "altitudeM");
            putOptionalDouble(values, "speed_mps", safeSample, "speedMps", "gpsSpeedMps");
            putOptionalDouble(values, "bearing_deg", safeSample, "bearingDeg");
            putOptionalLong(values, "location_age_ms", safeSample, "locationAgeMs");
            putOptionalLong(values, "elapsed_realtime_nanos", safeSample, "elapsedRealtimeNanos");
            values.put("json", safeSample.toString());

            long id = db.insertOrThrow(VoltTrackerDb.TABLE_LOCATION_SAMPLES, null, values);
            updateSessionLastEvent(db, sessionId, capturedAtMs);
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    public long recordLocationSample(
            long sessionId,
            long capturedAtMs,
            String provider,
            double latitude,
            double longitude,
            Double accuracyM,
            Double altitudeM,
            Double speedMps,
            Double bearingDeg,
            Long locationAgeMs,
            Long elapsedRealtimeNanos
    ) {
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
        return recordLocationSample(sessionId, payload, capturedAtMs);
    }

    public long recordStatus(long sessionId, String state, String detail, boolean blocked, JSONObject payload) {
        return recordEvent(sessionId, "status", state, detail, blocked, payload);
    }

    public long recordEvent(long sessionId, String kind, String state, String detail, boolean blocked, JSONObject payload) {
        long occurredAtMs = payload == null
                ? System.currentTimeMillis()
                : payload.optLong("updatedAt", System.currentTimeMillis());
        JSONObject safePayload = payload == null ? new JSONObject() : payload;
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            if (sessionId > 0) {
                values.put("session_id", sessionId);
            }
            values.put("occurred_at_ms", occurredAtMs);
            values.put("kind", clean(kind).isEmpty() ? "event" : clean(kind));
            values.put("state", clean(state));
            values.put("detail", clean(detail));
            values.put("blocked", blocked ? 1 : 0);
            values.put("payload", safePayload.toString());
            long id = db.insertOrThrow(VoltTrackerDb.TABLE_EVENTS, null, values);
            updateSessionLastEvent(db, sessionId, occurredAtMs);
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    public void recordAdapterSummary(
            String address,
            String name,
            String mode,
            long sessionId,
            String status,
            int samples,
            String supportedPids,
            String lastEventDetail
    ) {
        long now = System.currentTimeMillis();
        String cleanMode = cleanMode(mode);
        String key = adapterKey(address, cleanMode);
        String safeAddress = clean(address);
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            AdapterHistoryRecord existing = findAdapterHistory(db, key);
            ContentValues values = new ContentValues();
            values.put("adapter_key", key);
            values.put("address", safeAddress);
            values.put("name", chooseLatest(clean(name), existing == null ? "" : existing.name));
            values.put("first_seen_ms", existing == null ? now : existing.firstSeenMs);
            values.put("last_seen_ms", now);
            values.put("connect_count", countForMode(existing, MODE_OBD, cleanMode));
            values.put("scan_count", countForMode(existing, MODE_SCAN, cleanMode));
            values.put("demo_count", countForMode(existing, MODE_DEMO, cleanMode));
            values.put("sample_count", Math.max(0, existing == null ? 0 : existing.sampleCount) + Math.max(0, samples));
            if (sessionId > 0) {
                values.put("last_session_id", sessionId);
            }
            values.put("last_mode", cleanMode);
            values.put("last_status", cleanStatus(status));
            values.put("supported_pids", chooseLatest(clean(supportedPids),
                    existing == null ? "" : existing.supportedPids));
            values.put("last_event_detail", clean(lastEventDetail));
            db.insertWithOnConflict(
                    VoltTrackerDb.TABLE_ADAPTER_HISTORY, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private AdapterHistoryRecord findAdapterHistory(SQLiteDatabase db, String key) {
        try (Cursor cursor = db.query(
                VoltTrackerDb.TABLE_ADAPTER_HISTORY, null, "adapter_key = ?",
                new String[]{key}, null, null, null)) {
            return cursor.moveToFirst() ? readAdapterHistory(cursor) : null;
        }
    }

    // ---- typed-record reads (delegated) --------------------------------------------

    public ObdSessionRecord getSession(long sessionId) {
        return reports.getSession(sessionId);
    }

    public List<ObdSessionRecord> getRecentSessions(int limit) {
        return reports.getRecentSessions(limit);
    }

    public List<TelemetrySampleRecord> getRecentTelemetry(long sessionId, int limit) {
        return reports.getRecentTelemetry(sessionId, limit);
    }

    public List<StatusEventRecord> getRecentEvents(long sessionId, int limit) {
        return reports.getRecentEvents(sessionId, limit);
    }

    public List<AdapterHistoryRecord> getAdapterHistory(int limit) {
        return reports.getAdapterHistory(limit);
    }

    // ---- JSON projections (delegated) ----------------------------------------------

    public JSONObject getStorageSummary() {
        return reports.storageSummary(getDatabaseFile());
    }

    public JSONArray getRecentSessionsJson(int limit) {
        return reports.recentSessionsJson(limit);
    }

    public JSONArray getAdapterHistoryJson(int limit) {
        return reports.adapterHistoryJson(limit);
    }

    public JSONArray getTripsJson(int limit) {
        return trips.tripsJson(limit);
    }

    public JSONObject getInsightsJson() {
        return trips.insightsJson();
    }

    // ---- maintenance ---------------------------------------------------------------

    public void clearAllData() {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(VoltTrackerDb.TABLE_CELL_SNAPSHOTS, null, null);
            db.delete(VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS, null, null);
            db.delete(VoltTrackerDb.TABLE_EXPORTS, null, null);
            db.delete(VoltTrackerDb.TABLE_CHARGE_SESSIONS, null, null);
            db.delete(VoltTrackerDb.TABLE_TRIP_SEGMENTS, null, null);
            db.delete(VoltTrackerDb.TABLE_FIELD_CAPABILITIES, null, null);
            db.delete(VoltTrackerDb.TABLE_LOCATION_SAMPLES, null, null);
            db.delete(VoltTrackerDb.TABLE_DIAGNOSTIC_CODES, null, null);
            db.delete(VoltTrackerDb.TABLE_PID_OBSERVATIONS, null, null);
            db.delete(VoltTrackerDb.TABLE_EVENTS, null, null);
            db.delete(VoltTrackerDb.TABLE_TELEMETRY, null, null);
            db.delete(VoltTrackerDb.TABLE_SESSIONS, null, null);
            db.delete(VoltTrackerDb.TABLE_VEHICLES, null, null);
            db.delete(VoltTrackerDb.TABLE_ADAPTER_HISTORY, null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public File getDatabaseFile() {
        return context.getDatabasePath(VoltTrackerDb.DATABASE_NAME);
    }

    /** Flushes the write-ahead log into the main DB file so a file copy is a complete backup. */
    public void checkpoint() {
        try {
            helper.getWritableDatabase().execSQL("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (RuntimeException ignored) {
            // Backup proceeds with whatever is already in the main file.
        }
    }

    @Override
    public void close() {
        helper.close();
    }
}
