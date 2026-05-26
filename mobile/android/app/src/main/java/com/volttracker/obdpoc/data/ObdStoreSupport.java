package com.volttracker.obdpoc.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Stateless helpers shared by the {@link ObdLocalStore} layer: ContentValues/Cursor marshalling,
 * small SQL count/aggregate queries, value cleaning, and route geometry. Extracted from {@code
 * ObdLocalStore} so each store class stays focused and small; report and writer classes
 * static-import these.
 */
final class ObdStoreSupport {

    private ObdStoreSupport() {}

    /** A telemetry row counts as "useful" only if it carries at least one real reading. */
    static final String USEFUL_TELEMETRY_WHERE =
            "("
                    + "COALESCE(source, '') != ''"
                    + " OR speed_kph IS NOT NULL"
                    + " OR rpm IS NOT NULL"
                    + " OR voltage IS NOT NULL"
                    + " OR TRIM(COALESCE(raw, '')) != ''"
                    + ")";

    // ---- ContentValues / JSON marshalling ------------------------------------------

    static void putOptionalInt(ContentValues values, String column, JSONObject json, String key) {
        if (json.has(key) && !json.isNull(key)) {
            values.put(column, json.optInt(key));
        }
    }

    static void putOptionalLong(ContentValues values, String column, JSONObject json, String key) {
        if (json.has(key) && !json.isNull(key)) {
            values.put(column, json.optLong(key));
        }
    }

    static void putOptionalBool(ContentValues values, String column, JSONObject json, String key) {
        if (json.has(key) && !json.isNull(key)) {
            values.put(column, json.optBoolean(key) ? 1 : 0);
        }
    }

    static void putOptionalDouble(
            ContentValues values, String column, JSONObject json, String key) {
        if (json.has(key) && !json.isNull(key)) {
            values.put(column, json.optDouble(key));
        }
    }

    static void putOptionalDouble(
            ContentValues values,
            String column,
            JSONObject json,
            String primaryKey,
            String fallbackKey) {
        if (json.has(primaryKey) && !json.isNull(primaryKey)) {
            values.put(column, json.optDouble(primaryKey));
        } else if (json.has(fallbackKey) && !json.isNull(fallbackKey)) {
            values.put(column, json.optDouble(fallbackKey));
        }
    }

    static void putNullable(JSONObject json, String key, Double value) throws JSONException {
        if (value != null) {
            json.put(key, value.doubleValue());
        }
    }

    static void putNullable(JSONObject json, String key, Long value) throws JSONException {
        if (value != null) {
            json.put(key, value.longValue());
        }
    }

    static long optTimestamp(JSONObject json, String key, long fallback) {
        return json.has(key) && !json.isNull(key) ? json.optLong(key, fallback) : fallback;
    }

    static int nullableInt(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? 0 : cursor.getInt(index);
    }

    /**
     * Boxed variant of {@link #nullableInt} for JSON-projection paths. Returns {@code null} (which
     * {@link org.json.JSONObject#put} preserves as JSON {@code null}) instead of {@code 0}, so the
     * dashboard can distinguish "value not observed" from "value is zero" — important for columns
     * like {@code speed_kph} where 0 km/h is a valid reading.
     */
    static Integer nullableIntBoxed(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? null : cursor.getInt(index);
    }

    static long nullableLong(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? 0L : cursor.getLong(index);
    }

    static double nullableDouble(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? 0d : cursor.getDouble(index);
    }

    /**
     * Boxed counterpart of {@link #nullableDouble} for JSON-projection paths. See {@link
     * #nullableIntBoxed}.
     */
    static Double nullableDoubleBoxed(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? null : cursor.getDouble(index);
    }

    // ---- Cursor -> record mappers --------------------------------------------------

