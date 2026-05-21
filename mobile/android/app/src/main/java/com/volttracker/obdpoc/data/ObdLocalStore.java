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

    private static final String USEFUL_TELEMETRY_WHERE = "("
            + "COALESCE(source, '') != ''"
            + " OR speed_kph IS NOT NULL"
            + " OR rpm IS NOT NULL"
            + " OR voltage IS NOT NULL"
            + " OR TRIM(COALESCE(raw, '')) != ''"
            + ")";

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
                "session_id = ? AND " + USEFUL_TELEMETRY_WHERE,
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
            long rawTelemetryCount = countRows(db, VoltTrackerDb.TABLE_TELEMETRY);
            long usefulTelemetryCount = countRowsWhere(db, VoltTrackerDb.TABLE_TELEMETRY, USEFUL_TELEMETRY_WHERE, null);
            payload.put("rawTelemetryCount", rawTelemetryCount);
            payload.put("sampleCount", usefulTelemetryCount);
            payload.put("emptyTelemetryCount", Math.max(0L, rawTelemetryCount - usefulTelemetryCount));
            payload.put("eventCount", countRows(db, VoltTrackerDb.TABLE_EVENTS));
            payload.put("adapterCount", countRows(db, VoltTrackerDb.TABLE_ADAPTER_HISTORY));
            payload.put("pidObservationCount", countRows(db, VoltTrackerDb.TABLE_PID_OBSERVATIONS));
            payload.put("locationSampleCount", countRows(db, VoltTrackerDb.TABLE_LOCATION_SAMPLES));
            payload.put("vehicleCount", countRows(db, VoltTrackerDb.TABLE_VEHICLES));
            payload.put("fieldCapabilityCount", countRows(db, VoltTrackerDb.TABLE_FIELD_CAPABILITIES));
            payload.put("tripSegmentCount", countRows(db, VoltTrackerDb.TABLE_TRIP_SEGMENTS));
            payload.put("chargeSessionCount", countRows(db, VoltTrackerDb.TABLE_CHARGE_SESSIONS));
            payload.put("batterySnapshotCount", countRows(db, VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS));
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
            payload.put("recentSessions", getRecentSessionsJson(6));
            payload.put("adapters", getAdapterHistoryJson(6));
            ObdSessionRecord reviewSession = latestReviewableSession(db);
            payload.put("latestReview", reviewSession == null ? new JSONObject() : getSessionReviewJson(db, reviewSession));
            payload.put("latestRoute", reviewSession == null ? new JSONObject() : routeForSessionJson(db, reviewSession, 240));
            payload.put("recentRoutes", recentRoutesJson(db, 8, 500));
            payload.put("overview", overviewJson(db));
            payload.put("chargeSummary", chargeSummaryJson(db));
            payload.put("batterySummary", batterySummaryJson(db));
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
                long usefulSamples = countRowsWhere(helper.getReadableDatabase(), VoltTrackerDb.TABLE_TELEMETRY,
                        "session_id = ? AND " + USEFUL_TELEMETRY_WHERE,
                        new String[]{String.valueOf(record.id)});
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

    /**
     * Real trip list, one entry per logged OBD driving session. Distance, duration and
     * speeds are computed on read from telemetry and GPS samples already on disk; no
     * separate trip table is required. Demo and scan sessions are excluded.
     */
    public JSONArray getTripsJson(int limit) {
        JSONArray payload = new JSONArray();
        SQLiteDatabase db = helper.getReadableDatabase();
        try {
            for (ObdSessionRecord session : getRecentSessions(db, Math.max(1, Math.min(limit, 100)))) {
                if (!MODE_OBD.equals(session.mode)) {
                    continue;
                }
                JSONObject trip = tripJson(db, session);
                if (trip != null) {
                    payload.put(trip);
                }
            }
        } catch (JSONException ignored) {
            // Local numeric/string values are safe.
        }
        return payload;
    }

    private static JSONObject tripJson(SQLiteDatabase db, ObdSessionRecord session) throws JSONException {
        long usefulSamples = countRowsWhere(db, VoltTrackerDb.TABLE_TELEMETRY,
                "session_id = ? AND " + USEFUL_TELEMETRY_WHERE,
                new String[]{String.valueOf(session.id)});
        if (usefulSamples <= 0) {
            // A session with no useful telemetry was a failed connection, not a trip.
            return null;
        }
        JSONArray points = routePointsForSessionJson(db, session.id, 1000);
        long endedAtMs = session.endedAtMs > 0 ? session.endedAtMs : session.lastEventAtMs;
        long durationMs = endedAtMs > session.startedAtMs ? endedAtMs - session.startedAtMs : 0L;
        JSONObject trip = new JSONObject();
        trip.put("id", session.id);
        trip.put("startedAtMs", session.startedAtMs);
        trip.put("endedAtMs", endedAtMs);
        trip.put("durationMs", durationMs);
        trip.put("distanceMeters", distanceMeters(points));
        trip.put("maxSpeedKph", maxIntForSession(db, "speed_kph", session.id));
        trip.put("avgMovingSpeedKph", avgMovingSpeedKph(db, session.id));
        trip.put("sampleCount", usefulSamples);
        trip.put("pointCount", points.length());
        trip.put("hasRoute", points.length() >= 2);
        trip.put("adapterName", session.adapterName);
        trip.put("status", session.status);
        return trip;
    }

    private static double avgMovingSpeedKph(SQLiteDatabase db, long sessionId) {
        try (Cursor cursor = db.rawQuery(
                "SELECT AVG(speed_kph) FROM " + VoltTrackerDb.TABLE_TELEMETRY
                        + " WHERE session_id = ? AND speed_kph > 0 AND " + USEFUL_TELEMETRY_WHERE,
                new String[]{String.valueOf(sessionId)}
        )) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getDouble(0) : 0d;
        }
    }

    /** Cross-session lifetime aggregates for the Insights screen, all derived from trips. */
    public JSONObject getInsightsJson() {
        JSONObject payload = new JSONObject();
        SQLiteDatabase db = helper.getReadableDatabase();
        try {
            JSONArray trips = getTripsJson(100);
            double totalDistance = 0d;
            long totalDriveMs = 0L;
            double longestTrip = 0d;
            int maxSpeed = 0;
            int gpsTripCount = 0;
            long firstAt = 0L;
            long lastAt = 0L;
            for (int i = 0; i < trips.length(); i++) {
                JSONObject trip = trips.getJSONObject(i);
                double distance = trip.optDouble("distanceMeters", 0d);
                totalDistance += distance;
                longestTrip = Math.max(longestTrip, distance);
                totalDriveMs += trip.optLong("durationMs", 0L);
                maxSpeed = Math.max(maxSpeed, trip.optInt("maxSpeedKph", 0));
                if (trip.optBoolean("hasRoute", false)) {
                    gpsTripCount += 1;
                }
                long startedAt = trip.optLong("startedAtMs", 0L);
                if (startedAt > 0) {
                    firstAt = firstAt == 0 ? startedAt : Math.min(firstAt, startedAt);
                    lastAt = Math.max(lastAt, startedAt);
                }
            }
            int tripCount = trips.length();
            payload.put("tripCount", tripCount);
            payload.put("totalDistanceMeters", totalDistance);
            payload.put("totalDriveMs", totalDriveMs);
            payload.put("longestTripMeters", longestTrip);
            payload.put("avgTripDistanceMeters", tripCount > 0 ? totalDistance / tripCount : 0d);
            payload.put("maxSpeedKph", maxSpeed);
            payload.put("gpsTripCount", gpsTripCount);
            payload.put("firstTripAtMs", firstAt);
            payload.put("lastTripAtMs", lastAt);
            payload.put("sessionCount", countRows(db, VoltTrackerDb.TABLE_SESSIONS));
            payload.put("sampleCount", countRowsWhere(db, VoltTrackerDb.TABLE_TELEMETRY,
                    USEFUL_TELEMETRY_WHERE, null));
            payload.put("locationSampleCount", countRows(db, VoltTrackerDb.TABLE_LOCATION_SAMPLES));
        } catch (JSONException ignored) {
            // Local numeric/string values are safe.
        }
        return payload;
    }

    private JSONObject getSessionReviewJson(SQLiteDatabase db, ObdSessionRecord session) throws JSONException {
        JSONObject payload = new JSONObject();
        payload.put("session", sessionToJson(session));
        payload.put("pidObservationCount", countRowsWhere(db, VoltTrackerDb.TABLE_PID_OBSERVATIONS,
                "session_id = ?", new String[]{String.valueOf(session.id)}));
        payload.put("locationSampleCount", countRowsWhere(db, VoltTrackerDb.TABLE_LOCATION_SAMPLES,
                "session_id = ?", new String[]{String.valueOf(session.id)}));
        payload.put("eventCount", countRowsWhere(db, VoltTrackerDb.TABLE_EVENTS,
                "session_id = ?", new String[]{String.valueOf(session.id)}));
        payload.put("parsedPidCount", countRowsWhere(db, VoltTrackerDb.TABLE_PID_OBSERVATIONS,
                "session_id = ? AND (value_text IS NOT NULL AND value_text != '')",
                new String[]{String.valueOf(session.id)}));
        payload.put("unknownPidCount", countRowsWhere(db, VoltTrackerDb.TABLE_PID_OBSERVATIONS,
                "session_id = ? AND (value_text IS NULL OR value_text = '')",
                new String[]{String.valueOf(session.id)}));
        long usefulTelemetry = countRowsWhere(db, VoltTrackerDb.TABLE_TELEMETRY,
                "session_id = ? AND " + USEFUL_TELEMETRY_WHERE,
                new String[]{String.valueOf(session.id)});
        payload.put("usefulTelemetryCount", usefulTelemetry);
        payload.put("emptyTelemetryCount", Math.max(0L, session.sampleCount - usefulTelemetry));
        payload.put("maxSpeedKph", maxIntForSession(db, "speed_kph", session.id));
        payload.put("avgSampleIntervalMs", averageSampleIntervalMs(db, session.id));
        payload.put("backgroundSampleCount", countRowsWhere(db, VoltTrackerDb.TABLE_TELEMETRY,
                "session_id = ? AND app_foreground = 0",
                new String[]{String.valueOf(session.id)}));
        payload.put("sampleGapEventCount", countRowsWhere(db, VoltTrackerDb.TABLE_EVENTS,
                "session_id = ? AND detail = ?",
                new String[]{String.valueOf(session.id), "sample_gap"}));
        payload.put("latestHealth", latestHealthJson(db, session.id));
        payload.put("stateCounts", stateCountsJson(db, session.id));
        payload.put("timeline", recentEventsJson(db, session.id, 20));
        payload.put("recentPidFrames", recentPidFramesJson(db, session.id, 20));
        payload.put("speedTrace", recentSpeedTraceJson(db, session.id, 48));
        payload.put("route", routeForSessionJson(db, session, 180));
        payload.put("warnings", sessionWarningsJson(db, session.id));
        return payload;
    }

    private static JSONObject overviewJson(SQLiteDatabase db) throws JSONException {
        JSONObject payload = new JSONObject();
        payload.put("distanceMeters", totalDistanceMeters(db));
        payload.put("maxSpeedKph", maxInt(db, VoltTrackerDb.TABLE_TELEMETRY, "speed_kph"));
        payload.put("avgSampleIntervalMs", averageSampleIntervalMs(db));
        payload.put("drivingSamples", countRowsWhere(db, VoltTrackerDb.TABLE_TELEMETRY,
                "vehicle_state LIKE ?", new String[]{"%driving%"}));
        payload.put("chargingHints", countRowsWhere(db, VoltTrackerDb.TABLE_TELEMETRY,
                "charge_transition_hint = 1", null));
        payload.put("latestTelemetry", latestTelemetryJson(db));
        return payload;
    }

    private static JSONObject chargeSummaryJson(SQLiteDatabase db) throws JSONException {
        JSONObject payload = new JSONObject();
        payload.put("chargeSessionCount", countRows(db, VoltTrackerDb.TABLE_CHARGE_SESSIONS));
        payload.put("chargingHintCount", countRowsWhere(db, VoltTrackerDb.TABLE_TELEMETRY,
                "charge_transition_hint = 1", null));
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

    private static JSONObject sessionToJson(ObdSessionRecord record) throws JSONException {
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

    private static JSONArray recentEventsJson(SQLiteDatabase db, long sessionId, int limit) throws JSONException {
        JSONArray payload = new JSONArray();
        try (Cursor cursor = db.query(
                VoltTrackerDb.TABLE_EVENTS,
                new String[]{"occurred_at_ms", "kind", "state", "detail", "blocked", "payload"},
                "session_id = ?",
                new String[]{String.valueOf(sessionId)},
                null,
                null,
                "occurred_at_ms DESC",
                boundedLimit(limit)
        )) {
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                item.put("atMs", cursor.getLong(cursor.getColumnIndexOrThrow("occurred_at_ms")));
                item.put("kind", clean(cursor.getString(cursor.getColumnIndexOrThrow("kind"))));
                item.put("state", clean(cursor.getString(cursor.getColumnIndexOrThrow("state"))));
                item.put("detail", clean(cursor.getString(cursor.getColumnIndexOrThrow("detail"))));
                item.put("blocked", cursor.getInt(cursor.getColumnIndexOrThrow("blocked")) != 0);
                item.put("payload", parseObject(cursor.getString(cursor.getColumnIndexOrThrow("payload"))));
                payload.put(item);
            }
        }
        return reverse(payload);
    }

    private static JSONArray recentPidFramesJson(SQLiteDatabase db, long sessionId, int limit) throws JSONException {
        JSONArray payload = new JSONArray();
        try (Cursor cursor = db.query(
                VoltTrackerDb.TABLE_PID_OBSERVATIONS,
                new String[]{
                        "observed_at_ms", "command", "header", "pid", "name", "value_text",
                        "value_numeric", "unit", "raw_response", "json"
                },
                "session_id = ?",
                new String[]{String.valueOf(sessionId)},
                null,
                null,
                "observed_at_ms DESC",
                boundedLimit(limit)
        )) {
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                String valueText = clean(cursor.getString(cursor.getColumnIndexOrThrow("value_text")));
                JSONObject rawJson = parseObject(cursor.getString(cursor.getColumnIndexOrThrow("json")));
                item.put("atMs", cursor.getLong(cursor.getColumnIndexOrThrow("observed_at_ms")));
                item.put("command", clean(cursor.getString(cursor.getColumnIndexOrThrow("command"))));
                item.put("header", clean(cursor.getString(cursor.getColumnIndexOrThrow("header"))));
                item.put("pid", clean(cursor.getString(cursor.getColumnIndexOrThrow("pid"))));
                item.put("name", clean(cursor.getString(cursor.getColumnIndexOrThrow("name"))));
                item.put("valueText", valueText);
                item.put("unit", clean(cursor.getString(cursor.getColumnIndexOrThrow("unit"))));
                item.put("rawResponse", clean(cursor.getString(cursor.getColumnIndexOrThrow("raw_response"))));
                item.put("durationMs", rawJson.optLong("durationMs", 0L));
                item.put("gotPrompt", rawJson.optBoolean("gotPrompt", false));
                item.put("parsed", !valueText.isEmpty());
                payload.put(item);
            }
        }
        return payload;
    }

    private static JSONArray recentSpeedTraceJson(SQLiteDatabase db, long sessionId, int limit) throws JSONException {
        JSONArray payload = new JSONArray();
        try (Cursor cursor = db.query(
                VoltTrackerDb.TABLE_TELEMETRY,
                new String[]{"captured_at_ms", "speed_kph", "vehicle_state", "json"},
                "session_id = ? AND " + USEFUL_TELEMETRY_WHERE,
                new String[]{String.valueOf(sessionId)},
                null,
                null,
                "captured_at_ms DESC",
                boundedLimit(limit)
        )) {
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                JSONObject rawJson = parseObject(cursor.getString(cursor.getColumnIndexOrThrow("json")));
                item.put("atMs", cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")));
                item.put("speedKph", nullableInt(cursor, "speed_kph"));
                item.put("state", clean(cursor.getString(cursor.getColumnIndexOrThrow("vehicle_state"))));
                item.put("chargeTransitionHint", rawJson.optBoolean("chargeTransitionHint", false));
                item.put("speedRejectedKph", rawJson.optInt("speedRejectedKph", 0));
                payload.put(item);
            }
        }
        return reverse(payload);
    }

    private static JSONObject routeForSessionJson(SQLiteDatabase db, ObdSessionRecord session, int limit) throws JSONException {
        JSONObject payload = new JSONObject();
        JSONArray points = routePointsForSessionJson(db, session.id, limit);
        payload.put("session", sessionToJson(session));
        payload.put("points", points);
        payload.put("pointCount", points.length());
        payload.put("distanceMeters", distanceMeters(points));
        payload.put("bounds", boundsFor(points));
        return payload;
    }

    private static JSONArray recentRoutesJson(SQLiteDatabase db, int sessionLimit, int pointLimit) throws JSONException {
        JSONArray payload = new JSONArray();
        for (ObdSessionRecord session : getRecentSessions(db, sessionLimit)) {
            JSONArray points = routePointsForSessionJson(db, session.id, pointLimit);
            if (points.length() < 2) {
                continue;
            }
            JSONObject route = new JSONObject();
            route.put("session", sessionToJson(session));
            route.put("points", points);
            route.put("pointCount", points.length());
            route.put("distanceMeters", distanceMeters(points));
            route.put("bounds", boundsFor(points));
            payload.put(route);
        }
        return payload;
    }

    private static JSONArray routePointsForSessionJson(SQLiteDatabase db, long sessionId, int limit) throws JSONException {
        JSONArray points = new JSONArray();
        try (Cursor cursor = db.query(
                VoltTrackerDb.TABLE_LOCATION_SAMPLES,
                new String[]{"captured_at_ms", "latitude", "longitude", "accuracy_m", "speed_mps", "bearing_deg"},
                "session_id = ?",
                new String[]{String.valueOf(sessionId)},
                null,
                null,
                "captured_at_ms DESC",
                boundedLimit(limit)
        )) {
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                item.put("atMs", cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")));
                item.put("lat", cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")));
                item.put("lng", cursor.getDouble(cursor.getColumnIndexOrThrow("longitude")));
                item.put("accuracyM", nullableDouble(cursor, "accuracy_m"));
                item.put("speedMps", nullableDouble(cursor, "speed_mps"));
                item.put("bearingDeg", nullableDouble(cursor, "bearing_deg"));
                points.put(item);
            }
        }
        if (points.length() == 0) {
            try (Cursor cursor = db.query(
                    VoltTrackerDb.TABLE_TELEMETRY,
                    new String[]{"captured_at_ms", "latitude", "longitude", "accuracy_m", "gps_speed_mps", "bearing_deg"},
                    "session_id = ? AND latitude IS NOT NULL AND longitude IS NOT NULL",
                    new String[]{String.valueOf(sessionId)},
                    null,
                    null,
                    "captured_at_ms DESC",
                    boundedLimit(limit)
            )) {
                while (cursor.moveToNext()) {
                    JSONObject item = new JSONObject();
                    item.put("atMs", cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")));
                    item.put("lat", cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")));
                    item.put("lng", cursor.getDouble(cursor.getColumnIndexOrThrow("longitude")));
                    item.put("accuracyM", nullableDouble(cursor, "accuracy_m"));
                    item.put("speedMps", nullableDouble(cursor, "gps_speed_mps"));
                    item.put("bearingDeg", nullableDouble(cursor, "bearing_deg"));
                    points.put(item);
                }
            }
        }
        return reverse(points);
    }

    private static JSONObject latestTelemetryJson(SQLiteDatabase db) throws JSONException {
        try (Cursor cursor = db.query(
                VoltTrackerDb.TABLE_TELEMETRY,
                new String[]{"captured_at_ms", "vehicle_state", "speed_kph", "rpm", "voltage", "soc", "battery_temp", "power_kw", "json"},
                USEFUL_TELEMETRY_WHERE,
                null,
                null,
                null,
                "captured_at_ms DESC",
                "1"
        )) {
            if (!cursor.moveToFirst()) {
                return new JSONObject();
            }
            JSONObject item = parseObject(cursor.getString(cursor.getColumnIndexOrThrow("json")));
            item.put("capturedAtMs", cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")));
            item.put("vehicleState", clean(cursor.getString(cursor.getColumnIndexOrThrow("vehicle_state"))));
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
        try (Cursor cursor = db.query(
                VoltTrackerDb.TABLE_CHARGE_SESSIONS,
                new String[]{"_id", "started_at_ms", "ended_at_ms", "charger_type", "start_soc", "end_soc", "power_kw", "energy_kwh", "confidence"},
                null,
                null,
                null,
                null,
                "started_at_ms DESC",
                "1"
        )) {
            if (!cursor.moveToFirst()) {
                return new JSONObject();
            }
            JSONObject item = new JSONObject();
            item.put("id", cursor.getLong(cursor.getColumnIndexOrThrow("_id")));
            item.put("startedAtMs", cursor.getLong(cursor.getColumnIndexOrThrow("started_at_ms")));
            item.put("endedAtMs", nullableLong(cursor, "ended_at_ms"));
            item.put("chargerType", clean(cursor.getString(cursor.getColumnIndexOrThrow("charger_type"))));
            item.put("startSoc", nullableDouble(cursor, "start_soc"));
            item.put("endSoc", nullableDouble(cursor, "end_soc"));
            item.put("powerKw", nullableDouble(cursor, "power_kw"));
            item.put("energyKwh", nullableDouble(cursor, "energy_kwh"));
            item.put("confidence", nullableDouble(cursor, "confidence"));
            return item;
        }
    }

    private static JSONObject latestBatterySnapshotJson(SQLiteDatabase db) throws JSONException {
        try (Cursor cursor = db.query(
                VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS,
                new String[]{"_id", "captured_at_ms", "soc", "capacity_ah", "soh_pct", "pack_voltage", "pack_current_a", "pack_power_kw", "battery_temp_c"},
                null,
                null,
                null,
                null,
                "captured_at_ms DESC",
                "1"
        )) {
            if (!cursor.moveToFirst()) {
                return new JSONObject();
            }
            JSONObject item = new JSONObject();
            item.put("id", cursor.getLong(cursor.getColumnIndexOrThrow("_id")));
            item.put("capturedAtMs", cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")));
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

    private static JSONObject latestHealthJson(SQLiteDatabase db, long sessionId) throws JSONException {
        try (Cursor cursor = db.query(
                VoltTrackerDb.TABLE_TELEMETRY,
                new String[]{"json"},
                "session_id = ? AND " + USEFUL_TELEMETRY_WHERE,
                new String[]{String.valueOf(sessionId)},
                null,
                null,
                "captured_at_ms DESC",
                "1"
        )) {
            if (!cursor.moveToFirst()) {
                return new JSONObject();
            }
            JSONObject rawJson = parseObject(cursor.getString(cursor.getColumnIndexOrThrow("json")));
            JSONObject payload = new JSONObject();
            payload.put("appForeground", rawJson.optBoolean("appForeground", true));
            payload.put("foregroundServiceActive", rawJson.optBoolean("foregroundServiceActive", false));
            payload.put("backgroundSampleCount", rawJson.optInt("backgroundSampleCount", 0));
            payload.put("sampleGapCount", rawJson.optInt("sampleGapCount", 0));
            payload.put("lastSampleGapMs", rawJson.optLong("lastSampleGapMs", 0L));
            payload.put("maxSampleGapMs", rawJson.optLong("maxSampleGapMs", 0L));
            return payload;
        }
    }

    private static JSONObject stateCountsJson(SQLiteDatabase db, long sessionId) throws JSONException {
        JSONObject payload = new JSONObject();
        try (Cursor cursor = db.rawQuery(
                "SELECT vehicle_state, COUNT(*) FROM " + VoltTrackerDb.TABLE_TELEMETRY
                        + " WHERE session_id = ? AND " + USEFUL_TELEMETRY_WHERE
                        + " GROUP BY vehicle_state",
                new String[]{String.valueOf(sessionId)}
        )) {
            while (cursor.moveToNext()) {
                String state = clean(cursor.getString(0));
                payload.put(state.isEmpty() ? "unknown" : state, cursor.getLong(1));
            }
        }
        return payload;
    }

    private static JSONArray sessionWarningsJson(SQLiteDatabase db, long sessionId) throws JSONException {
        JSONArray payload = new JSONArray();
        long chargeHints = countRowsWhere(db, VoltTrackerDb.TABLE_TELEMETRY,
                "session_id = ? AND charge_transition_hint = 1",
                new String[]{String.valueOf(sessionId)});
        long speedRejected = countRowsWhere(db, VoltTrackerDb.TABLE_EVENTS,
                "session_id = ? AND detail = ?",
                new String[]{String.valueOf(sessionId), "speed_rejected"});
        long gpsSamples = countRowsWhere(db, VoltTrackerDb.TABLE_LOCATION_SAMPLES,
                "session_id = ?", new String[]{String.valueOf(sessionId)});
        long unknownPids = countRowsWhere(db, VoltTrackerDb.TABLE_PID_OBSERVATIONS,
                "session_id = ? AND (value_text IS NULL OR value_text = '')",
                new String[]{String.valueOf(sessionId)});
        long sampleGaps = countRowsWhere(db, VoltTrackerDb.TABLE_EVENTS,
                "session_id = ? AND detail = ?",
                new String[]{String.valueOf(sessionId), "sample_gap"});
        long backgroundEvents = countRowsWhere(db, VoltTrackerDb.TABLE_EVENTS,
                "session_id = ? AND (detail = ? OR detail = ?)",
                new String[]{String.valueOf(sessionId), "app_backgrounded", "app_foregrounded"});
        long emptyTelemetry = countRowsWhere(db, VoltTrackerDb.TABLE_TELEMETRY,
                "session_id = ? AND NOT " + USEFUL_TELEMETRY_WHERE,
                new String[]{String.valueOf(sessionId)});
        long movingWithZeroRpm = countRowsWhere(db, VoltTrackerDb.TABLE_TELEMETRY,
                "session_id = ? AND speed_kph > 0 AND rpm = 0 AND " + USEFUL_TELEMETRY_WHERE,
                new String[]{String.valueOf(sessionId)});
        long powerRows = countRowsWhere(db, VoltTrackerDb.TABLE_TELEMETRY,
                "session_id = ? AND power_kw IS NOT NULL AND " + USEFUL_TELEMETRY_WHERE,
                new String[]{String.valueOf(sessionId)});
        if (chargeHints > 0 || speedRejected > 0) {
            payload.put(warning("charge-speed-hint",
                    "Rejected 255 km/h speed frame seen. Treat it as a charging/transition clue, not vehicle speed.",
                    Math.max(chargeHints, speedRejected)));
        }
        if (gpsSamples == 0) {
            payload.put(warning("gps-missing", "No GPS samples were stored for this session.", 0));
        }
        if (unknownPids > 0) {
            payload.put(warning("pid-unparsed",
                    "Some PID responses are stored but not parsed yet.", unknownPids));
        }
        if (sampleGaps > 0) {
            payload.put(warning("sample-gap",
                    "Logging had one or more long sample gaps while the session was active.", sampleGaps));
        }
        if (backgroundEvents > 0) {
            payload.put(warning("background-tested",
                    "App foreground/background transitions were captured for this session.", backgroundEvents));
        }
        if (emptyTelemetry > 0) {
            payload.put(warning("empty-telemetry",
                    "This older session contains empty telemetry rows from a broken adapter pipe. Product summaries now ignore them.",
                    emptyTelemetry));
        }
        if (movingWithZeroRpm > 0) {
            payload.put(warning("rpm-zero-moving",
                    "Vehicle speed was observed while standard engine RPM stayed at 0. Validate engine-running behavior with Scan.",
                    movingWithZeroRpm));
        }
        if (powerRows == 0) {
            payload.put(warning("power-pid-missing",
                    "No real power/kW rows are stored yet. Pack or charger power needs validated Volt-specific PIDs.",
                    0));
        }
        return payload;
    }

    private static JSONObject warning(String code, String detail, long count) throws JSONException {
        JSONObject item = new JSONObject();
        item.put("code", code);
        item.put("detail", detail);
        item.put("count", count);
        return item;
    }

    private static int maxIntForSession(SQLiteDatabase db, String column, long sessionId) {
        try (Cursor cursor = db.rawQuery(
                "SELECT MAX(" + column + ") FROM " + VoltTrackerDb.TABLE_TELEMETRY
                        + " WHERE session_id = ? AND " + USEFUL_TELEMETRY_WHERE,
                new String[]{String.valueOf(sessionId)}
        )) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getInt(0) : 0;
        }
    }

    private static int maxInt(SQLiteDatabase db, String table, String column) {
        String where = VoltTrackerDb.TABLE_TELEMETRY.equals(table) ? " WHERE " + USEFUL_TELEMETRY_WHERE : "";
        try (Cursor cursor = db.rawQuery("SELECT MAX(" + column + ") FROM " + table + where, null)) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getInt(0) : 0;
        }
    }

    private static double maxDouble(SQLiteDatabase db, String table, String column) {
        String where = VoltTrackerDb.TABLE_TELEMETRY.equals(table) ? " WHERE " + USEFUL_TELEMETRY_WHERE : "";
        try (Cursor cursor = db.rawQuery("SELECT MAX(" + column + ") FROM " + table + where, null)) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getDouble(0) : 0d;
        }
    }

    private static long averageSampleIntervalMs(SQLiteDatabase db, long sessionId) {
        try (Cursor cursor = db.rawQuery(
                "SELECT MIN(captured_at_ms), MAX(captured_at_ms), COUNT(*) FROM "
                        + VoltTrackerDb.TABLE_TELEMETRY
                        + " WHERE session_id = ? AND " + USEFUL_TELEMETRY_WHERE,
                new String[]{String.valueOf(sessionId)}
        )) {
            if (!cursor.moveToFirst() || cursor.getLong(2) < 2) {
                return 0L;
            }
            return Math.max(0L, Math.round((cursor.getDouble(1) - cursor.getDouble(0)) / (cursor.getLong(2) - 1)));
        }
    }

    private static long averageSampleIntervalMs(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery(
                "SELECT MIN(captured_at_ms), MAX(captured_at_ms), COUNT(*) FROM "
                        + VoltTrackerDb.TABLE_TELEMETRY
                        + " WHERE " + USEFUL_TELEMETRY_WHERE,
                null
        )) {
            if (!cursor.moveToFirst() || cursor.getLong(2) < 2) {
                return 0L;
            }
            return Math.max(0L, Math.round((cursor.getDouble(1) - cursor.getDouble(0)) / (cursor.getLong(2) - 1)));
        }
    }

    private static double totalDistanceMeters(SQLiteDatabase db) throws JSONException {
        double total = 0d;
        for (ObdSessionRecord session : getRecentSessions(db, 20)) {
            total += distanceMeters(routePointsForSessionJson(db, session.id, 1000));
        }
        return total;
    }

    private static ObdSessionRecord latestReviewableSession(SQLiteDatabase db) {
        for (ObdSessionRecord session : getRecentSessions(db, 20)) {
            long usefulTelemetry = countRowsWhere(db, VoltTrackerDb.TABLE_TELEMETRY,
                    "session_id = ? AND " + USEFUL_TELEMETRY_WHERE,
                    new String[]{String.valueOf(session.id)});
            long pidRows = countRowsWhere(db, VoltTrackerDb.TABLE_PID_OBSERVATIONS,
                    "session_id = ?",
                    new String[]{String.valueOf(session.id)});
            long locationRows = countRowsWhere(db, VoltTrackerDb.TABLE_LOCATION_SAMPLES,
                    "session_id = ?",
                    new String[]{String.valueOf(session.id)});
            if (usefulTelemetry > 0 || pidRows > 0 || locationRows > 0) {
                return session;
            }
        }
        return null;
    }

    private static List<ObdSessionRecord> getRecentSessions(SQLiteDatabase db, int limit) {
        List<ObdSessionRecord> records = new ArrayList<>();
        try (Cursor cursor = db.query(
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

    // Package-private for unit testing of the route-distance math.
    static double distanceMeters(JSONArray points) throws JSONException {
        double total = 0d;
        JSONObject previous = null;
        for (int i = 0; i < points.length(); i++) {
            JSONObject point = points.getJSONObject(i);
            if (previous != null) {
                total += haversineMeters(
                        previous.optDouble("lat"), previous.optDouble("lng"),
                        point.optDouble("lat"), point.optDouble("lng"));
            }
            previous = point;
        }
        return total;
    }

    private static JSONObject boundsFor(JSONArray points) throws JSONException {
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

    // Package-private for unit testing.
    static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double earthMeters = 6371000d;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2d) * Math.sin(dLat / 2d)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2d) * Math.sin(dLng / 2d);
        return earthMeters * 2d * Math.atan2(Math.sqrt(a), Math.sqrt(1d - a));
    }

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

    private static void putOptionalBool(ContentValues values, String column, JSONObject json, String key) {
        if (json.has(key) && !json.isNull(key)) {
            values.put(column, json.optBoolean(key) ? 1 : 0);
        }
    }

    private static void putOptionalDouble(ContentValues values, String column, JSONObject json, String key) {
        if (json.has(key) && !json.isNull(key)) {
            values.put(column, json.optDouble(key));
        }
    }

    private static void putOptionalDouble(
            ContentValues values,
            String column,
            JSONObject json,
            String primaryKey,
            String fallbackKey
    ) {
        if (json.has(primaryKey) && !json.isNull(primaryKey)) {
            values.put(column, json.optDouble(primaryKey));
        } else if (json.has(fallbackKey) && !json.isNull(fallbackKey)) {
            values.put(column, json.optDouble(fallbackKey));
        }
    }

    private static void putNullable(JSONObject json, String key, Double value) throws JSONException {
        if (value != null) {
            json.put(key, value.doubleValue());
        }
    }

    private static void putNullable(JSONObject json, String key, Long value) throws JSONException {
        if (value != null) {
            json.put(key, value.longValue());
        }
    }

    private static long optTimestamp(JSONObject json, String key, long fallback) {
        return json.has(key) && !json.isNull(key) ? json.optLong(key, fallback) : fallback;
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

    private static boolean isUsefulTelemetry(JSONObject sample) {
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

    private static long countRowsWhere(SQLiteDatabase db, String table, String where, String[] args) {
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + table + " WHERE " + where, args)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    private static JSONObject parseObject(String json) {
        try {
            return json == null || json.trim().isEmpty() ? new JSONObject() : new JSONObject(json);
        } catch (JSONException ex) {
            return new JSONObject();
        }
    }

    private static JSONArray reverse(JSONArray source) throws JSONException {
        JSONArray target = new JSONArray();
        for (int i = source.length() - 1; i >= 0; i -= 1) {
            target.put(source.get(i));
        }
        return target;
    }

    private static void updateSessionLastEvent(SQLiteDatabase db, long sessionId, long occurredAtMs) {
        if (sessionId <= 0) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("last_event_at_ms", occurredAtMs);
        db.update(VoltTrackerDb.TABLE_SESSIONS, values, "_id = ?",
                new String[]{String.valueOf(sessionId)});
    }

    private static <T> T firstOrNull(List<T> items) {
        return items.isEmpty() ? null : items.get(0);
    }
}
