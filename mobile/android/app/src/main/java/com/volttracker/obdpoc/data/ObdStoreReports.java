package com.volttracker.obdpoc.data;

import static com.volttracker.obdpoc.data.ObdStoreSupport.USEFUL_TELEMETRY_WHERE;
import static com.volttracker.obdpoc.data.ObdStoreSupport.averageSampleIntervalMs;
import static com.volttracker.obdpoc.data.ObdStoreSupport.boundedLimit;
import static com.volttracker.obdpoc.data.ObdStoreSupport.clean;
import static com.volttracker.obdpoc.data.ObdStoreSupport.countRows;
import static com.volttracker.obdpoc.data.ObdStoreSupport.countRowsWhere;
import static com.volttracker.obdpoc.data.ObdStoreSupport.firstOrNull;
import static com.volttracker.obdpoc.data.ObdStoreSupport.getRecentSessions;
import static com.volttracker.obdpoc.data.ObdStoreSupport.maxDouble;
import static com.volttracker.obdpoc.data.ObdStoreSupport.maxInt;
import static com.volttracker.obdpoc.data.ObdStoreSupport.nullableDoubleBoxed;
import static com.volttracker.obdpoc.data.ObdStoreSupport.nullableIntBoxed;
import static com.volttracker.obdpoc.data.ObdStoreSupport.nullableLongBoxed;
import static com.volttracker.obdpoc.data.ObdStoreSupport.parseObject;
import static com.volttracker.obdpoc.data.ObdStoreSupport.readAdapterHistory;
import static com.volttracker.obdpoc.data.ObdStoreSupport.readSession;
import static com.volttracker.obdpoc.data.ObdStoreSupport.readStatusEvent;
import static com.volttracker.obdpoc.data.ObdStoreSupport.readTelemetry;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Read-side projections: typed-record reads and the storage-summary / session / adapter / overview
 * / charge / battery JSON the dashboard consumes. Trip, route and per-session review projections
 * live in {@link ObdStoreTrips}, which this class composes for the storage summary. Split out of
 * {@link ObdLocalStore} to keep each file under 500 lines.
 */
final class ObdStoreReports {

    private final VoltTrackerDb helper;
    private final ObdStoreTrips trips;

    ObdStoreReports(VoltTrackerDb helper, ObdStoreTrips trips) {
        this.helper = helper;
        this.trips = trips;
    }

    // ---- typed-record reads --------------------------------------------------------

    ObdSessionRecord getSession(long sessionId) {
        try (Cursor cursor =
                helper.getReadableDatabase()
                        .query(
                                VoltTrackerDb.TABLE_SESSIONS,
                                null,
                                "_id = ?",
                                new String[] {String.valueOf(sessionId)},
                                null,
                                null,
                                null)) {
            return cursor.moveToFirst() ? readSession(cursor) : null;
        }
    }

    /**
     * Full route projection for a single session, loaded on demand. Unlike the storage summary's
     * {@code recentRoutes} (capped at the most recent few sessions for payload size), this serves
     * any session by id so the Trips screen can preview the route of an older logged drive — e.g.
     * one folded in from a merged backup. Returns {@code {}} when the session is unknown.
     */
    JSONObject tripRouteJson(long sessionId) {
        ObdSessionRecord session = getSession(sessionId);
        if (session == null) {
            return new JSONObject();
        }
        try {
            return ObdStoreRouteProjection.routeForSession(
                    helper.getReadableDatabase(),
                    session,
                    ObdStoreRouteProjection.MAX_TRACK_POINTS);
        } catch (JSONException ex) {
            return new JSONObject();
        }
    }

    JSONObject tripRouteJson(String routeKey) {
        DriveWindowDetector.RouteKey parsed = DriveWindowDetector.parseRouteKey(routeKey);
        if (parsed == null) {
            return new JSONObject();
        }
        ObdSessionRecord session = getSession(parsed.sessionId);
        if (session == null) {
            return new JSONObject();
        }
        try {
            return ObdStoreRouteProjection.routeForSession(
                    helper.getReadableDatabase(),
                    session,
                    ObdStoreRouteProjection.MAX_TRACK_POINTS,
                    parsed.startedAtMs,
                    parsed.endedAtMs,
                    routeKey);
        } catch (JSONException ex) {
            return new JSONObject();
        }
    }