    static ObdSessionRecord readSession(Cursor cursor) {
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
                nullableLong(cursor, "last_event_at_ms"));
    }

    static TelemetrySampleRecord readTelemetry(Cursor cursor) {
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
                cursor.getString(cursor.getColumnIndexOrThrow("json")));
    }

    static StatusEventRecord readStatusEvent(Cursor cursor) {
        return new StatusEventRecord(
                cursor.getLong(cursor.getColumnIndexOrThrow("_id")),
                nullableLong(cursor, "session_id"),
                cursor.getLong(cursor.getColumnIndexOrThrow("occurred_at_ms")),
                cursor.getString(cursor.getColumnIndexOrThrow("kind")),
                cursor.getString(cursor.getColumnIndexOrThrow("state")),
                cursor.getString(cursor.getColumnIndexOrThrow("detail")),
                cursor.getInt(cursor.getColumnIndexOrThrow("blocked")) != 0,
                cursor.getString(cursor.getColumnIndexOrThrow("payload")));
    }

    static AdapterHistoryRecord readAdapterHistory(Cursor cursor) {
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
                cursor.getString(cursor.getColumnIndexOrThrow("last_event_detail")));
    }

    // ---- value cleaning ------------------------------------------------------------

    static int countForMode(AdapterHistoryRecord existing, String countedMode, String newMode) {
        int current = 0;
        if (existing != null) {
            if (ObdLocalStore.MODE_OBD.equals(countedMode)) {
                current = existing.connectCount;
            } else if (ObdLocalStore.MODE_SCAN.equals(countedMode)) {
                current = existing.scanCount;
            } else if (ObdLocalStore.MODE_DEMO.equals(countedMode)) {
                current = existing.demoCount;
            }
        }
        return countedMode.equals(newMode) ? current + 1 : current;
    }

    static String adapterKey(String address, String mode) {
        String cleanAddress = clean(address);
        if (!cleanAddress.isEmpty()) {
            return cleanAddress.toUpperCase(Locale.US);
        }
        return ObdLocalStore.MODE_DEMO.equals(mode) ? "demo" : "unknown";
    }

    static String cleanMode(String mode) {
        String cleaned = clean(mode).toLowerCase(Locale.US);
        if (ObdLocalStore.MODE_SCAN.equals(cleaned) || ObdLocalStore.MODE_DEMO.equals(cleaned)) {
            return cleaned;
        }
        return ObdLocalStore.MODE_OBD;
    }

    static String cleanStatus(String status) {
        String cleaned = clean(status).toLowerCase(Locale.US);
        if (ObdLocalStore.STATUS_COMPLETE.equals(cleaned)
                || ObdLocalStore.STATUS_ERROR.equals(cleaned)
                || ObdLocalStore.STATUS_DISCONNECTED.equals(cleaned)
                || ObdLocalStore.STATUS_ACTIVE.equals(cleaned)) {
            return cleaned;
        }
        return cleaned.isEmpty() ? ObdLocalStore.STATUS_COMPLETE : cleaned;
    }

    static String chooseLatest(String candidate, String fallback) {
        return candidate == null || candidate.trim().isEmpty() ? clean(fallback) : candidate.trim();
    }

    static boolean isUsefulTelemetry(JSONObject sample) {
        if (sample == null || sample.length() == 0) {
            return false;
        }
        if (!clean(sample.optString("source", "")).isEmpty()) {
            return true;
        }
        if (!clean(sample.optString("raw", "")).isEmpty()) {
            return true;
        }
        return sample.has("speedKph")
                || sample.has("rpm")
                || sample.has("voltage")
                || sample.has("latitude")
                || sample.has("longitude")
                || sample.has("soc")
                || sample.has("batteryTemp")
                || sample.has("powerKw");
    }

    static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    static String boundedLimit(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return String.valueOf(safeLimit);
    }

    // ---- small SQL queries ---------------------------------------------------------

    /**
     * Guards string-built SQL helpers below. Table names cannot be parameterized in SQLite, so we
     * allow them to be inlined — but only if they are a known table. Anything else is a bug (or a
     * hostile caller) and should fail fast.
     */
    private static String requireKnownTable(String table) {
        if (!VoltTrackerDb.KNOWN_TABLES.contains(table)) {
            throw new IllegalArgumentException("Unknown SQL table: " + table);
        }
        return table;
    }

    /** Same idea for column names — they too cannot be parameterized. */
    private static String requireSimpleIdentifier(String column) {
        if (column == null || column.isEmpty() || !column.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Unsafe SQL identifier: " + column);
        }
        return column;
    }

    static long countRows(SQLiteDatabase db, String table) {
        try (Cursor cursor =
                db.rawQuery("SELECT COUNT(*) FROM " + requireKnownTable(table), null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    static long countRowsWhere(SQLiteDatabase db, String table, String where, String[] args) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT COUNT(*) FROM " + requireKnownTable(table) + " WHERE " + where,
                        args)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    static int maxInt(SQLiteDatabase db, String table, String column) {
        String safeTable = requireKnownTable(table);
        String safeColumn = requireSimpleIdentifier(column);
        String where =
                VoltTrackerDb.TABLE_TELEMETRY.equals(safeTable)
                        ? " WHERE " + USEFUL_TELEMETRY_WHERE
                        : "";
        try (Cursor cursor =
                db.rawQuery("SELECT MAX(" + safeColumn + ") FROM " + safeTable + where, null)) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getInt(0) : 0;
        }
    }

    static double maxDouble(SQLiteDatabase db, String table, String column) {
        String safeTable = requireKnownTable(table);
        String safeColumn = requireSimpleIdentifier(column);
        String where =
                VoltTrackerDb.TABLE_TELEMETRY.equals(safeTable)
                        ? " WHERE " + USEFUL_TELEMETRY_WHERE
                        : "";
        try (Cursor cursor =
                db.rawQuery("SELECT MAX(" + safeColumn + ") FROM " + safeTable + where, null)) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getDouble(0) : 0d;
        }
    }

    static int maxIntForSession(SQLiteDatabase db, String column, long sessionId) {
        Integer boxed = maxIntForSessionBoxed(db, column, sessionId);
        return boxed == null ? 0 : boxed;
    }

    /**
     * Boxed counterpart of {@link #maxIntForSession} for callers that need to distinguish "no
     * useful rows" from "max value is 0". The primitive overload returns 0 in both cases, which the
     * dashboard renders as "0 mph" — misleading when the session simply has no usable speed samples
     * (e.g. the Volt's 0xFF sentinel suppresses speedKph during charge).
     */
    static Integer maxIntForSessionBoxed(SQLiteDatabase db, String column, long sessionId) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT MAX("
                                + requireSimpleIdentifier(column)
                                + ") FROM "
                                + VoltTrackerDb.TABLE_TELEMETRY
                                + " WHERE session_id = ? AND "
                                + USEFUL_TELEMETRY_WHERE,
                        new String[] {String.valueOf(sessionId)})) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getInt(0) : null;
        }
    }

    static long averageSampleIntervalMs(SQLiteDatabase db, long sessionId) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT MIN(captured_at_ms), MAX(captured_at_ms), COUNT(*) FROM "
                                + VoltTrackerDb.TABLE_TELEMETRY
                                + " WHERE session_id = ? AND "
                                + USEFUL_TELEMETRY_WHERE,
                        new String[] {String.valueOf(sessionId)})) {
            if (!cursor.moveToFirst() || cursor.getLong(2) < 2) {
                return 0L;
            }
            return Math.max(
                    0L,
                    Math.round(
                            (cursor.getDouble(1) - cursor.getDouble(0)) / (cursor.getLong(2) - 1)));
        }
    }

    static long averageSampleIntervalMs(SQLiteDatabase db) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT MIN(captured_at_ms), MAX(captured_at_ms), COUNT(*) FROM "
                                + VoltTrackerDb.TABLE_TELEMETRY
                                + " WHERE "
                                + USEFUL_TELEMETRY_WHERE,
                        null)) {
            if (!cursor.moveToFirst() || cursor.getLong(2) < 2) {
                return 0L;
            }
            return Math.max(
                    0L,
                    Math.round(
                            (cursor.getDouble(1) - cursor.getDouble(0)) / (cursor.getLong(2) - 1)));
        }
    }

    static void updateSessionLastEvent(SQLiteDatabase db, long sessionId, long occurredAtMs) {
        if (sessionId <= 0) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("last_event_at_ms", occurredAtMs);
        db.update(
                VoltTrackerDb.TABLE_SESSIONS,
                values,
                "_id = ?",
                new String[] {String.valueOf(sessionId)});
    }

    // ---- JSON utilities ------------------------------------------------------------

    static JSONObject parseObject(String json) {
        try {
            return json == null || json.trim().isEmpty() ? new JSONObject() : new JSONObject(json);
        } catch (JSONException ex) {
            return new JSONObject();
        }
    }

    static JSONArray reverse(JSONArray source) throws JSONException {
        JSONArray target = new JSONArray();
        for (int i = source.length() - 1; i >= 0; i -= 1) {
            target.put(source.get(i));
        }
        return target;
    }

    static <T> T firstOrNull(List<T> items) {
        return items.isEmpty() ? null : items.get(0);
    }

    // ---- session reads shared by the report classes --------------------------------

    static List<ObdSessionRecord> getRecentSessions(SQLiteDatabase db, int limit) {
        List<ObdSessionRecord> records = new ArrayList<>();
        try (Cursor cursor =
                db.query(
                        VoltTrackerDb.TABLE_SESSIONS,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "started_at_ms DESC",
                        boundedLimit(limit))) {
            while (cursor.moveToNext()) {
                records.add(readSession(cursor));
            }
        }
        return records;
    }

    static JSONObject sessionToJson(ObdSessionRecord record) throws JSONException {
        JSONObject item = new JSONObject();
        item.put("id", record.id);
        item.put("mode", record.mode);
        item.put("adapterName", record.adapterName);
        item.put("adapterAddress", record.adapterAddress);
        item.put("startedAtMs", record.startedAtMs);
        item.put("endedAtMs", record.endedAtMs);
        item.put("status", record.status);
        item.put("sampleCount", record.sampleCount);
        item.put("lastEventAtMs", record.lastEventAtMs);
        return item;
    }

    // ---- route geometry ------------------------------------------------------------

    /**
     * Sums the great-circle distance between consecutive route points using the haversine formula.
     * Assumes the caller has already noise-filtered the points (see {@link
     * com.volttracker.obdpoc.location.LocationFilter}); this method does no smoothing of its own,
     * so a single bad GPS sample will inflate the total.
     *
     * <p>Each point is a {@code JSONObject} with numeric {@code "lat"} and {@code "lng"} keys.
     * Missing or zero coordinates are still summed — pre-filter the array if that is not what you
     * want.
     *
     * @return distance in meters, or {@code 0} for an empty or single-point array.
     */
    static double distanceMeters(JSONArray points) throws JSONException {
        double total = 0d;
        JSONObject previous = null;
        for (int i = 0; i < points.length(); i++) {
            JSONObject point = points.getJSONObject(i);
            // JSONObject.optDouble returns NaN for missing keys. Skipping the segment is much
            // safer than summing NaN into `total` — once `total` goes NaN, `JSONObject.put` of
            // the result throws JSONException, and the outer tripJson catch silently drops the
            // entire trip. Callers today pre-filter NULL coordinates in SQL, but the defensive
            // skip keeps the helper honest if a future caller forgets to.
            if (!hasFiniteLatLng(point)) {
                previous = point;
                continue;
            }
            if (previous != null && hasFiniteLatLng(previous)) {
                total +=
                        haversineMeters(
                                previous.optDouble("lat"), previous.optDouble("lng"),
                                point.optDouble("lat"), point.optDouble("lng"));
            }
            previous = point;
        }
        return total;
    }

    private static boolean hasFiniteLatLng(JSONObject point) {
        double lat = point.optDouble("lat");
        double lng = point.optDouble("lng");
        return !Double.isNaN(lat) && !Double.isNaN(lng);
    }

    static JSONObject boundsFor(JSONArray points) throws JSONException {
        JSONObject payload = new JSONObject();
        if (points.length() == 0) {
            return payload;
        }
        double minLat = Double.MAX_VALUE;
        double maxLat = -Double.MAX_VALUE;
        double minLng = Double.MAX_VALUE;
        double maxLng = -Double.MAX_VALUE;
        for (int i = 0; i < points.length(); i++) {
            JSONObject point = points.getJSONObject(i);
            double lat = point.optDouble("lat");
            double lng = point.optDouble("lng");
            minLat = Math.min(minLat, lat);
            maxLat = Math.max(maxLat, lat);
            minLng = Math.min(minLng, lng);
            maxLng = Math.max(maxLng, lng);
        }
        payload.put("minLat", minLat);
        payload.put("maxLat", maxLat);
        payload.put("minLng", minLng);
        payload.put("maxLng", maxLng);
        return payload;
    }

    static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double earthMeters = 6371000d;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a =
                Math.sin(dLat / 2d) * Math.sin(dLat / 2d)
                        + Math.cos(Math.toRadians(lat1))
                                * Math.cos(Math.toRadians(lat2))
                                * Math.sin(dLng / 2d)
                                * Math.sin(dLng / 2d);
        return earthMeters * 2d * Math.atan2(Math.sqrt(a), Math.sqrt(1d - a));
    }
}
