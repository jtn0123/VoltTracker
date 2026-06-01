package com.volttracker.obdpoc.data;

import static com.volttracker.obdpoc.data.ObdStoreSupport.boundsFor;
import static com.volttracker.obdpoc.data.ObdStoreSupport.countRowsWhere;
import static com.volttracker.obdpoc.data.ObdStoreSupport.distanceMeters;
import static com.volttracker.obdpoc.data.ObdStoreSupport.getRecentSessions;
import static com.volttracker.obdpoc.data.ObdStoreSupport.nullableDoubleBoxed;
import static com.volttracker.obdpoc.data.ObdStoreSupport.sessionToJson;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Route and scalar-track projections: turns a session's GPS fixes and time-series columns (SOC,
 * power) into the evenly-downsampled point arrays the dashboard polyline/track renderers consume.
 * Split out of {@link ObdStoreTrips} (A1) because both the trip/insights aggregation and the
 * per-session review screen depend on this same geometry engine. All methods are stateless statics.
 */
final class ObdStoreRouteProjection {

    private ObdStoreRouteProjection() {}

    static JSONObject routeForSession(SQLiteDatabase db, ObdSessionRecord session, int limit)
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

    static JSONArray recentRoutes(SQLiteDatabase db, int sessionLimit, int pointLimit)
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

    static JSONArray routePointsForSessionJson(SQLiteDatabase db, long sessionId, int limit)
            throws JSONException {
        int target = Math.max(1, Math.min(limit, MAX_TRACK_POINTS));
        String[] sessionArg = {String.valueOf(sessionId)};

        long total =
                countRowsWhere(
                        db, VoltTrackerDb.TABLE_LOCATION_SAMPLES, "session_id = ?", sessionArg);
        String telemetryWhere = "session_id = ? AND latitude IS NOT NULL AND longitude IS NOT NULL";
        long telemetryTotal =
                countRowsWhere(db, VoltTrackerDb.TABLE_TELEMETRY, telemetryWhere, sessionArg);
        if (total > 0) {
            JSONArray locationPoints =
                    downsampledRoutePoints(
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
            if (locationPoints.length() >= 2 || telemetryTotal == 0) {
                return locationPoints;
            }
        }
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
        item.put("accuracyM", jsonNumberOrNull(nullableDoubleBoxed(cursor, "accuracy_m")));
        item.put("bearingDeg", jsonNumberOrNull(nullableDoubleBoxed(cursor, "bearing_deg")));
        if (fromLocationSamples) {
            item.put("speedMps", jsonNumberOrNull(nullableDoubleBoxed(cursor, "speed_mps")));
            item.put("altM", jsonNumberOrNull(nullableDoubleBoxed(cursor, "altitude_m")));
        } else {
            item.put("speedMps", jsonNumberOrNull(nullableDoubleBoxed(cursor, "gps_speed_mps")));
            item.put("soc", jsonNumberOrNull(nullableDoubleBoxed(cursor, "soc")));
        }
        return item;
    }

    private static Object jsonNumberOrNull(Number value) {
        return value == null ? JSONObject.NULL : value;
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
