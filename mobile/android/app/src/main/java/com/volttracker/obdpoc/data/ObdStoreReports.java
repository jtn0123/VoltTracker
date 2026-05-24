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
import static com.volttracker.obdpoc.data.ObdStoreSupport.nullableDouble;
import static com.volttracker.obdpoc.data.ObdStoreSupport.nullableInt;
import static com.volttracker.obdpoc.data.ObdStoreSupport.nullableLong;
import static com.volttracker.obdpoc.data.ObdStoreSupport.parseObject;
import static com.volttracker.obdpoc.data.ObdStoreSupport.readAdapterHistory;
import static com.volttracker.obdpoc.data.ObdStoreSupport.readSession;
import static com.volttracker.obdpoc.data.ObdStoreSupport.readStatusEvent;
import static com.volttracker.obdpoc.data.ObdStoreSupport.readTelemetry;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
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

    // ---- storage summary -----------------------------------------------------------

    JSONObject storageSummary(File databaseFile) {
        JSONObject payload = new JSONObject();
        SQLiteDatabase db = helper.getReadableDatabase();
        try {
            payload.put("database", VoltTrackerDb.DATABASE_NAME);
            payload.put("databaseBytes", databaseFile.length());
            payload.put("sessionCount", countRows(db, VoltTrackerDb.TABLE_SESSIONS));
            long rawTelemetryCount = countRows(db, VoltTrackerDb.TABLE_TELEMETRY);
            long usefulTelemetryCount =
                    countRowsWhere(db, VoltTrackerDb.TABLE_TELEMETRY, USEFUL_TELEMETRY_WHERE, null);
            payload.put("rawTelemetryCount", rawTelemetryCount);
            payload.put("sampleCount", usefulTelemetryCount);
            payload.put(
                    "emptyTelemetryCount", Math.max(0L, rawTelemetryCount - usefulTelemetryCount));
            payload.put("eventCount", countRows(db, VoltTrackerDb.TABLE_EVENTS));
            payload.put("adapterCount", countRows(db, VoltTrackerDb.TABLE_ADAPTER_HISTORY));
            payload.put("pidObservationCount", countRows(db, VoltTrackerDb.TABLE_PID_OBSERVATIONS));
            payload.put("diagnosticCodeCount", countRows(db, VoltTrackerDb.TABLE_DIAGNOSTIC_CODES));
            payload.put("diagnosticCodeStatusCounts", diagnosticCodeStatusCountsJson(db));
            payload.put("locationSampleCount", countRows(db, VoltTrackerDb.TABLE_LOCATION_SAMPLES));
            payload.put("vehicleCount", countRows(db, VoltTrackerDb.TABLE_VEHICLES));
            payload.put(
                    "fieldCapabilityCount", countRows(db, VoltTrackerDb.TABLE_FIELD_CAPABILITIES));
            payload.put("tripSegmentCount", countRows(db, VoltTrackerDb.TABLE_TRIP_SEGMENTS));
            payload.put("chargeSessionCount", countRows(db, VoltTrackerDb.TABLE_CHARGE_SESSIONS));
            payload.put(
                    "batterySnapshotCount", countRows(db, VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS));
            payload.put("cellSnapshotCount", countRows(db, VoltTrackerDb.TABLE_CELL_SNAPSHOTS));
            payload.put("exportCount", countRows(db, VoltTrackerDb.TABLE_EXPORTS));
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
            payload.put("recentSessions", recentSessionsJson(6));
            payload.put("adapters", adapterHistoryJson(6));
            payload.put("latestDiagnosticCodes", latestDiagnosticCodesJson(db, 12));
            ObdSessionRecord reviewSession = trips.latestReviewableSession(db);
            payload.put(
                    "latestReview",
                    reviewSession == null
                            ? new JSONObject()
                            : trips.sessionReview(db, reviewSession));
            payload.put(
                    "latestRoute",
                    reviewSession == null
                            ? new JSONObject()
                            : trips.routeForSession(db, reviewSession, 240));
            payload.put("recentRoutes", trips.recentRoutes(db, 8, 500));
            payload.put("overview", overviewJson(db));
            payload.put("chargeSummary", chargeSummaryJson(db));
            payload.put("batterySummary", batterySummaryJson(db));
            payload.put("latestVehicle", latestVehicleJson(db));
        } catch (JSONException ignored) {
            // Local numeric/string values are safe.
        }
        return payload;
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
        JSONArray payload = new JSONArray();
        try (Cursor cursor =
                db.query(
                        VoltTrackerDb.TABLE_DIAGNOSTIC_CODES,
                        new String[] {
                            "_id",
                            "dtc",
                            "status",
                            "status_label",
                            "module_key",
                            "module_name",
                            "header",
                            "first_seen_ms",
                            "last_seen_ms",
                            "seen_count",
                            "last_session_id",
                            "raw_response"
                        },
                        null,
                        null,
                        null,
                        null,
                        "last_seen_ms DESC",
                        boundedLimit(limit))) {
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                try {
                    item.put("id", cursor.getLong(cursor.getColumnIndexOrThrow("_id")));
                    item.put("dtc", clean(cursor.getString(cursor.getColumnIndexOrThrow("dtc"))));
                    item.put(
                            "status",
                            clean(cursor.getString(cursor.getColumnIndexOrThrow("status"))));
                    item.put(
                            "statusLabel",
                            clean(cursor.getString(cursor.getColumnIndexOrThrow("status_label"))));
                    item.put(
                            "moduleKey",
                            clean(cursor.getString(cursor.getColumnIndexOrThrow("module_key"))));
                    item.put(
                            "moduleName",
                            clean(cursor.getString(cursor.getColumnIndexOrThrow("module_name"))));
                    item.put(
                            "header",
                            clean(cursor.getString(cursor.getColumnIndexOrThrow("header"))));
                    item.put(
                            "firstSeenMs",
                            cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_ms")));
                    item.put(
                            "lastSeenMs",
                            cursor.getLong(cursor.getColumnIndexOrThrow("last_seen_ms")));
                    item.put(
                            "seenCount",
                            cursor.getLong(cursor.getColumnIndexOrThrow("seen_count")));
                    item.put("lastSessionId", nullableLong(cursor, "last_session_id"));
                    item.put(
                            "rawResponse",
                            clean(cursor.getString(cursor.getColumnIndexOrThrow("raw_response"))));
                } catch (JSONException ignored) {
                    // Local fields are safe.
                }
                payload.put(item);
            }
        }
        return payload;
    }

    private static JSONObject diagnosticCodeStatusCountsJson(SQLiteDatabase db) {
        JSONObject payload = new JSONObject();
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
                try {
                    payload.put(key, cursor.getLong(cursor.getColumnIndexOrThrow("count")));
                } catch (JSONException ignored) {
                    // Local fields are safe.
                }
            }
        }
        return payload;
    }

    // ---- overview / charge / battery -----------------------------------------------

    private JSONObject overviewJson(SQLiteDatabase db) throws JSONException {
        JSONObject payload = new JSONObject();
        payload.put("distanceMeters", trips.totalDistanceMeters(db));
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
            item.put("speedKph", nullableInt(cursor, "speed_kph"));
            item.put("rpm", nullableInt(cursor, "rpm"));
            item.put("voltage", nullableDouble(cursor, "voltage"));
            item.put("soc", nullableDouble(cursor, "soc"));
            item.put("batteryTemp", nullableDouble(cursor, "battery_temp"));
            item.put("powerKw", nullableDouble(cursor, "power_kw"));
            return item;
        }
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
            item.put("endedAtMs", nullableLong(cursor, "ended_at_ms"));
            item.put(
                    "chargerType",
                    clean(cursor.getString(cursor.getColumnIndexOrThrow("charger_type"))));
            item.put("startSoc", nullableDouble(cursor, "start_soc"));
            item.put("endSoc", nullableDouble(cursor, "end_soc"));
            item.put("powerKw", nullableDouble(cursor, "power_kw"));
            item.put("energyKwh", nullableDouble(cursor, "energy_kwh"));
            item.put("confidence", nullableDouble(cursor, "confidence"));
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
            item.put("soc", nullableDouble(cursor, "soc"));
            item.put("capacityAh", nullableDouble(cursor, "capacity_ah"));
            item.put("sohPct", nullableDouble(cursor, "soh_pct"));
            item.put("packVoltage", nullableDouble(cursor, "pack_voltage"));
            item.put("packCurrentA", nullableDouble(cursor, "pack_current_a"));
            item.put("packPowerKw", nullableDouble(cursor, "pack_power_kw"));
            item.put("batteryTempC", nullableDouble(cursor, "battery_temp_c"));
            return item;
        }
    }
}
