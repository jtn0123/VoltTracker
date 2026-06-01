package com.volttracker.obdpoc.data;

import static com.volttracker.obdpoc.data.ObdStoreSupport.USEFUL_TELEMETRY_WHERE;
import static com.volttracker.obdpoc.data.ObdStoreSupport.averageSampleIntervalMs;
import static com.volttracker.obdpoc.data.ObdStoreSupport.boundedLimit;
import static com.volttracker.obdpoc.data.ObdStoreSupport.clean;
import static com.volttracker.obdpoc.data.ObdStoreSupport.countRowsWhere;
import static com.volttracker.obdpoc.data.ObdStoreSupport.getRecentSessions;
import static com.volttracker.obdpoc.data.ObdStoreSupport.maxIntForSession;
import static com.volttracker.obdpoc.data.ObdStoreSupport.nullableIntBoxed;
import static com.volttracker.obdpoc.data.ObdStoreSupport.parseObject;
import static com.volttracker.obdpoc.data.ObdStoreSupport.reverse;
import static com.volttracker.obdpoc.data.ObdStoreSupport.sessionToJson;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Per-session diagnostic review projection — the deep "what happened in this session?" payload the
 * Diagnostics screen reads: counts, health snapshot, vehicle-state breakdown, event/PID timelines,
 * speed trace, route and derived warnings. Split out of {@link ObdStoreTrips} (A1); route geometry
 * is delegated to {@link ObdStoreRouteProjection}. All methods are stateless statics.
 */
final class ObdStoreSessionReview {

    private ObdStoreSessionReview() {}

    static ObdSessionRecord latestReviewableSession(SQLiteDatabase db) {
        for (ObdSessionRecord session : getRecentSessions(db, 20)) {
            long usefulTelemetry =
                    countRowsWhere(
                            db,
                            VoltTrackerDb.TABLE_TELEMETRY,
                            "session_id = ? AND " + USEFUL_TELEMETRY_WHERE,
                            new String[] {String.valueOf(session.id)});
            long pidRows =
                    countRowsWhere(
                            db,
                            VoltTrackerDb.TABLE_PID_OBSERVATIONS,
                            "session_id = ?",
                            new String[] {String.valueOf(session.id)});
            long locationRows =
                    countRowsWhere(
                            db,
                            VoltTrackerDb.TABLE_LOCATION_SAMPLES,
                            "session_id = ?",
                            new String[] {String.valueOf(session.id)});
            if (usefulTelemetry > 0 || pidRows > 0 || locationRows > 0) {
                return session;
            }
        }
        return null;
    }

    static JSONObject sessionReview(SQLiteDatabase db, ObdSessionRecord session)
            throws JSONException {
        JSONObject payload = new JSONObject();
        payload.put("session", sessionToJson(session));
        payload.put(
                "pidObservationCount",
                countRowsWhere(
                        db,
                        VoltTrackerDb.TABLE_PID_OBSERVATIONS,
                        "session_id = ?",
                        new String[] {String.valueOf(session.id)}));
        payload.put(
                "locationSampleCount",
                countRowsWhere(
                        db,
                        VoltTrackerDb.TABLE_LOCATION_SAMPLES,
                        "session_id = ?",
                        new String[] {String.valueOf(session.id)}));
        payload.put(
                "eventCount",
                countRowsWhere(
                        db,
                        VoltTrackerDb.TABLE_EVENTS,
                        "session_id = ?",
                        new String[] {String.valueOf(session.id)}));
        payload.put(
                "parsedPidCount",
                countRowsWhere(
                        db,
                        VoltTrackerDb.TABLE_PID_OBSERVATIONS,
                        "session_id = ? AND (value_text IS NOT NULL AND value_text != '')",
                        new String[] {String.valueOf(session.id)}));
        payload.put(
                "unknownPidCount",
                countRowsWhere(
                        db,
                        VoltTrackerDb.TABLE_PID_OBSERVATIONS,
                        "session_id = ? AND (value_text IS NULL OR value_text = '')",
                        new String[] {String.valueOf(session.id)}));
        long usefulTelemetry =
                countRowsWhere(
                        db,
                        VoltTrackerDb.TABLE_TELEMETRY,
                        "session_id = ? AND " + USEFUL_TELEMETRY_WHERE,
                        new String[] {String.valueOf(session.id)});
        payload.put("usefulTelemetryCount", usefulTelemetry);
        payload.put("emptyTelemetryCount", Math.max(0L, session.sampleCount - usefulTelemetry));
        payload.put("maxSpeedKph", maxIntForSession(db, "speed_kph", session.id));
        payload.put("avgSampleIntervalMs", averageSampleIntervalMs(db, session.id));
        payload.put(
                "backgroundSampleCount",
                countRowsWhere(
                        db,
                        VoltTrackerDb.TABLE_TELEMETRY,
                        "session_id = ? AND app_foreground = 0",
                        new String[] {String.valueOf(session.id)}));
        payload.put(
                "sampleGapEventCount",
                countRowsWhere(
                        db,
                        VoltTrackerDb.TABLE_EVENTS,
                        "session_id = ? AND detail = ?",
                        new String[] {String.valueOf(session.id), "sample_gap"}));
        payload.put("latestHealth", latestHealthJson(db, session.id));
        payload.put("stateCounts", stateCountsJson(db, session.id));
        payload.put("timeline", recentEventsJson(db, session.id, 20));
        payload.put("recentPidFrames", recentPidFramesJson(db, session.id, 20));
        payload.put("speedTrace", recentSpeedTraceJson(db, session.id, 48));
        payload.put("route", ObdStoreRouteProjection.routeForSession(db, session, 180));
        payload.put("warnings", sessionWarningsJson(db, session.id));
        return payload;
    }

