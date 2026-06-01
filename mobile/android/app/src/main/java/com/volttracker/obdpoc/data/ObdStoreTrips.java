package com.volttracker.obdpoc.data;

import static com.volttracker.obdpoc.data.ObdStoreSupport.USEFUL_TELEMETRY_WHERE;
import static com.volttracker.obdpoc.data.ObdStoreSupport.countRows;
import static com.volttracker.obdpoc.data.ObdStoreSupport.countRowsWhere;
import static com.volttracker.obdpoc.data.ObdStoreSupport.distanceMeters;
import static com.volttracker.obdpoc.data.ObdStoreSupport.getAllSessions;
import static com.volttracker.obdpoc.data.ObdStoreSupport.getRecentSessions;
import static com.volttracker.obdpoc.data.ObdStoreSupport.maxIntForSessionBoxed;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        JSONArray points = ObdStoreRouteProjection.routePointsForSessionJson(db, session.id, 1000);
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
        SQLiteDatabase db = helper.getWritableDatabase();
        try {
            // O(1) steady state: closed sessions are read from the cached per-session rollup (one
            // scalar query, no GPS walk); only the at-most-one open session is computed live. The
            // folding below is byte-for-byte the same one-session-=-one-trip math the old
            // per-session
            // loop did, so the published numbers are unchanged.
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
                JSONObject trip = tripJson(db, session);
                if (trip == null) {
                    continue;
                }
                Integer maxSpeed = trip.isNull("maxSpeedKph") ? null : trip.optInt("maxSpeedKph");
                agg.addTrip(
                        trip.optDouble("distanceMeters", 0d),
                        trip.optLong("durationMs", 0L),
                        maxSpeed,
                        trip.optBoolean("hasRoute", false),
                        trip.optLong("startedAtMs", 0L));
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
     * Backfills the per-session rollup cache for every closed OBD session that lacks a row, and
     * returns the open (active) OBD sessions. Active sessions are recomputed live on each read
     * since their data is still growing, so they are never cached. Steady state this inserts
     * nothing and returns at most one session — the bulk of the work moves into a single scalar
     * query by the callers. The first read after an upgrade backfills the whole history once.
     */
    private List<ObdSessionRecord> ensureRollupsAndCollectActive(SQLiteDatabase db) {
        Set<Long> rolled = new HashSet<>();
        try (Cursor cursor =
                db.query(
                        VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS,
                        new String[] {"session_id"},
                        null,
                        null,
                        null,
                        null,
                        null)) {
            while (cursor.moveToNext()) {
                rolled.add(cursor.getLong(0));
            }
        }
        List<ObdSessionRecord> active = new ArrayList<>();
        for (ObdSessionRecord session : getAllSessions(db)) {
            if (!ObdLocalStore.MODE_OBD.equals(session.mode)) {
                continue;
            }
            if (session.endedAtMs <= 0) {
                active.add(session);
            } else if (!rolled.contains(session.id)) {
                insertRollup(db, session);
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
            JSONObject trip = tripJson(db, session);
            counted = trip != null;
            if (counted) {
                distance = trip.optDouble("distanceMeters", 0d);
                hasRoute = trip.optBoolean("hasRoute", false);
                maxSpeed = trip.isNull("maxSpeedKph") ? null : trip.optInt("maxSpeedKph");
                durationMs = trip.optLong("durationMs", 0L);
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
        db.insertWithOnConflict(
                VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS,
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE);
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
