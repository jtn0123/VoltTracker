package com.volttracker.obdpoc.data;

import static com.volttracker.obdpoc.data.ObdStoreSupport.USEFUL_TELEMETRY_WHERE;
import static com.volttracker.obdpoc.data.ObdStoreSupport.countRows;
import static com.volttracker.obdpoc.data.ObdStoreSupport.countRowsWhere;
import static com.volttracker.obdpoc.data.ObdStoreSupport.distanceMeters;
import static com.volttracker.obdpoc.data.ObdStoreSupport.getAllSessions;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Trip, route and per-session review projections, all derived on read from telemetry and GPS
 * samples already on disk. Split out of {@link ObdLocalStore} to keep each file under 500 lines;
 * {@link ObdStoreReports} composes this class for the storage summary.
 */
final class ObdStoreTrips {

    private static final int ROLLUP_CACHE_VERSION = 2;

    private final VoltTrackerDb helper;

    ObdStoreTrips(VoltTrackerDb helper) {
        this.helper = helper;
    }

    // ---- trips & insights ----------------------------------------------------------

    /**
     * Real trip list, one entry per detected drive window. A single long OBD session can surface
     * multiple rows when GPS/OBD evidence shows the car stopped for several minutes.
     */
    JSONArray tripsJson(int limit) {
        JSONArray payload = new JSONArray();
        SQLiteDatabase db = helper.getReadableDatabase();
        try {
            List<JSONObject> allTrips = new ArrayList<>();
            for (ObdSessionRecord session : getAllSessions(db)) {
                if (!ObdLocalStore.MODE_OBD.equals(session.mode)) {
                    continue;
                }
                allTrips.addAll(tripJsons(db, session));
            }
            Collections.sort(
                    allTrips,
                    new Comparator<JSONObject>() {
                        @Override
                        public int compare(JSONObject left, JSONObject right) {
                            return Long.compare(
                                    right.optLong("endedAtMs", 0L), left.optLong("endedAtMs", 0L));
                        }
                    });
            for (int i = 0; i < allTrips.size() && payload.length() < limit; i++) {
                payload.put(allTrips.get(i));
            }
        } catch (JSONException ignored) {
            // Local numeric/string values are safe.
        }
        return payload;
    }

    private List<JSONObject> tripJsons(SQLiteDatabase db, ObdSessionRecord session)
            throws JSONException {
        List<JSONObject> trips = new ArrayList<>();
        for (DriveWindowDetector.DriveWindow window :
                DriveWindowDetector.windowsForSession(db, session)) {
            JSONObject trip = tripJson(db, session, window);
            if (trip != null) {
                trips.add(trip);
            }
        }
        return trips;
    }

    private JSONObject tripJson(
            SQLiteDatabase db, ObdSessionRecord session, DriveWindowDetector.DriveWindow window)
            throws JSONException {
        long usefulSamples =
                countRowsWhere(
                        db,
                        VoltTrackerDb.TABLE_TELEMETRY,
                        "session_id = ? AND captured_at_ms >= ? AND captured_at_ms <= ? AND "
                                + USEFUL_TELEMETRY_WHERE,
                        new String[] {
                            String.valueOf(session.id),
                            String.valueOf(window.startedAtMs),
                            String.valueOf(window.endedAtMs)
                        });
        if (usefulSamples <= 0) {
            // A session with no useful telemetry was a failed connection, not a trip.
            return null;
        }
        JSONArray points =
                ObdStoreRouteProjection.routePointsForSessionJson(
                        db, session.id, 1000, window.startedAtMs, window.endedAtMs);
        long startedAtMs = window.startedAtMs;
        long endedAtMs = window.endedAtMs;
        if (points.length() > 0) {
            startedAtMs = points.getJSONObject(0).optLong("atMs", startedAtMs);
            endedAtMs = points.getJSONObject(points.length() - 1).optLong("atMs", endedAtMs);
        }
        String routeKey = session.id + ":" + startedAtMs + ":" + endedAtMs;
        JSONObject trip = new JSONObject();
        trip.put("id", routeKey);
        trip.put("sessionId", session.id);
        trip.put("segmentIndex", window.index);
        trip.put("startedAtMs", startedAtMs);
        trip.put("endedAtMs", endedAtMs);
        trip.put("durationMs", Math.max(0L, endedAtMs - startedAtMs));
        trip.put("distanceMeters", distanceMeters(points));
        // Boxed so a trip with no accepted speed samples (e.g. all-sentinel charging session)
        // projects as JSON null instead of 0, letting the dashboard render "--" instead of
        // "0 mph". Numeric callers like insightsJson use optInt with a 0 default, so the
        // existing math still degrades gracefully.
        Integer maxSpeed = maxIntForWindowBoxed(db, "speed_kph", session.id, window);
        trip.put("maxSpeedKph", maxSpeed == null ? JSONObject.NULL : maxSpeed);
        trip.put("avgMovingSpeedKph", avgMovingSpeedKph(db, session.id, window));
        trip.put("sampleCount", usefulSamples);
        trip.put("pointCount", points.length());
        trip.put("hasRoute", points.length() >= 2);
        trip.put("adapterName", session.adapterName);
        trip.put("status", session.status);
        return trip;
    }