    private static JSONObject latestHealthJson(SQLiteDatabase db, long sessionId)
            throws JSONException {
        try (Cursor cursor =
                db.query(
                        VoltTrackerDb.TABLE_TELEMETRY,
                        new String[] {"json"},
                        "session_id = ? AND " + USEFUL_TELEMETRY_WHERE,
                        new String[] {String.valueOf(sessionId)},
                        null,
                        null,
                        "captured_at_ms DESC",
                        "1")) {
            if (!cursor.moveToFirst()) {
                return new JSONObject();
            }
            JSONObject rawJson =
                    parseObject(cursor.getString(cursor.getColumnIndexOrThrow("json")));
            JSONObject payload = new JSONObject();
            payload.put("appForeground", rawJson.optBoolean("appForeground", true));
            payload.put(
                    "foregroundServiceActive",
                    rawJson.optBoolean("foregroundServiceActive", false));
            payload.put("backgroundSampleCount", rawJson.optInt("backgroundSampleCount", 0));
            payload.put("sampleGapCount", rawJson.optInt("sampleGapCount", 0));
            payload.put("lastSampleGapMs", rawJson.optLong("lastSampleGapMs", 0L));
            payload.put("maxSampleGapMs", rawJson.optLong("maxSampleGapMs", 0L));
            return payload;
        }
    }

