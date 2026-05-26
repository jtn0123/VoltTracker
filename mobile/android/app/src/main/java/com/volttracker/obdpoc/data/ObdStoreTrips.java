package com.volttracker.obdpoc.data;

import static com.volttracker.obdpoc.data.ObdStoreSupport.USEFUL_TELEMETRY_WHERE;
import static com.volttracker.obdpoc.data.ObdStoreSupport.averageSampleIntervalMs;
import static com.volttracker.obdpoc.data.ObdStoreSupport.boundedLimit;
import static com.volttracker.obdpoc.data.ObdStoreSupport.boundsFor;
import static com.volttracker.obdpoc.data.ObdStoreSupport.clean;
import static com.volttracker.obdpoc.data.ObdStoreSupport.countRows;
import static com.volttracker.obdpoc.data.ObdStoreSupport.countRowsWhere;
import static com.volttracker.obdpoc.data.ObdStoreSupport.distanceMeters;
import static com.volttracker.obdpoc.data.ObdStoreSupport.getRecentSessions;
import static com.volttracker.obdpoc.data.ObdStoreSupport.maxIntForSession;
import static com.volttracker.obdpoc.data.ObdStoreSupport.maxIntForSessionBoxed;
import static com.volttracker.obdpoc.data.ObdStoreSupport.nullableDouble;
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
 * Trip, route and per-session review projections, all derived on read from telemetry and GPS
 * samples already on disk. Split out of {@link ObdLocalStore} to keep each file under 500 lines;
 * {@link ObdStoreReports} composes this class for the storage summary.
 */
final class ObdStoreTrips {

    private final VoltTrackerDb helper;

    ObdStoreTrips(VoltTrackerDb helper) {
        this.helper = helper;
    }

    // ---- trips & insights ----------------------------------------------------------

    /**
     * Real trip list, one entry per logged OBD driving session. Distance, duration and speeds are
     * computed on read; no separate trip table is required. Demo and scan sessions are excluded.
     */
    JSONArray tripsJson(int limit) {
        JSONArray payload = new JSONArray();
        SQLiteDatabase db = helper.getReadableDatabase();
        try {
            for (ObdSessionRecord session :
                    getRecentSessions(db, Math.max(1, Math.min(limit, 100)))) {
                if (!ObdLocalStore.MODE_OBD.equals(session.mode)) {
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

    private JSONObject tripJson(SQLiteDatabase db, ObdSessionRecord session) throws JSONException {
        long usefulSamples =
                countRowsWhere(
                        db,
                        VoltTrackerDb.TABLE_TELEMETRY,
                        "session_id = ? AND " + USEFUL_TELEMETRY_WHERE,
                        new String[] {String.valueOf(session.id)});
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
        // Boxed so a trip with no accepted speed samples (e.g. all-sentinel charging session)
        // projects as JSON null instead of 0, letting the dashboard render "--" instead of
        // "0 mph". Numeric callers like insightsJson use optInt with a 0 default, so the
        // existing math still degrades gracefully.
        Integer maxSpeed = maxIntForSessionBoxed(db, "speed_kph", session.id);
        trip.put("maxSpeedKph", maxSpeed == null ? JSONObject.NULL : maxSpeed);
        trip.put("avgMovingSpeedKph", avgMovingSpeedKph(db, session.id));
        trip.put("sampleCount", usefulSamples);
        trip.put("pointCount", points.length());
        trip.put("hasRoute", points.length() >= 2);
        trip.put("adapterName", session.adapterName);
        trip.put("status", session.status);
        return trip;
    }

    private static double avgMovingSpeedKph(SQLiteDatabase db, long sessionId) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT AVG(speed_kph) FROM "
                                + VoltTrackerDb.TABLE_TELEMETRY
                                + " WHERE session_id = ? AND speed_kph > 0 AND "
                                + USEFUL_TELEMETRY_WHERE,
                        new String[] {String.valueOf(sessionId)})) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getDouble(0) : 0d;
        }
    }