    private static Integer maxIntForWindowBoxed(
            SQLiteDatabase db,
            String column,
            long sessionId,
            DriveWindowDetector.DriveWindow window) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT MAX("
                                + column
                                + ") FROM "
                                + VoltTrackerDb.TABLE_TELEMETRY
                                + " WHERE session_id = ? AND captured_at_ms >= ?"
                                + " AND captured_at_ms <= ? AND "
                                + column
                                + " IS NOT NULL",
                        new String[] {
                            String.valueOf(sessionId),
                            String.valueOf(window.startedAtMs),
                            String.valueOf(window.endedAtMs)
                        })) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getInt(0) : null;
        }
    }

    private static double avgMovingSpeedKph(
            SQLiteDatabase db, long sessionId, DriveWindowDetector.DriveWindow window) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT AVG(speed_kph) FROM "
                                + VoltTrackerDb.TABLE_TELEMETRY
                                + " WHERE session_id = ? AND captured_at_ms >= ?"
                                + " AND captured_at_ms <= ? AND speed_kph > 0 AND "
                                + USEFUL_TELEMETRY_WHERE,
                        new String[] {
                            String.valueOf(sessionId),
                            String.valueOf(window.startedAtMs),
                            String.valueOf(window.endedAtMs)
                        })) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getDouble(0) : 0d;
        }
    }

    /** Cross-session lifetime aggregates for the Insights screen, all derived from trips. */
    JSONObject insightsJson() {
        JSONObject payload = new JSONObject();
        SQLiteDatabase db = helper.getWritableDatabase();
        try {
            // Closed-session rollups are refreshed with the current split heuristic so Insights
            // does not keep stale "one session = one drive" totals after an app update.
            List<ObdSessionRecord> active = ensureRollupsAndCollectActive(db);
            TripAggregate agg = new TripAggregate();
            try (Cursor cursor =
                    db.query(
                            VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS,
                            new String[] {
                                "distance_m",
                                "duration_ms",
                                "max_speed_kph",
                                "has_route",
                                "started_at_ms"
                            },
                            "counted = 1",
                            null,
                            null,
                            null,
                            null)) {
                while (cursor.moveToNext()) {
                    Integer maxSpeed = cursor.isNull(2) ? null : cursor.getInt(2);
                    agg.addTrip(
                            cursor.getDouble(0),
                            cursor.getLong(1),
                            maxSpeed,
                            cursor.getInt(3) != 0,
                            cursor.getLong(4));
                }
            }
            for (ObdSessionRecord session : active) {
                for (JSONObject trip : tripJsons(db, session)) {
                    Integer maxSpeed =
                            trip.isNull("maxSpeedKph") ? null : trip.optInt("maxSpeedKph");
                    agg.addTrip(
                            trip.optDouble("distanceMeters", 0d),
                            trip.optLong("durationMs", 0L),
                            maxSpeed,
                            trip.optBoolean("hasRoute", false),
                            trip.optLong("startedAtMs", 0L));
                }
            }
            payload.put("tripCount", agg.tripCount);
            payload.put("totalDistanceMeters", agg.totalDistance);
            payload.put("totalDriveMs", agg.totalDriveMs);
            payload.put("longestTripMeters", agg.longestTrip);
            payload.put(
                    "avgTripDistanceMeters",
                    agg.tripCount > 0 ? agg.totalDistance / agg.tripCount : 0d);
            payload.put("maxSpeedKph", agg.maxSpeed);
            payload.put("gpsTripCount", agg.gpsTripCount);
            payload.put("firstTripAtMs", agg.firstAt);
            payload.put("lastTripAtMs", agg.lastAt);
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

    double totalDistanceMeters() throws JSONException {
        SQLiteDatabase db = helper.getWritableDatabase();
        List<ObdSessionRecord> active = ensureRollupsAndCollectActive(db);
        double total = 0d;
        // Cached rollups cover every closed OBD session (counted 0 and 1 — a failed connection
        // still
        // contributes its route distance, matching the old all-sessions sum).
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT SUM(distance_m) FROM " + VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS,
                        null)) {
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                total += cursor.getDouble(0);
            }
        }
        for (ObdSessionRecord session : active) {
            total +=
                    distanceMeters(
                            ObdStoreRouteProjection.routePointsForSessionJson(
                                    db, session.id, 1000));
        }
        return total;
    }

    /**
     * Refreshes the per-session rollup cache for every closed OBD session and returns the active
     * OBD sessions. Active sessions are recomputed live on each read since their data is still
     * growing, so they are never cached.
     */
    private List<ObdSessionRecord> ensureRollupsAndCollectActive(SQLiteDatabase db) {
        List<ObdSessionRecord> active = new ArrayList<>();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT s.* FROM "
                                + VoltTrackerDb.TABLE_SESSIONS
                                + " s LEFT JOIN "
                                + VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS
                                + " r ON r.session_id = s._id WHERE s.mode = ? AND "
                                + "(s.ended_at_ms <= 0 OR r.session_id IS NULL"
                                + " OR r.rollup_version < ?) ORDER BY s.started_at_ms DESC",
                        new String[] {
                            ObdLocalStore.MODE_OBD, String.valueOf(ROLLUP_CACHE_VERSION)
                        })) {
            while (cursor.moveToNext()) {
                ObdSessionRecord session = ObdStoreSupport.readSession(cursor);
                if (session.endedAtMs <= 0) {
                    active.add(session);
                } else {
                    insertRollup(db, session);
                }
            }
        }
        return active;
    }

    /** Computes and caches the rollup scalar for one closed session, reusing the tripJson math. */
    private void insertRollup(SQLiteDatabase db, ObdSessionRecord session) {
        double distance;
        boolean hasRoute;
        Integer maxSpeed;
        long durationMs;
        boolean counted;
        try {
            List<JSONObject> trips = tripJsons(db, session);
            counted = !trips.isEmpty();
            if (counted) {
                distance = 0d;
                durationMs = 0L;
                hasRoute = false;
                maxSpeed = null;
                for (JSONObject trip : trips) {
                    distance += trip.optDouble("distanceMeters", 0d);
                    hasRoute = hasRoute || trip.optBoolean("hasRoute", false);
                    Integer tripMax =
                            trip.isNull("maxSpeedKph") ? null : trip.optInt("maxSpeedKph");
                    if (tripMax != null) {
                        maxSpeed = maxSpeed == null ? tripMax : Math.max(maxSpeed, tripMax);
                    }
                    durationMs += trip.optLong("durationMs", 0L);
                }
            } else {
                // Failed connection (no useful telemetry): not a counted trip, but the storage
                // summary still sums its route distance, so compute that once here.
                JSONArray points =
                        ObdStoreRouteProjection.routePointsForSessionJson(db, session.id, 1000);
                distance = distanceMeters(points);
                hasRoute = points.length() >= 2;
                maxSpeed = null;
                durationMs = 0L;
            }
        } catch (JSONException ex) {
            return; // local numeric values are safe; just skip caching this one on the unexpected
        }
        ContentValues values = new ContentValues();
        values.put("session_id", session.id);
        values.put("counted", counted ? 1 : 0);
        values.put("distance_m", distance);
        values.put("duration_ms", durationMs);
        if (maxSpeed == null) {
            values.putNull("max_speed_kph");
        } else {
            values.put("max_speed_kph", maxSpeed);
        }
        values.put("has_route", hasRoute ? 1 : 0);
        values.put("started_at_ms", session.startedAtMs);
        values.put("rollup_version", ROLLUP_CACHE_VERSION);
        db.insertWithOnConflict(
                VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Mutable accumulator mirroring the original insightsJson per-trip folding exactly. */
    private static final class TripAggregate {
        int tripCount;
        double totalDistance;
        long totalDriveMs;
        double longestTrip;
        int maxSpeed;
        int gpsTripCount;
        long firstAt;
        long lastAt;

        void addTrip(
                double distance,
                long durationMs,
                Integer maxSpeedKph,
                boolean hasRoute,
                long startedAt) {
            tripCount += 1;
            totalDistance += distance;
            longestTrip = Math.max(longestTrip, distance);
            totalDriveMs += durationMs;
            if (maxSpeedKph != null) {
                maxSpeed = Math.max(maxSpeed, maxSpeedKph);
            }
            if (hasRoute) {
                gpsTripCount += 1;
            }
            if (startedAt > 0) {
                firstAt = firstAt == 0 ? startedAt : Math.min(firstAt, startedAt);
                lastAt = Math.max(lastAt, startedAt);
            }
        }
    }
}
