package com.volttracker.obdpoc.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Collections

/**
 * Trip, route and per-session review projections.
 */
class ObdStoreTrips(
    private val helper: VoltTrackerDb,
) {
    private val activeTripCache = HashMap<String, CachedTrip>()

    fun tripsJson(limit: Int): JSONArray {
        val payload = JSONArray()
        // Writable: ensureRollupsAndCollectActive backfills the rollup + trip-list caches.
        val db = helper.writableDatabase
        try {
            val allTrips = ArrayList<JSONObject>()
            // Finalized sessions are served from trip_list_cache (top-N by recency, no per-session
            // recomputation). Active (not-yet-finalized) sessions aren't cached, so compute them
            // live — there are only a handful in flight, so this stays bounded.
            val active = ensureRollupsAndCollectActive(db)
            for (session in active) {
                allTrips.addAll(tripJsons(db, session))
            }
            db
                .rawQuery(
                    "SELECT trip_json FROM ${VoltTrackerDb.TABLE_TRIP_LIST_CACHE}" +
                        " ORDER BY ended_at_ms DESC LIMIT ?",
                    arrayOf(limit.toString()),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        allTrips.add(JSONObject(cursor.getString(0)))
                    }
                }
            Collections.sort(allTrips) { left, right ->
                java.lang.Long.compare(right.optLong("endedAtMs", 0L), left.optLong("endedAtMs", 0L))
            }
            var i = 0
            while (i < allTrips.size && payload.length() < limit) {
                payload.put(allTrips[i])
                i++
            }
        } catch (ignored: JSONException) {
            // Local numeric/string values are safe.
        }
        return payload
    }

    @Throws(JSONException::class)
    private fun tripJsons(
        db: SQLiteDatabase,
        session: ObdSessionRecord,
    ): List<JSONObject> {
        val trips = ArrayList<JSONObject>()
        val hiddenRouteKeys = ObdTripExclusions.hiddenRouteKeys(db, listOf(session.id))
        for (window in DriveWindowDetector.windowsForSession(db, session)) {
            val trip = tripJson(db, session, window, hiddenRouteKeys)
            if (trip != null) {
                trips.add(trip)
            }
        }
        return trips
    }

    @Throws(JSONException::class)
    private fun tripJson(
        db: SQLiteDatabase,
        session: ObdSessionRecord,
        window: DriveWindowDetector.DriveWindow,
        hiddenRouteKeys: Set<String>,
    ): JSONObject? {
        if (hiddenRouteKeys.contains(window.routeKey())) {
            return null
        }
        val usefulSamples =
            ObdStoreSupport.countRowsWhere(
                db,
                VoltTrackerDb.TABLE_TELEMETRY,
                "session_id = ? AND captured_at_ms >= ? AND captured_at_ms <= ? " +
                    "AND ${ObdStoreSupport.USEFUL_TELEMETRY_WHERE}",
                arrayOf(session.id.toString(), window.startedAtMs.toString(), window.endedAtMs.toString()),
            )
        if (usefulSamples <= 0) {
            return null
        }
        val cacheKey = activeTripCacheKey(session, window, usefulSamples)
        val now = System.currentTimeMillis()
        val cached = activeTripCache[cacheKey]
        if (cached != null && now - cached.createdAtMs <= ACTIVE_TRIP_CACHE_TTL_MS) {
            return JSONObject(cached.json)
        }
        val points =
            ObdStoreRouteProjection.routePointsForSessionJson(
                db,
                session.id,
                1000,
                window.startedAtMs,
                window.endedAtMs,
            )
        var startedAtMs = window.startedAtMs
        var endedAtMs = window.endedAtMs
        if (points.length() > 0) {
            startedAtMs = points.getJSONObject(0).optLong("atMs", startedAtMs)
            endedAtMs = points.getJSONObject(points.length() - 1).optLong("atMs", endedAtMs)
        }
        val distanceMeters = ObdStoreSupport.distanceMeters(points)
        val maxSpeed = maxIntForWindowBoxed(db, "speed_kph", session.id, window)
        if (!ObdSessionClassifier.isMeaningfulTrip(points.length(), distanceMeters, maxSpeed)) {
            return null
        }
        val routeKey = "${session.id}:$startedAtMs:$endedAtMs"
        if (hiddenRouteKeys.contains(routeKey)) {
            return null
        }
        val trip = JSONObject()
        trip.put("id", routeKey)
        trip.put("sessionId", session.id)
        trip.put("segmentIndex", window.index)
        trip.put("startedAtMs", startedAtMs)
        trip.put("endedAtMs", endedAtMs)
        trip.put("durationMs", maxOf(0L, endedAtMs - startedAtMs))
        trip.put("distanceMeters", distanceMeters)
        trip.put("maxSpeedKph", maxSpeed ?: JSONObject.NULL)
        trip.put("avgMovingSpeedKph", avgMovingSpeedKph(db, session.id, window))
        trip.put("sampleCount", usefulSamples)
        trip.put("pointCount", points.length())
        trip.put("hasRoute", points.length() >= 2)
        trip.put("adapterName", session.adapterName)
        trip.put("status", session.status)
        if (session.endedAtMs <= 0) {
            activeTripCache[cacheKey] = CachedTrip(trip.toString(), now)
            pruneActiveTripCache(now, session.id)
        }
        return trip
    }

    fun insightsJson(): JSONObject {
        val payload = JSONObject()
        val db = helper.writableDatabase
        try {
            val active = ensureRollupsAndCollectActive(db)
            val agg = TripAggregate()
            db
                .query(
                    VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS,
                    arrayOf("distance_m", "duration_ms", "max_speed_kph", "has_route", "started_at_ms"),
                    "counted = 1",
                    null,
                    null,
                    null,
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val maxSpeed = if (cursor.isNull(2)) null else cursor.getInt(2)
                        agg.addTrip(
                            cursor.getDouble(0),
                            cursor.getLong(1),
                            maxSpeed,
                            cursor.getInt(3) != 0,
                            cursor.getLong(4),
                        )
                    }
                }
            for (session in active) {
                for (trip in tripJsons(db, session)) {
                    val maxSpeed = if (trip.isNull("maxSpeedKph")) null else trip.optInt("maxSpeedKph")
                    agg.addTrip(
                        trip.optDouble("distanceMeters", 0.0),
                        trip.optLong("durationMs", 0L),
                        maxSpeed,
                        trip.optBoolean("hasRoute", false),
                        trip.optLong("startedAtMs", 0L),
                    )
                }
            }
            payload.put("tripCount", agg.tripCount)
            payload.put("totalDistanceMeters", agg.totalDistance)
            payload.put("totalDriveMs", agg.totalDriveMs)
            payload.put("longestTripMeters", agg.longestTrip)
            payload.put("avgTripDistanceMeters", if (agg.tripCount > 0) agg.totalDistance / agg.tripCount else 0.0)
            payload.put("maxSpeedKph", agg.maxSpeed)
            payload.put("gpsTripCount", agg.gpsTripCount)
            payload.put("firstTripAtMs", agg.firstAt)
            payload.put("lastTripAtMs", agg.lastAt)
            payload.put("sessionCount", ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_SESSIONS))
            payload.put(
                "sampleCount",
                ObdStoreSupport.countRowsWhere(
                    db,
                    VoltTrackerDb.TABLE_TELEMETRY,
                    ObdStoreSupport.USEFUL_TELEMETRY_WHERE,
                    null,
                ),
            )
            payload.put("locationSampleCount", ObdStoreSupport.countRows(db, VoltTrackerDb.TABLE_LOCATION_SAMPLES))
        } catch (ignored: JSONException) {
            // Local numeric/string values are safe.
        }
        return payload
    }

    @Throws(JSONException::class)
    fun totalDistanceMeters(): Double {
        val db = helper.writableDatabase
        val active = ensureRollupsAndCollectActive(db)
        var total = 0.0
        db
            .rawQuery(
                "SELECT SUM(distance_m) FROM ${VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS} WHERE counted = 1",
                null,
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    total += cursor.getDouble(0)
                }
            }
        for (session in active) {
            for (trip in tripJsons(db, session)) {
                total += trip.optDouble("distanceMeters", 0.0)
            }
        }
        return total
    }

    fun invalidateSessionTripCache(sessionId: Long) {
        val db = helper.writableDatabase
        db.delete(VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS, "session_id = ?", arrayOf(sessionId.toString()))
        db.delete(VoltTrackerDb.TABLE_TRIP_LIST_CACHE, "session_id = ?", arrayOf(sessionId.toString()))
    }

    private fun ensureRollupsAndCollectActive(db: SQLiteDatabase): List<ObdSessionRecord> {
        val active = ArrayList<ObdSessionRecord>()
        db
            .rawQuery(
                "SELECT s.* FROM ${VoltTrackerDb.TABLE_SESSIONS} s " +
                    "LEFT JOIN ${VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS}" +
                    " r ON r.session_id = s._id WHERE s.mode = ? AND (s.ended_at_ms <= 0 OR r.session_id IS NULL" +
                    " OR r.rollup_version < ?) ORDER BY s.started_at_ms DESC",
                arrayOf(ObdLocalStore.MODE_OBD, ROLLUP_CACHE_VERSION.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val session = ObdStoreSupport.readSession(cursor)
                    if (session.endedAtMs <= 0) {
                        active.add(session)
                    } else {
                        insertRollup(db, session)
                    }
                }
            }
        return active
    }

    private fun insertRollup(
        db: SQLiteDatabase,
        session: ObdSessionRecord,
    ) {
        val distance: Double
        val hasRoute: Boolean
        val maxSpeed: Int?
        val durationMs: Long
        val counted: Boolean
        try {
            val trips = tripJsons(db, session)
            writeTripListCache(db, session.id, trips)
            counted = trips.isNotEmpty()
            if (counted) {
                var computedDistance = 0.0
                var computedDurationMs = 0L
                var computedHasRoute = false
                var computedMaxSpeed: Int? = null
                for (trip in trips) {
                    computedDistance += trip.optDouble("distanceMeters", 0.0)
                    computedHasRoute = computedHasRoute || trip.optBoolean("hasRoute", false)
                    val tripMax = if (trip.isNull("maxSpeedKph")) null else trip.optInt("maxSpeedKph")
                    if (tripMax != null) {
                        computedMaxSpeed = if (computedMaxSpeed == null) tripMax else maxOf(computedMaxSpeed, tripMax)
                    }
                    computedDurationMs += trip.optLong("durationMs", 0L)
                }
                distance = computedDistance
                durationMs = computedDurationMs
                hasRoute = computedHasRoute
                maxSpeed = computedMaxSpeed
            } else {
                val points = ObdStoreRouteProjection.routePointsForSessionJson(db, session.id, 1000)
                distance = ObdStoreSupport.distanceMeters(points)
                hasRoute = points.length() >= 2
                maxSpeed = null
                durationMs = 0L
            }
        } catch (ex: JSONException) {
            return
        }
        val values = ContentValues()
        values.put("session_id", session.id)
        values.put("counted", if (counted) 1 else 0)
        values.put("distance_m", distance)
        values.put("duration_ms", durationMs)
        if (maxSpeed == null) {
            values.putNull("max_speed_kph")
        } else {
            values.put("max_speed_kph", maxSpeed)
        }
        values.put("has_route", if (hasRoute) 1 else 0)
        values.put("started_at_ms", session.startedAtMs)
        values.put("rollup_version", ROLLUP_CACHE_VERSION)
        db.insertWithOnConflict(VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Replaces this session's cached per-window trip rows with the freshly computed [trips]. Stores
     * the exact trip JSON the list renders, so getTripsJson reads it back verbatim. Called from
     * insertRollup, so it rebuilds whenever ROLLUP_CACHE_VERSION bumps or a session finalizes.
     */
    private fun writeTripListCache(
        db: SQLiteDatabase,
        sessionId: Long,
        trips: List<JSONObject>,
    ) {
        db.delete(VoltTrackerDb.TABLE_TRIP_LIST_CACHE, "session_id = ?", arrayOf(sessionId.toString()))
        for (trip in trips) {
            val values = ContentValues()
            values.put("session_id", sessionId)
            values.put("ended_at_ms", trip.optLong("endedAtMs", 0L))
            values.put("rollup_version", ROLLUP_CACHE_VERSION)
            values.put("trip_json", trip.toString())
            db.insert(VoltTrackerDb.TABLE_TRIP_LIST_CACHE, null, values)
        }
    }

    private class TripAggregate {
        var tripCount = 0
        var totalDistance = 0.0
        var totalDriveMs = 0L
        var longestTrip = 0.0
        var maxSpeed = 0
        var gpsTripCount = 0
        var firstAt = 0L
        var lastAt = 0L

        fun addTrip(
            distance: Double,
            durationMs: Long,
            maxSpeedKph: Int?,
            hasRoute: Boolean,
            startedAt: Long,
        ) {
            tripCount += 1
            totalDistance += distance
            longestTrip = maxOf(longestTrip, distance)
            totalDriveMs += durationMs
            if (maxSpeedKph != null) {
                maxSpeed = maxOf(maxSpeed, maxSpeedKph)
            }
            if (hasRoute) {
                gpsTripCount += 1
            }
            if (startedAt > 0) {
                firstAt = if (firstAt == 0L) startedAt else minOf(firstAt, startedAt)
                lastAt = maxOf(lastAt, startedAt)
            }
        }
    }

    private class CachedTrip(
        val json: String,
        val createdAtMs: Long,
    )

    private fun activeTripCacheKey(
        session: ObdSessionRecord,
        window: DriveWindowDetector.DriveWindow,
        usefulSamples: Long,
    ): String = "${session.id}:${window.startedAtMs}:${window.endedAtMs}:$usefulSamples"

    private fun pruneActiveTripCache(
        now: Long,
        sessionId: Long,
    ) {
        // Expired entries are never read again (the TTL check on lookup skips them), so collect
        // them here; the prefix prune alone would let stale entries from other sessions linger.
        val entries = activeTripCache.entries.iterator()
        while (entries.hasNext()) {
            if (now - entries.next().value.createdAtMs > ACTIVE_TRIP_CACHE_TTL_MS) {
                entries.remove()
            }
        }
        if (activeTripCache.size <= ACTIVE_TRIP_CACHE_MAX_ENTRIES) {
            return
        }
        val prefix = "$sessionId:"
        val iterator = activeTripCache.keys.iterator()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (key.startsWith(prefix)) {
                iterator.remove()
            }
        }
    }

    companion object {
        // Bump to invalidate cached rollups + the trip-list cache (forces a one-time rebuild on
        // the next read). v5 keeps stationary GPS drift and manual hides out of trip/map totals.
        private const val ROLLUP_CACHE_VERSION = 5
        private const val ACTIVE_TRIP_CACHE_TTL_MS = 2_000L
        private const val ACTIVE_TRIP_CACHE_MAX_ENTRIES = 64

        private fun maxIntForWindowBoxed(
            db: SQLiteDatabase,
            column: String,
            sessionId: Long,
            window: DriveWindowDetector.DriveWindow,
        ): Int? =
            db
                .rawQuery(
                    "SELECT MAX($column) FROM ${VoltTrackerDb.TABLE_TELEMETRY} " +
                        "WHERE session_id = ? AND captured_at_ms >= ?" +
                        " AND captured_at_ms <= ? AND $column IS NOT NULL",
                    arrayOf(sessionId.toString(), window.startedAtMs.toString(), window.endedAtMs.toString()),
                ).use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else null
                }

        private fun avgMovingSpeedKph(
            db: SQLiteDatabase,
            sessionId: Long,
            window: DriveWindowDetector.DriveWindow,
        ): Double =
            db
                .rawQuery(
                    "SELECT AVG(speed_kph) FROM ${VoltTrackerDb.TABLE_TELEMETRY} " +
                        "WHERE session_id = ? AND captured_at_ms >= ?" +
                        " AND captured_at_ms <= ? AND speed_kph > 0 AND ${ObdStoreSupport.USEFUL_TELEMETRY_WHERE}",
                    arrayOf(sessionId.toString(), window.startedAtMs.toString(), window.endedAtMs.toString()),
                ).use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getDouble(0) else 0.0
                }
    }
}
