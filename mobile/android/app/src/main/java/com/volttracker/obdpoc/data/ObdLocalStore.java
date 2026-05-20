package com.volttracker.obdpoc.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    public ObdLocalStore(Context context) {
        this.context = context.getApplicationContext();
        helper = new VoltTrackerDb(this.context);
    }

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
                VoltTrackerDb.TABLE_SESSIONS,
                values,
                "_id = ?",
                new String[]{String.valueOf(sessionId)}
        );
    }

    public long recordTelemetry(long sessionId, JSONObject sample) {
        long capturedAtMs = sample == null
                ? System.currentTimeMillis()
                : sample.optLong("updatedAt", System.currentTimeMillis());
        return recordTelemetry(sessionId, sample, capturedAtMs);
    }

    public long recordTelemetry(long sessionId, JSONObject sample, long capturedAtMs) {
        JSONObject safeSample = sample == null ? new JSONObject() : sample;
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
            values.put("raw", clean(safeSample.optString("raw", "")));
            values.put("json", safeSample.toString());

            long id = db.insertOrThrow(VoltTrackerDb.TABLE_TELEMETRY, null, values);
            ContentValues sessionValues = new ContentValues();
            sessionValues.put("last_event_at_ms", capturedAtMs);
            sessionValues.put("supported_pids", clean(safeSample.optString("supportedPids", "")));
            db.execSQL("UPDATE " + VoltTrackerDb.TABLE_SESSIONS
                            + " SET sample_count = sample_count + 1,"
                            + " last_event_at_ms = ?,"
                            + " supported_pids = CASE WHEN ? = '' THEN supported_pids ELSE ? END"
                            + " WHERE _id = ?",
                    new Object[]{
                            capturedAtMs,
                            sessionValues.getAsString("supported_pids"),
                            sessionValues.getAsString("supported_pids"),
                            sessionId
                    });
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
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
            if (sessionId > 0) {
                ContentValues sessionValues = new ContentValues();
                sessionValues.put("last_event_at_ms", occurredAtMs);
                db.update(VoltTrackerDb.TABLE_SESSIONS, sessionValues, "_id = ?",
                        new String[]{String.valueOf(sessionId)});
            }
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
                    VoltTrackerDb.TABLE_ADAPTER_HISTORY,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
            );
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public ObdSessionRecord getSession(long sessionId) {
        try (Cursor cursor = helper.getReadableDatabase().query(
                VoltTrackerDb.TABLE_SESSIONS,
                null,
                "_id = ?",
                new String[]{String.valueOf(sessionId)},
                null,
                null,
                null
        )) {
            return cursor.moveToFirst() ? readSession(cursor) : null;
        }
    }

    public List<ObdSessionRecord> getRecentSessions(int limit) {
        List<ObdSessionRecord> records = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query(
                VoltTrackerDb.TABLE_SESSIONS,
                null,
                null,
                null,
                null,
                null,
                "started_at_ms DESC",
                boundedLimit(limit)
        )) {
            while (cursor.moveToNext()) {
                records.add(readSession(cursor));
            }
        }
        return records;
    }

    public List<TelemetrySampleRecord> getRecentTelemetry(long sessionId, int limit) {
        List<TelemetrySampleRecord> records = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query(
                VoltTrackerDb.TABLE_TELEMETRY,
                null,
                "session_id = ?",
                new String[]{String.valueOf(sessionId)},
                null,
                null,
                "captured_at_ms DESC",
                boundedLimit(limit)
        )) {
            while (cursor.moveToNext()) {
                records.add(readTelemetry(cursor));
            }
        }
        return records;
    }

    public List<StatusEventRecord> getRecentEvents(long sessionId, int limit) {
        List<StatusEventRecord> records = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query(
                VoltTrackerDb.TABLE_EVENTS,
                null,
                "session_id = ?",
                new String[]{String.valueOf(sessionId)},
                null,
                null,
                "occurred_at_ms DESC",
                boundedLimit(limit)
        )) {
            while (cursor.moveToNext()) {
                records.add(readStatusEvent(cursor));
            }
        }
        return records;
    }

    public List<AdapterHistoryRecord> getAdapterHistory(int limit) {
        List<AdapterHistoryRecord> records = new ArrayList<>();
        try (Cursor cursor = helper.getReadableDatabase().query(
                VoltTrackerDb.TABLE_ADAPTER_HISTORY,
                null,
                null,
                null,
                null,
                null,
                "last_seen_ms DESC",
                boundedLimit(limit)
        )) {
            while (cursor.moveToNext()) {
                records.add(readAdapterHistory(cursor));
            }
        }
        return records;
    }

    public JSONObject getStorageSummary() {
        JSONObject payload = new JSONObject();
        SQLiteDatabase db = helper.getReadableDatabase();
        try {
            payload.put("database", VoltTrackerDb.DATABASE_NAME);
            payload.put("databaseBytes", getDatabaseFile().length());
            payload.put("sessionCount", countRows(db, VoltTrackerDb.TABLE_SESSIONS));
            payload.put("sampleCount", countRows(db, VoltTrackerDb.TABLE_TELEMETRY));
            payload.put("eventCount", countRows(db, VoltTrackerDb.TABLE_EVENTS));
            payload.put("adapterCount", countRows(db, VoltTrackerDb.TABLE_ADAPTER_HISTORY));
            ObdSessionRecord latest = firstOrNull(getRecentSessions(1));
            if (latest != null) {
                payload.put("lastSessionId", latest.id);
                payload.put("lastMode", latest.mode);
                payload.put("lastStatus", latest.status);
                payload.put("lastStartedAtMs", latest.startedAtMs);
                payload.put("lastEventAtMs", latest.lastEventAtMs);
                payload.put("lastSampleCount", latest.sampleCount);
                payload.put("lastAdapter", latest.adapterName);
            }
            payload.put("recentSessions", getRecentSessionsJson(6));
            payload.put("adapters", getAdapterHistoryJson(6));
        } catch (JSONException ignored) {
            // Local numeric/string values are safe.
        }
        return payload;
    }

    public JSONArray getRecentSessionsJson(int limit) {
        JSONArray payload = new JSONArray();
        for (ObdSessionRecord record : getRecentSessions(limit)) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", record.id);
                item.put("mode", record.mode);
                item.put("adapterAddress", record.adapterAddress);
                item.put("adapterName", record.adapterName);
                item.put("startedAtMs", record.startedAtMs);
                item.put("endedAtMs", record.endedAtMs);
                item.put("status", record.status);
                item.put("supportedPids", record.supportedPids);
                item.put("sampleCount", record.sampleCount);
                item.put("lastEventAtMs", record.lastEventAtMs);
            } catch (JSONException ignored) {
                // Local fields are safe.
            }
            payload.put(item);
        }
        return payload;
    }

    public JSONArray getAdapterHistoryJson(int limit) {
        JSONArray payload = new JSONArray();
        for (AdapterHistoryRecord record : getAdapterHistory(limit)) {
            JSONObject item = new JSONObject();
            try {
                item.put("adapterKey", record.adapterKey);
                item.put("address", record.address);
                item.put("name", record.name);
                item.put("firstSeenMs", record.firstSeenMs);
                item.put("lastSeenMs", record.lastSeenMs);
                item.put("connectCount", record.connectCount);
                item.put("scanCount", record.scanCount);
                item.put("demoCount", record.demoCount);
                item.put("sampleCount", record.sampleCount);
                item.put("lastSessionId", record.lastSessionId);
                item.put("lastMode", record.lastMode);
                item.put("lastStatus", record.lastStatus);
                item.put("supportedPids", record.supportedPids);
                item.put("lastEventDetail", record.lastEventDetail);
            } catch (JSONException ignored) {
                // Local fields are safe.
            }
            payload.put(item);
        }
        return payload;
    }

    public void clearAllData() {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(VoltTrackerDb.TABLE_EVENTS, null, null);
            db.delete(VoltTrackerDb.TABLE_TELEMETRY, null, null);
            db.delete(VoltTrackerDb.TABLE_SESSIONS, null, null);
            db.delete(VoltTrackerDb.TABLE_ADAPTER_HISTORY, null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public File getDatabaseFile() {
        return context.getDatabasePath(VoltTrackerDb.DATABASE_NAME);
    }

    @Override
    public void close() {
        helper.close();
    }

    private AdapterHistoryRecord findAdapterHistory(SQLiteDatabase db, String key) {
        try (Cursor cursor = db.query(
                VoltTrackerDb.TABLE_ADAPTER_HISTORY,
                null,
                "adapter_key = ?",
                new String[]{key},
                null,
                null,
                null
        )) {
            return cursor.moveToFirst() ? readAdapterHistory(cursor) : null;
        }
    }

    private static ObdSessionRecord readSession(Cursor cursor) {
        return new ObdSessionRecord(
                cursor.getLong(cursor.getColumnIndexOrThrow("_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("mode")),
                cursor.getString(cursor.getColumnIndexOrThrow("adapter_address")),
                cursor.getString(cursor.getColumnIndexOrThrow("adapter_name")),
                cursor.getLong(cursor.getColumnIndexOrThrow("started_at_ms")),
                nullableLong(cursor, "ended_at_ms"),
                cursor.getString(cursor.getColumnIndexOrThrow("status")),
                cursor.getString(cursor.getColumnIndexOrThrow("supported_pids")),
                cursor.getInt(cursor.getColumnIndexOrThrow("sample_count")),
                nullableLong(cursor, "last_event_at_ms")
        );
    }

    private static TelemetrySampleRecord readTelemetry(Cursor cursor) {
        return new TelemetrySampleRecord(
                cursor.getLong(cursor.getColumnIndexOrThrow("_id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("session_id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")),
                cursor.getString(cursor.getColumnIndexOrThrow("source")),
                cursor.getString(cursor.getColumnIndexOrThrow("vehicle_state")),
                nullableInt(cursor, "speed_kph"),
                nullableInt(cursor, "rpm"),
                nullableInt(cursor, "coolant_c"),
                nullableInt(cursor, "load_pct"),
                nullableInt(cursor, "throttle_pct"),
                nullableDouble(cursor, "voltage"),
                nullableDouble(cursor, "soc"),
                nullableDouble(cursor, "battery_temp"),
                nullableDouble(cursor, "power_kw"),
                nullableInt(cursor, "sample_number"),
                nullableLong(cursor, "session_ms"),
                cursor.getString(cursor.getColumnIndexOrThrow("raw")),
                cursor.getString(cursor.getColumnIndexOrThrow("json"))
        );
    }

    private static StatusEventRecord readStatusEvent(Cursor cursor) {
        return new StatusEventRecord(
                cursor.getLong(cursor.getColumnIndexOrThrow("_id")),
                nullableLong(cursor, "session_id"),
                cursor.getLong(cursor.getColumnIndexOrThrow("occurred_at_ms")),
                cursor.getString(cursor.getColumnIndexOrThrow("kind")),
                cursor.getString(cursor.getColumnIndexOrThrow("state")),
                cursor.getString(cursor.getColumnIndexOrThrow("detail")),
                cursor.getInt(cursor.getColumnIndexOrThrow("blocked")) != 0,
                cursor.getString(cursor.getColumnIndexOrThrow("payload"))
        );
    }

    private static AdapterHistoryRecord readAdapterHistory(Cursor cursor) {
        return new AdapterHistoryRecord(
                cursor.getString(cursor.getColumnIndexOrThrow("adapter_key")),
                cursor.getString(cursor.getColumnIndexOrThrow("address")),
                cursor.getString(cursor.getColumnIndexOrThrow("name")),
                cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_ms")),
                cursor.getLong(cursor.getColumnIndexOrThrow("last_seen_ms")),
                cursor.getInt(cursor.getColumnIndexOrThrow("connect_count")),
                cursor.getInt(cursor.getColumnIndexOrThrow("scan_count")),
                cursor.getInt(cursor.getColumnIndexOrThrow("demo_count")),
                cursor.getInt(cursor.getColumnIndexOrThrow("sample_count")),
                nullableLong(cursor, "last_session_id"),
                cursor.getString(cursor.getColumnIndexOrThrow("last_mode")),
                cursor.getString(cursor.getColumnIndexOrThrow("last_status")),
                cursor.getString(cursor.getColumnIndexOrThrow("supported_pids")),
                cursor.getString(cursor.getColumnIndexOrThrow("last_event_detail"))
        );
    }

    private static void putOptionalInt(ContentValues values, String column, JSONObject json, String key) {
        if (json.has(key) && !json.isNull(key)) {
            values.put(column, json.optInt(key));
        }
    }

    private static void putOptionalLong(ContentValues values, String column, JSONObject json, String key) {
        if (json.has(key) && !json.isNull(key)) {
            values.put(column, json.optLong(key));
        }
    }

    private static void putOptionalDouble(ContentValues values, String column, JSONObject json, String key) {
        if (json.has(key) && !json.isNull(key)) {
            values.put(column, json.optDouble(key));
        }
    }

    private static int nullableInt(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? 0 : cursor.getInt(index);
    }

    private static long nullableLong(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? 0L : cursor.getLong(index);
    }

    private static double nullableDouble(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? 0d : cursor.getDouble(index);
    }

    private static int countForMode(AdapterHistoryRecord existing, String countedMode, String newMode) {
        int current = 0;
        if (existing != null) {
            if (MODE_OBD.equals(countedMode)) {
                current = existing.connectCount;
            } else if (MODE_SCAN.equals(countedMode)) {
                current = existing.scanCount;
            } else if (MODE_DEMO.equals(countedMode)) {
                current = existing.demoCount;
            }
        }
        return countedMode.equals(newMode) ? current + 1 : current;
    }

    private static String adapterKey(String address, String mode) {
        String cleanAddress = clean(address);
        if (!cleanAddress.isEmpty()) {
            return cleanAddress.toUpperCase(Locale.US);
        }
        return MODE_DEMO.equals(mode) ? "demo" : "unknown";
    }

    private static String cleanMode(String mode) {
        String cleaned = clean(mode).toLowerCase(Locale.US);
        if (MODE_SCAN.equals(cleaned) || MODE_DEMO.equals(cleaned)) {
            return cleaned;
        }
        return MODE_OBD;
    }

    private static String cleanStatus(String status) {
        String cleaned = clean(status).toLowerCase(Locale.US);
        if (STATUS_COMPLETE.equals(cleaned)
                || STATUS_ERROR.equals(cleaned)
                || STATUS_DISCONNECTED.equals(cleaned)
                || STATUS_ACTIVE.equals(cleaned)) {
            return cleaned;
        }
        return cleaned.isEmpty() ? STATUS_COMPLETE : cleaned;
    }

    private static String chooseLatest(String candidate, String fallback) {
        return candidate == null || candidate.trim().isEmpty() ? clean(fallback) : candidate.trim();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String boundedLimit(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return String.valueOf(safeLimit);
    }

    private static long countRows(SQLiteDatabase db, String table) {
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + table, null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    private static <T> T firstOrNull(List<T> items) {
        return items.isEmpty() ? null : items.get(0);
    }
}