    List<ObdSessionRecord> getRecentSessions(int limit) {
        List<ObdSessionRecord> records = new ArrayList<>();
        try (Cursor cursor =
                helper.getReadableDatabase()
                        .query(
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

    List<TelemetrySampleRecord> getRecentTelemetry(long sessionId, int limit) {
        List<TelemetrySampleRecord> records = new ArrayList<>();
        try (Cursor cursor =
                helper.getReadableDatabase()
                        .query(
                                VoltTrackerDb.TABLE_TELEMETRY,
                                null,
                                "session_id = ? AND " + USEFUL_TELEMETRY_WHERE,
                                new String[] {String.valueOf(sessionId)},
                                null,
                                null,
                                "captured_at_ms DESC",
                                boundedLimit(limit))) {
            while (cursor.moveToNext()) {
                records.add(readTelemetry(cursor));
            }
        }
        return records;
    }

    List<StatusEventRecord> getRecentEvents(long sessionId, int limit) {
        List<StatusEventRecord> records = new ArrayList<>();
        try (Cursor cursor =
                helper.getReadableDatabase()
                        .query(
                                VoltTrackerDb.TABLE_EVENTS,
                                null,
                                "session_id = ?",
                                new String[] {String.valueOf(sessionId)},
                                null,
                                null,
                                "occurred_at_ms DESC",
                                boundedLimit(limit))) {
            while (cursor.moveToNext()) {
                records.add(readStatusEvent(cursor));
            }
        }
        return records;
    }

    List<AdapterHistoryRecord> getAdapterHistory(int limit) {
        List<AdapterHistoryRecord> records = new ArrayList<>();
        try (Cursor cursor =
                helper.getReadableDatabase()
                        .query(
                                VoltTrackerDb.TABLE_ADAPTER_HISTORY,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "last_seen_ms DESC",
                                boundedLimit(limit))) {
            while (cursor.moveToNext()) {
                records.add(readAdapterHistory(cursor));
            }
        }
        return records;
    }

    JSONArray enhancedCapabilitiesJson(int limit) {
        JSONArray items = new JSONArray();
        try (Cursor cursor =
                helper.getReadableDatabase()
                        .query(
                                VoltTrackerDb.TABLE_FIELD_CAPABILITIES,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "last_seen_ms DESC",
                                boundedLimit(limit))) {
            while (cursor.moveToNext()) {
                try {
                    JSONObject item = capabilityJsonFromCursor(cursor);
                    items.put(item);
                } catch (JSONException ignored) {
                    // Skip malformed rows instead of breaking the dashboard/debug payload.
                }
            }
        }
        return items;
    }

    JSONObject enhancedCapabilityJson(long id) {
        try (Cursor cursor =
                helper.getReadableDatabase()
                        .query(
                                VoltTrackerDb.TABLE_FIELD_CAPABILITIES,
                                null,
                                "_id = ?",
                                new String[] {String.valueOf(id)},
                                null,
                                null,
                                null,
                                "1")) {
            if (!cursor.moveToFirst()) {
                return notFound("detailed signal log not found");
            }
            try {
                JSONObject payload = new JSONObject();
                payload.put("ok", true);
                payload.put("kind", "detailed-signal-log");
                payload.put("item", capabilityJsonFromCursor(cursor));
                return payload;
            } catch (JSONException ex) {
                return error("detailed signal log could not be exported");
            }
        }
    }

    JSONObject enhancedCapabilitiesExportJson(int limit) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("ok", true);
            payload.put("kind", "detailed-signal-logs");
            payload.put("exportedAtMs", System.currentTimeMillis());
            payload.put("items", enhancedCapabilitiesJson(limit));
        } catch (JSONException ignored) {
            // Local keys are safe.
        }
        return payload;
    }

    boolean hasRecentEnhancedCapability(
            String adapterAddress, String header, String command, long minAgeMs) {
        String adapterKey = ObdStoreSupport.adapterKey(adapterAddress, ObdLocalStore.MODE_OBD);
        long cutoff = System.currentTimeMillis() - Math.max(0L, minAgeMs);
        try (Cursor cursor =
                helper.getReadableDatabase()
                        .query(
                                VoltTrackerDb.TABLE_FIELD_CAPABILITIES,
                                new String[] {"last_seen_ms"},
                                "adapter_key = ? AND header = ? AND command = ? AND last_seen_ms >= ?",
                                new String[] {
                                    adapterKey,
                                    clean(header),
                                    clean(command),
                                    String.valueOf(cutoff)
                                },
                                null,
                                null,
                                "last_seen_ms DESC",
                                "1")) {
            return cursor.moveToFirst();
        }
    }

    int deleteEnhancedCapability(long id) {
        return helper.getWritableDatabase()
                .delete(
                        VoltTrackerDb.TABLE_FIELD_CAPABILITIES,
                        "_id = ?",
                        new String[] {String.valueOf(id)});
    }

    boolean hasRejectedEnhancedCapability(String adapterAddress, String header, String command) {
        String adapterKey = ObdStoreSupport.adapterKey(adapterAddress, ObdLocalStore.MODE_OBD);
        try (Cursor cursor =
                helper.getReadableDatabase()
                        .query(
                                VoltTrackerDb.TABLE_FIELD_CAPABILITIES,
                                new String[] {"supported", "response_count"},
                                "adapter_key = ? AND header = ? AND command = ?",
                                new String[] {adapterKey, clean(header), clean(command)},
                                null,
                                null,
                                "last_seen_ms DESC",
                                "1")) {
            if (!cursor.moveToFirst()) {
                return false;
            }
            boolean supported = cursor.getInt(cursor.getColumnIndexOrThrow("supported")) == 1;
            long responseCount = cursor.getLong(cursor.getColumnIndexOrThrow("response_count"));
            return !supported && responseCount <= 0L;
        }
    }

    private static JSONObject capabilityJsonFromCursor(Cursor cursor) throws JSONException {
        JSONObject item = new JSONObject();
        item.put("id", cursor.getLong(cursor.getColumnIndexOrThrow("_id")));
        item.put(
                "adapterKey", clean(cursor.getString(cursor.getColumnIndexOrThrow("adapter_key"))));
        item.put("protocol", clean(cursor.getString(cursor.getColumnIndexOrThrow("protocol"))));
        item.put("header", clean(cursor.getString(cursor.getColumnIndexOrThrow("header"))));
        item.put("command", clean(cursor.getString(cursor.getColumnIndexOrThrow("command"))));
        item.put("pid", clean(cursor.getString(cursor.getColumnIndexOrThrow("pid"))));
        item.put("name", clean(cursor.getString(cursor.getColumnIndexOrThrow("name"))));
        item.put("unit", clean(cursor.getString(cursor.getColumnIndexOrThrow("unit"))));
        item.put("supported", cursor.getInt(cursor.getColumnIndexOrThrow("supported")) == 1);
        item.put("responseCount", cursor.getLong(cursor.getColumnIndexOrThrow("response_count")));
        item.put("firstSeenMs", cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_ms")));
        item.put("lastSeenMs", cursor.getLong(cursor.getColumnIndexOrThrow("last_seen_ms")));
        item.put(
                "sample",
                parseObject(cursor.getString(cursor.getColumnIndexOrThrow("sample_json"))));
        return item;
    }

    private static JSONObject notFound(String message) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("ok", false);
            payload.put("error", "not_found");
            payload.put("message", message);
        } catch (JSONException ignored) {
            // Local strings are safe.
        }
        return payload;
    }