    /** Cross-session lifetime aggregates for the Insights screen, all derived from trips. */
    JSONObject insightsJson() {
        JSONObject payload = new JSONObject();
        SQLiteDatabase db = helper.getReadableDatabase();
        try {
            JSONArray trips = tripsJson(100);
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
            payload.put(
                    "sampleCount",
                    countRowsWhere(
                            db, VoltTrackerDb.TABLE_TELEMETRY, USEFUL_TELEMETRY_WHERE, null));
            payload.put("locationSampleCount", countRows(db, VoltTrackerDb.TABLE_LOCATION_SAMPLES));
        } catch (JSONException ignored) {
            // Local numeric/string values are safe.
        }
        return payload;
    }

    double totalDistanceMeters(SQLiteDatabase db) throws JSONException {
        double total = 0d;
        for (ObdSessionRecord session : getRecentSessions(db, 20)) {
            total += distanceMeters(routePointsForSessionJson(db, session.id, 1000));
        }
        return total;
    }

    ObdSessionRecord latestReviewableSession(SQLiteDatabase db) {
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

    // ---- per-session review --------------------------------------------------------

    JSONObject sessionReview(SQLiteDatabase db, ObdSessionRecord session) throws JSONException {
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
        payload.put("route", routeForSession(db, session, 180));
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

    // ---- routes --------------------------------------------------------------------

    JSONObject routeForSession(SQLiteDatabase db, ObdSessionRecord session, int limit)
            throws JSONException {
        JSONObject payload = new JSONObject();
        JSONArray points = routePointsForSessionJson(db, session.id, limit);
        payload.put("session", sessionToJson(session));
        payload.put("points", points);
        payload.put("pointCount", points.length());
        payload.put("distanceMeters", distanceMeters(points));
        payload.put("bounds", boundsFor(points));
        payload.put("socTrack", socTrackForSessionJson(db, session.id, limit));
        payload.put("powerTrack", powerTrackForSessionJson(db, session.id, limit));
        return payload;
    }

    JSONArray recentRoutes(SQLiteDatabase db, int sessionLimit, int pointLimit)
            throws JSONException {
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
            route.put("socTrack", socTrackForSessionJson(db, session.id, pointLimit));
            route.put("powerTrack", powerTrackForSessionJson(db, session.id, pointLimit));
            payload.put(route);
        }
        return payload;
    }

    // Long drives can capture thousands of GPS fixes; the dashboard's polyline renderer stays
    // smooth around 500 points, so each track is downsampled evenly across the full session
    // timespan rather than truncated to the latest 500.
    private static final int MAX_TRACK_POINTS = 500;

    private static JSONArray routePointsForSessionJson(SQLiteDatabase db, long sessionId, int limit)
            throws JSONException {
        int target = Math.max(1, Math.min(limit, MAX_TRACK_POINTS));
        String[] sessionArg = {String.valueOf(sessionId)};

        long total =
                countRowsWhere(
                        db, VoltTrackerDb.TABLE_LOCATION_SAMPLES, "session_id = ?", sessionArg);
        if (total > 0) {
            return downsampledRoutePoints(
                    db,
                    VoltTrackerDb.TABLE_LOCATION_SAMPLES,
                    new String[] {
                        "captured_at_ms",
                        "latitude",
                        "longitude",
                        "accuracy_m",
                        "speed_mps",
                        "bearing_deg",
                        "altitude_m"
                    },
                    "session_id = ?",
                    sessionArg,
                    total,
                    target,
                    /* fromLocationSamples= */ true);
        }
        String telemetryWhere = "session_id = ? AND latitude IS NOT NULL AND longitude IS NOT NULL";
        long telemetryTotal =
                countRowsWhere(db, VoltTrackerDb.TABLE_TELEMETRY, telemetryWhere, sessionArg);
        if (telemetryTotal == 0) {
            return new JSONArray();
        }
        return downsampledRoutePoints(
                db,
                VoltTrackerDb.TABLE_TELEMETRY,
                new String[] {
                    "captured_at_ms",
                    "latitude",
                    "longitude",
                    "accuracy_m",
                    "gps_speed_mps",
                    "bearing_deg",
                    "soc"
                },
                telemetryWhere,
                sessionArg,
                telemetryTotal,
                target,
                /* fromLocationSamples= */ false);
    }

    private static JSONArray downsampledRoutePoints(
            SQLiteDatabase db,
            String table,
            String[] columns,
            String where,
            String[] whereArgs,
            long total,
            int target,
            boolean fromLocationSamples)
            throws JSONException {
        long stride = strideFor(total, target);
        JSONArray points = new JSONArray();
        JSONObject tail = null;
        try (Cursor cursor =
                db.query(table, columns, where, whereArgs, null, null, "captured_at_ms ASC")) {
            long idx = 0;
            while (cursor.moveToNext()) {
                JSONObject item = buildRoutePointItem(cursor, fromLocationSamples);
                if (cursor.isLast()) {
                    // Reserved so the final fix always lands on the map, even when the stride
                    // sweep has already filled the target-1 slots above.
                    tail = item;
                    break;
                }
                boolean strideKeep = total <= target || idx % stride == 0;
                boolean withinCap = total <= target || points.length() < target - 1;
                if (strideKeep && withinCap) {
                    points.put(item);
                }
                idx++;
            }
        }
        if (tail != null) {
            points.put(tail);
        }
        return points;
    }

    private static JSONObject buildRoutePointItem(Cursor cursor, boolean fromLocationSamples)
            throws JSONException {
        JSONObject item = new JSONObject();
        item.put("atMs", cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")));
        item.put("lat", cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")));
        item.put("lng", cursor.getDouble(cursor.getColumnIndexOrThrow("longitude")));
        item.put("accuracyM", nullableDouble(cursor, "accuracy_m"));
        item.put("bearingDeg", nullableDouble(cursor, "bearing_deg"));
        if (fromLocationSamples) {
            item.put("speedMps", nullableDouble(cursor, "speed_mps"));
            item.put("altM", nullableDouble(cursor, "altitude_m"));
        } else {
            item.put("speedMps", nullableDouble(cursor, "gps_speed_mps"));
            item.put("soc", nullableDouble(cursor, "soc"));
        }
        return item;
    }

    /**
     * SOC samples for a session, ascending by time, evenly downsampled across the whole session so
     * the track lines up visually with the full route polyline (not just its tail).
     */
    private static JSONArray socTrackForSessionJson(SQLiteDatabase db, long sessionId, int limit)
            throws JSONException {
        return downsampledScalarTrack(
                db,
                sessionId,
                "soc",
                "session_id = ? AND soc IS NOT NULL",
                "soc",
                Math.max(1, Math.min(limit, MAX_TRACK_POINTS)));
    }

    /** Power-kW samples for a session, downsampled the same way as the SOC and route tracks. */
    private static JSONArray powerTrackForSessionJson(SQLiteDatabase db, long sessionId, int limit)
            throws JSONException {
        return downsampledScalarTrack(
                db,
                sessionId,
                "power_kw",
                "session_id = ? AND power_kw IS NOT NULL",
                "powerKw",
                Math.max(1, Math.min(limit, MAX_TRACK_POINTS)));
    }

    private static JSONArray downsampledScalarTrack(
            SQLiteDatabase db,
            long sessionId,
            String column,
            String where,
            String jsonKey,
            int target)
            throws JSONException {
        String[] args = {String.valueOf(sessionId)};
        long total = countRowsWhere(db, VoltTrackerDb.TABLE_TELEMETRY, where, args);
        if (total == 0) {
            return new JSONArray();
        }
        long stride = strideFor(total, target);
        JSONArray track = new JSONArray();
        JSONObject tail = null;
        try (Cursor cursor =
                db.query(
                        VoltTrackerDb.TABLE_TELEMETRY,
                        new String[] {"captured_at_ms", column},
                        where,
                        args,
                        null,
                        null,
                        "captured_at_ms ASC")) {
            long idx = 0;
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                item.put("atMs", cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")));
                item.put(jsonKey, cursor.getDouble(cursor.getColumnIndexOrThrow(column)));
                if (cursor.isLast()) {
                    tail = item;
                    break;
                }
                boolean strideKeep = total <= target || idx % stride == 0;
                boolean withinCap = total <= target || track.length() < target - 1;
                if (strideKeep && withinCap) {
                    track.put(item);
                }
                idx++;
            }
        }
        if (tail != null) {
            track.put(tail);
        }
        return track;
    }

    private static long strideFor(long total, int target) {
        if (total <= target || target <= 1) {
            return 1L;
        }
        // The final row is always emitted separately as the "tail" sample, so distribute the
        // remaining (target - 1) samples evenly across the first (total - 1) cursor positions.
        return Math.max(1L, (total - 1L) / (long) (target - 1));
    }
}