    private static JSONObject stateCountsJson(SQLiteDatabase db, long sessionId)
            throws JSONException {
        JSONObject payload = new JSONObject();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT vehicle_state, COUNT(*) FROM "
                                + VoltTrackerDb.TABLE_TELEMETRY
                                + " WHERE session_id = ? AND "
                                + USEFUL_TELEMETRY_WHERE
                                + " GROUP BY vehicle_state",
                        new String[] {String.valueOf(sessionId)})) {
            while (cursor.moveToNext()) {
                String state = clean(cursor.getString(0));
                payload.put(state.isEmpty() ? "unknown" : state, cursor.getLong(1));
            }
        }
        return payload;
    }

    private static JSONArray sessionWarningsJson(SQLiteDatabase db, long sessionId)
            throws JSONException {
        JSONArray payload = new JSONArray();
        long chargeHints =
                countRowsWhere(
                        db,
                        VoltTrackerDb.TABLE_TELEMETRY,
                        "session_id = ? AND charge_transition_hint = 1",
                        new String[] {String.valueOf(sessionId)});
        long speedRejected =
                countRowsWhere(
                        db,
                        VoltTrackerDb.TABLE_EVENTS,
                        "session_id = ? AND detail = ?",
                        new String[] {String.valueOf(sessionId), "speed_rejected"});
        long gpsSamples =
                countRowsWhere(
                        db,
                        VoltTrackerDb.TABLE_LOCATION_SAMPLES,
                        "session_id = ?",
                        new String[] {String.valueOf(sessionId)});
        long unknownPids =
                countRowsWhere(
                        db,
                        VoltTrackerDb.TABLE_PID_OBSERVATIONS,
                        "session_id = ? AND (value_text IS NULL OR value_text = '')",
                        new String[] {String.valueOf(sessionId)});
        long sampleGaps =
                countRowsWhere(
                        db,
                        VoltTrackerDb.TABLE_EVENTS,
                        "session_id = ? AND detail = ?",
                        new String[] {String.valueOf(sessionId), "sample_gap"});
        long backgroundEvents =
                countRowsWhere(
                        db,
                        VoltTrackerDb.TABLE_EVENTS,
                        "session_id = ? AND (detail = ? OR detail = ?)",
                        new String[] {
                            String.valueOf(sessionId), "app_backgrounded", "app_foregrounded"
                        });
        long emptyTelemetry =
                countRowsWhere(
                        db,
                        VoltTrackerDb.TABLE_TELEMETRY,
                        "session_id = ? AND NOT " + USEFUL_TELEMETRY_WHERE,
                        new String[] {String.valueOf(sessionId)});
        long movingWithZeroRpm =
                countRowsWhere(
                        db,
                        VoltTrackerDb.TABLE_TELEMETRY,
                        "session_id = ? AND speed_kph > 0 AND rpm = 0 AND "
                                + USEFUL_TELEMETRY_WHERE,
                        new String[] {String.valueOf(sessionId)});
        long powerRows =
                countRowsWhere(
                        db,
                        VoltTrackerDb.TABLE_TELEMETRY,
                        "session_id = ? AND power_kw IS NOT NULL AND " + USEFUL_TELEMETRY_WHERE,
                        new String[] {String.valueOf(sessionId)});
        if (chargeHints > 0 || speedRejected > 0) {
            payload.put(
                    warning(
                            "charge-speed-hint",
                            "Rejected 255 km/h speed frame seen. Treat it as a charging/transition clue, not vehicle speed.",
                            Math.max(chargeHints, speedRejected)));
        }
        if (gpsSamples == 0) {
            payload.put(warning("gps-missing", "No GPS samples were stored for this session.", 0));
        }
        if (unknownPids > 0) {
            payload.put(
                    warning(
                            "pid-unparsed",
                            "Some PID responses are stored but not parsed yet.",
                            unknownPids));
        }
        if (sampleGaps > 0) {
            payload.put(
                    warning(
                            "sample-gap",
                            "Logging had one or more long sample gaps while the session was active.",
                            sampleGaps));
        }
        if (backgroundEvents > 0) {
            payload.put(
                    warning(
                            "background-tested",
                            "App foreground/background transitions were captured for this session.",
                            backgroundEvents));
        }
        if (emptyTelemetry > 0) {
            payload.put(
                    warning(
                            "empty-telemetry",
                            "This older session contains empty telemetry rows from a broken adapter pipe. Product summaries now ignore them.",
                            emptyTelemetry));
        }
        if (movingWithZeroRpm > 0) {
            payload.put(
                    warning(
                            "rpm-zero-moving",
                            "Vehicle speed was observed while standard engine RPM stayed at 0. Validate engine-running behavior with Scan.",
                            movingWithZeroRpm));
        }
        if (powerRows == 0) {
            payload.put(
                    warning(
                            "power-pid-missing",
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

    private static JSONArray recentEventsJson(SQLiteDatabase db, long sessionId, int limit)
            throws JSONException {
        JSONArray payload = new JSONArray();
        try (Cursor cursor =
                db.query(
                        VoltTrackerDb.TABLE_EVENTS,
                        new String[] {
                            "occurred_at_ms", "kind", "state", "detail", "blocked", "payload"
                        },
                        "session_id = ?",
                        new String[] {String.valueOf(sessionId)},
                        null,
                        null,
                        "occurred_at_ms DESC",
                        boundedLimit(limit))) {
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                item.put("atMs", cursor.getLong(cursor.getColumnIndexOrThrow("occurred_at_ms")));
                item.put("kind", clean(cursor.getString(cursor.getColumnIndexOrThrow("kind"))));
                item.put("state", clean(cursor.getString(cursor.getColumnIndexOrThrow("state"))));
                item.put("detail", clean(cursor.getString(cursor.getColumnIndexOrThrow("detail"))));
                item.put("blocked", cursor.getInt(cursor.getColumnIndexOrThrow("blocked")) != 0);
                item.put(
                        "payload",
                        parseObject(cursor.getString(cursor.getColumnIndexOrThrow("payload"))));
                payload.put(item);
            }
        }
        return reverse(payload);
    }

    private static JSONArray recentPidFramesJson(SQLiteDatabase db, long sessionId, int limit)
            throws JSONException {
        JSONArray payload = new JSONArray();
        try (Cursor cursor =
                db.query(
                        VoltTrackerDb.TABLE_PID_OBSERVATIONS,
                        new String[] {
                            "observed_at_ms",
                            "command",
                            "header",
                            "pid",
                            "name",
                            "value_text",
                            "value_numeric",
                            "unit",
                            "raw_response",
                            "json"
                        },
                        "session_id = ?",
                        new String[] {String.valueOf(sessionId)},
                        null,
                        null,
                        "observed_at_ms DESC",
                        boundedLimit(limit))) {
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                String valueText =
                        clean(cursor.getString(cursor.getColumnIndexOrThrow("value_text")));
                JSONObject rawJson =
                        parseObject(cursor.getString(cursor.getColumnIndexOrThrow("json")));
                item.put("atMs", cursor.getLong(cursor.getColumnIndexOrThrow("observed_at_ms")));
                item.put(
                        "command",
                        clean(cursor.getString(cursor.getColumnIndexOrThrow("command"))));
                item.put("header", clean(cursor.getString(cursor.getColumnIndexOrThrow("header"))));
                item.put("pid", clean(cursor.getString(cursor.getColumnIndexOrThrow("pid"))));
                item.put("name", clean(cursor.getString(cursor.getColumnIndexOrThrow("name"))));
                item.put("valueText", valueText);
                item.put("unit", clean(cursor.getString(cursor.getColumnIndexOrThrow("unit"))));
                item.put(
                        "rawResponse",
                        clean(cursor.getString(cursor.getColumnIndexOrThrow("raw_response"))));
                item.put("durationMs", rawJson.optLong("durationMs", 0L));
                item.put("gotPrompt", rawJson.optBoolean("gotPrompt", false));
                item.put("parsed", !valueText.isEmpty());
                payload.put(item);
            }
        }
        return payload;
    }

    private static JSONArray recentSpeedTraceJson(SQLiteDatabase db, long sessionId, int limit)
            throws JSONException {
        JSONArray payload = new JSONArray();
        try (Cursor cursor =
                db.query(
                        VoltTrackerDb.TABLE_TELEMETRY,
                        new String[] {"captured_at_ms", "speed_kph", "vehicle_state", "json"},
                        "session_id = ? AND " + USEFUL_TELEMETRY_WHERE,
                        new String[] {String.valueOf(sessionId)},
                        null,
                        null,
                        "captured_at_ms DESC",
                        boundedLimit(limit))) {
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                JSONObject rawJson =
                        parseObject(cursor.getString(cursor.getColumnIndexOrThrow("json")));
                item.put("atMs", cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")));
                // Boxed so SQL NULL projects as JSON null (not 0) — see ObdStoreReports for
                // context.
                Integer boxedSpeed = nullableIntBoxed(cursor, "speed_kph");
                item.put("speedKph", boxedSpeed == null ? JSONObject.NULL : boxedSpeed);
                item.put(
                        "state",
                        clean(cursor.getString(cursor.getColumnIndexOrThrow("vehicle_state"))));
                item.put("chargeTransitionHint", rawJson.optBoolean("chargeTransitionHint", false));
                item.put("speedRejectedKph", rawJson.optInt("speedRejectedKph", 0));
                payload.put(item);
            }
        }
        return reverse(payload);
    }
}