    private static JSONObject error(String message) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("ok", false);
            payload.put("error", "export_failed");
            payload.put("message", message);
        } catch (JSONException ignored) {
            // Local strings are safe.
        }
        return payload;
    }

    // ---- storage summary -----------------------------------------------------------

    StorageSummaryRecord storageSummaryRecord(File databaseFile) {
        SQLiteDatabase db = helper.getReadableDatabase();
        long rawTelemetryCount = countRows(db, VoltTrackerDb.TABLE_TELEMETRY);
        long usefulTelemetryCount =
                countRowsWhere(db, VoltTrackerDb.TABLE_TELEMETRY, USEFUL_TELEMETRY_WHERE, null);
        ObdSessionRecord latest = firstOrNull(getRecentSessions(1));
        ObdSessionRecord reviewSession = ObdStoreSessionReview.latestReviewableSession(db);
        return new StorageSummaryRecord(
                VoltTrackerDb.DATABASE_NAME,
                databaseFile.length(),
                countRows(db, VoltTrackerDb.TABLE_SESSIONS),
                rawTelemetryCount,
                usefulTelemetryCount,
                Math.max(0L, rawTelemetryCount - usefulTelemetryCount),
                countRows(db, VoltTrackerDb.TABLE_EVENTS),
                countRows(db, VoltTrackerDb.TABLE_ADAPTER_HISTORY),
                countRows(db, VoltTrackerDb.TABLE_PID_OBSERVATIONS),
                countRows(db, VoltTrackerDb.TABLE_DIAGNOSTIC_CODES),
                diagnosticCodeStatusCounts(db),
                countRows(db, VoltTrackerDb.TABLE_LOCATION_SAMPLES),
                countRows(db, VoltTrackerDb.TABLE_VEHICLES),
                countRows(db, VoltTrackerDb.TABLE_FIELD_CAPABILITIES),
                countRows(db, VoltTrackerDb.TABLE_TRIP_SEGMENTS),
                countRows(db, VoltTrackerDb.TABLE_CHARGE_SESSIONS),
                countRows(db, VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS),
                countRows(db, VoltTrackerDb.TABLE_CELL_SNAPSHOTS),
                countRows(db, VoltTrackerDb.TABLE_EXPORTS),
                latest,
                recentSessionSummaries(db, 6),
                getAdapterHistory(6),
                latestDiagnosticCodeReports(db, 12),
                reviewSession == null
                        ? new JSONObject()
                        : safeJson(() -> ObdStoreSessionReview.sessionReview(db, reviewSession)),
                reviewSession == null
                        ? new JSONObject()
                        : safeJson(
                                () ->
                                        ObdStoreRouteProjection.routeForSession(
                                                db, reviewSession, 240)),
                safeArray(() -> ObdStoreRouteProjection.recentRoutes(db, 8, 500)),
                safeJson(() -> overviewJson(db)),
                safeJson(() -> chargeSummaryJson(db)),
                safeJson(() -> batterySummaryJson(db)),
                safeJson(() -> latestVehicleJson(db)),
                safeArray(() -> enhancedCapabilitiesJson(24)));
    }

    private interface JsonSupplier {
        JSONObject get() throws JSONException;
    }

    private interface ArraySupplier {
        JSONArray get() throws JSONException;
    }

    private static JSONObject safeJson(JsonSupplier supplier) {
        try {
            JSONObject value = supplier.get();
            return value == null ? new JSONObject() : value;
        } catch (JSONException ex) {
            return new JSONObject();
        }
    }

    private static JSONArray safeArray(ArraySupplier supplier) {
        try {
            JSONArray value = supplier.get();
            return value == null ? new JSONArray() : value;
        } catch (JSONException ex) {
            return new JSONArray();
        }
    }

    private StorageSummaryRecord emptyStorageSummary(File databaseFile) {
        return new StorageSummaryRecord(
                VoltTrackerDb.DATABASE_NAME,
                databaseFile.length(),
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                Map.of(),
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                null,
                List.of(),
                List.of(),
                List.of(),
                new JSONObject(),
                new JSONObject(),
                new JSONArray(),
                new JSONObject(),
                new JSONObject(),
                new JSONObject(),
                new JSONObject(),
                new JSONArray());
    }

    private List<RecentSessionSummaryRecord> recentSessionSummaries(SQLiteDatabase db, int limit) {
        List<RecentSessionSummaryRecord> records = new ArrayList<>();
        for (ObdSessionRecord session : getRecentSessions(limit)) {
            long usefulSamples =
                    countRowsWhere(
                            db,
                            VoltTrackerDb.TABLE_TELEMETRY,
                            "session_id = ? AND " + USEFUL_TELEMETRY_WHERE,
                            new String[] {String.valueOf(session.id)});
            records.add(
                    new RecentSessionSummaryRecord(
                            session,
                            usefulSamples,
                            Math.max(0L, session.sampleCount - usefulSamples)));
        }
        return records;
    }

    /**
     * Most-recently-seen vehicle row exposed to the dashboard so the "Vehicle identity" panel can
     * fill in once a Scan has populated {@code vehicles}. Returns an empty object when no row
     * exists yet — the panel JS treats absent fields as "--". We deliberately surface the redacted
     * VIN suffix (last 4 chars) only; the SHA-256 hash stays inside the DB.
     */
    private JSONObject latestVehicleJson(SQLiteDatabase db) {
        JSONObject result = new JSONObject();
        try (android.database.Cursor cursor =
                db.query(
                        VoltTrackerDb.TABLE_VEHICLES,
                        new String[] {
                            "_id",
                            "display_name",
                            "make",
                            "model",
                            "model_year",
                            "vin_redacted",
                            "first_seen_ms",
                            "last_seen_ms"
                        },
                        null,
                        null,
                        null,
                        null,
                        "last_seen_ms DESC",
                        "1")) {
            if (!cursor.moveToFirst()) {
                return result;
            }
            try {
                String displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name"));
                String make = cursor.getString(cursor.getColumnIndexOrThrow("make"));
                String model = cursor.getString(cursor.getColumnIndexOrThrow("model"));
                String vinRedacted = cursor.getString(cursor.getColumnIndexOrThrow("vin_redacted"));
                int yearIndex = cursor.getColumnIndexOrThrow("model_year");
                if (displayName != null) {
                    result.put("name", displayName);
                }
                if (make != null) {
                    result.put("make", make);
                }
                if (model != null) {
                    result.put("model", model);
                }
                if (vinRedacted != null && !vinRedacted.isEmpty()) {
                    // "VIN …XXXX" — enough to recognise the car without exposing the full ID.
                    result.put("vin", "…" + vinRedacted);
                }
                if (!cursor.isNull(yearIndex)) {
                    result.put("year", cursor.getInt(yearIndex));
                    result.put("modelYear", cursor.getInt(yearIndex));
                }
                result.put("vehicleId", cursor.getLong(cursor.getColumnIndexOrThrow("_id")));
                result.put(
                        "firstSeenMs",
                        cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_ms")));
                result.put(
                        "lastSeenMs", cursor.getLong(cursor.getColumnIndexOrThrow("last_seen_ms")));
            } catch (JSONException ignored) {
                // Local values are safe.
            }
        }
        return result;
    }

    JSONArray recentSessionsJson(int limit) {
        JSONArray payload = new JSONArray();
        SQLiteDatabase db = helper.getReadableDatabase();
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
                long usefulSamples =
                        countRowsWhere(
                                db,
                                VoltTrackerDb.TABLE_TELEMETRY,
                                "session_id = ? AND " + USEFUL_TELEMETRY_WHERE,
                                new String[] {String.valueOf(record.id)});
                item.put("usefulSampleCount", usefulSamples);
                item.put("emptySampleCount", Math.max(0L, record.sampleCount - usefulSamples));
                item.put("lastEventAtMs", record.lastEventAtMs);
            } catch (JSONException ignored) {
                // Local fields are safe.
            }
            payload.put(item);
        }
        return payload;
    }

    JSONArray adapterHistoryJson(int limit) {
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

    private static JSONArray latestDiagnosticCodesJson(SQLiteDatabase db, int limit) {
        return diagnosticCodeReportsJson(latestDiagnosticCodeReports(db, limit));
    }

    private static List<DiagnosticCodeReport> latestDiagnosticCodeReports(
            SQLiteDatabase db, int limit) {
        List<DiagnosticCodeReport> reports = new ArrayList<>();
        try (Cursor cursor =
                db.query(
                        VoltTrackerDb.TABLE_DIAGNOSTIC_CODES,
                        DiagnosticCodeReport.QUERY_COLUMNS,
                        null,
                        null,
                        null,
                        null,
                        "last_seen_ms DESC",
                        boundedLimit(limit))) {
            while (cursor.moveToNext()) {
                reports.add(DiagnosticCodeReport.fromCursor(cursor));
            }
        }
        return reports;
    }

    private static JSONArray diagnosticCodeReportsJson(List<DiagnosticCodeReport> reports) {
        JSONArray payload = new JSONArray();
        for (DiagnosticCodeReport report : reports) {
            payload.put(report.toJson());
        }
        return payload;
    }

    private static Map<String, Long> diagnosticCodeStatusCounts(SQLiteDatabase db) {
        Map<String, Long> payload = new LinkedHashMap<>();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT status, COUNT(*) AS count FROM "
                                + VoltTrackerDb.TABLE_DIAGNOSTIC_CODES
                                + " GROUP BY status",
                        null)) {
            while (cursor.moveToNext()) {
                String key = clean(cursor.getString(cursor.getColumnIndexOrThrow("status")));
                if (key.isEmpty()) {
                    key = "stored";
                }
                payload.put(key, cursor.getLong(cursor.getColumnIndexOrThrow("count")));
            }
        }
        return payload;
    }

    // ---- overview / charge / battery -----------------------------------------------

    private JSONObject overviewJson(SQLiteDatabase db) throws JSONException {
        JSONObject payload = new JSONObject();
        payload.put("distanceMeters", trips.totalDistanceMeters());
        payload.put("maxSpeedKph", maxInt(db, VoltTrackerDb.TABLE_TELEMETRY, "speed_kph"));
        payload.put("avgSampleIntervalMs", averageSampleIntervalMs(db));
        payload.put(
                "drivingSamples",
                countRowsWhere(
                        db,
                        VoltTrackerDb.TABLE_TELEMETRY,
                        "vehicle_state LIKE ?",
                        new String[] {"%driving%"}));
        payload.put(
                "chargingHints",
                countRowsWhere(
                        db, VoltTrackerDb.TABLE_TELEMETRY, "charge_transition_hint = 1", null));
        payload.put("latestTelemetry", latestTelemetryJson(db));
        return payload;
    }

    private static JSONObject chargeSummaryJson(SQLiteDatabase db) throws JSONException {
        JSONObject payload = new JSONObject();
        payload.put("chargeSessionCount", countRows(db, VoltTrackerDb.TABLE_CHARGE_SESSIONS));
        payload.put(
                "chargingHintCount",
                countRowsWhere(
                        db, VoltTrackerDb.TABLE_TELEMETRY, "charge_transition_hint = 1", null));
        payload.put("maxPowerKw", maxDouble(db, VoltTrackerDb.TABLE_TELEMETRY, "power_kw"));
        payload.put("latest", latestChargeSessionJson(db));
        return payload;
    }

    private static JSONObject batterySummaryJson(SQLiteDatabase db) throws JSONException {
        JSONObject payload = new JSONObject();
        payload.put("snapshotCount", countRows(db, VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS));
        payload.put("cellSnapshotCount", countRows(db, VoltTrackerDb.TABLE_CELL_SNAPSHOTS));
        payload.put("latestTelemetry", latestTelemetryJson(db));
        payload.put("latestBatterySnapshot", latestBatterySnapshotJson(db));
        return payload;
    }

    static JSONObject latestTelemetryJson(SQLiteDatabase db) throws JSONException {
        try (Cursor cursor =
                db.query(
                        VoltTrackerDb.TABLE_TELEMETRY,
                        new String[] {
                            "captured_at_ms",
                            "vehicle_state",
                            "speed_kph",
                            "rpm",
                            "voltage",
                            "soc",
                            "battery_temp",
                            "power_kw",
                            "pack_voltage",
                            "pack_current_a",
                            "json"
                        },
                        USEFUL_TELEMETRY_WHERE,
                        null,
                        null,
                        null,
                        "captured_at_ms DESC",
                        "1")) {
            if (!cursor.moveToFirst()) {
                return new JSONObject();
            }
            JSONObject item = parseObject(cursor.getString(cursor.getColumnIndexOrThrow("json")));
            item.put(
                    "capturedAtMs", cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")));
            item.put(
                    "vehicleState",
                    clean(cursor.getString(cursor.getColumnIndexOrThrow("vehicle_state"))));
            // Boxed variants so SQL NULL projects as JSON null instead of 0/0.0 — the dashboard
            // renders the latter as "0 km/h" / "0.0 V" etc., conflating "no data" with "real
            // zero reading".
            item.put("speedKph", boxedOrNull(nullableIntBoxed(cursor, "speed_kph")));
            item.put("rpm", boxedOrNull(nullableIntBoxed(cursor, "rpm")));
            item.put("voltage", boxedOrNull(nullableDoubleBoxed(cursor, "voltage")));
            item.put("soc", boxedOrNull(nullableDoubleBoxed(cursor, "soc")));
            item.put("batteryTemp", boxedOrNull(nullableDoubleBoxed(cursor, "battery_temp")));
            item.put("powerKw", boxedOrNull(nullableDoubleBoxed(cursor, "power_kw")));
            item.put("packVoltage", boxedOrNull(nullableDoubleBoxed(cursor, "pack_voltage")));
            item.put("packCurrentA", boxedOrNull(nullableDoubleBoxed(cursor, "pack_current_a")));
            return item;
        }
    }

    /**
     * {@link JSONObject#put} stores boxed numbers natively; this helper just maps null → JSON null.
     */
    private static Object boxedOrNull(Number value) {
        return value == null ? JSONObject.NULL : value;
    }

    private static JSONObject latestChargeSessionJson(SQLiteDatabase db) throws JSONException {
        try (Cursor cursor =
                db.query(
                        VoltTrackerDb.TABLE_CHARGE_SESSIONS,
                        new String[] {
                            "_id",
                            "started_at_ms",
                            "ended_at_ms",
                            "charger_type",
                            "start_soc",
                            "end_soc",
                            "power_kw",
                            "energy_kwh",
                            "confidence"
                        },
                        null,
                        null,
                        null,
                        null,
                        "started_at_ms DESC",
                        "1")) {
            if (!cursor.moveToFirst()) {
                return new JSONObject();
            }
            JSONObject item = new JSONObject();
            item.put("id", cursor.getLong(cursor.getColumnIndexOrThrow("_id")));
            item.put("startedAtMs", cursor.getLong(cursor.getColumnIndexOrThrow("started_at_ms")));
            item.put("endedAtMs", boxedOrNull(nullableLongBoxed(cursor, "ended_at_ms")));
            String chargerType = cursor.getString(cursor.getColumnIndexOrThrow("charger_type"));
            item.put("chargerType", chargerType == null ? JSONObject.NULL : clean(chargerType));
            item.put("startSoc", boxedOrNull(nullableDoubleBoxed(cursor, "start_soc")));
            item.put("endSoc", boxedOrNull(nullableDoubleBoxed(cursor, "end_soc")));
            item.put("powerKw", boxedOrNull(nullableDoubleBoxed(cursor, "power_kw")));
            item.put("energyKwh", boxedOrNull(nullableDoubleBoxed(cursor, "energy_kwh")));
            item.put("confidence", boxedOrNull(nullableDoubleBoxed(cursor, "confidence")));
            return item;
        }
    }

    private static JSONObject latestBatterySnapshotJson(SQLiteDatabase db) throws JSONException {
        try (Cursor cursor =
                db.query(
                        VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS,
                        new String[] {
                            "_id",
                            "captured_at_ms",
                            "soc",
                            "capacity_ah",
                            "soh_pct",
                            "pack_voltage",
                            "pack_current_a",
                            "pack_power_kw",
                            "battery_temp_c"
                        },
                        null,
                        null,
                        null,
                        null,
                        "captured_at_ms DESC",
                        "1")) {
            if (!cursor.moveToFirst()) {
                return new JSONObject();
            }
            JSONObject item = new JSONObject();
            item.put("id", cursor.getLong(cursor.getColumnIndexOrThrow("_id")));
            item.put(
                    "capturedAtMs", cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")));
            item.put("soc", boxedOrNull(nullableDoubleBoxed(cursor, "soc")));
            item.put("capacityAh", boxedOrNull(nullableDoubleBoxed(cursor, "capacity_ah")));
            item.put("sohPct", boxedOrNull(nullableDoubleBoxed(cursor, "soh_pct")));
            item.put("packVoltage", boxedOrNull(nullableDoubleBoxed(cursor, "pack_voltage")));
            item.put("packCurrentA", boxedOrNull(nullableDoubleBoxed(cursor, "pack_current_a")));
            item.put("packPowerKw", boxedOrNull(nullableDoubleBoxed(cursor, "pack_power_kw")));
            item.put("batteryTempC", boxedOrNull(nullableDoubleBoxed(cursor, "battery_temp_c")));
            return item;
        }
    }
}
