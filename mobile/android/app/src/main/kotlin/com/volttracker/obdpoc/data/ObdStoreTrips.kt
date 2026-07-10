package com.volttracker.obdpoc.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.core.database.sqlite.transaction
import com.volttracker.obdpoc.materialize.PackEnergyMath
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
    /**
     * Reached concurrently from the WebView JS-bridge thread (getTrips/getInsights) and the OBD
     * polling thread (storage summary -> totalDistanceMeters), so every read/write/iteration must
     * hold the map's monitor.
     */
    private val activeTripCache = HashMap<String, CachedTrip>()

    fun tripsJson(limit: Int): JSONArray = tripsJson(limit, 0)

    fun tripsJson(
        limit: Int,
        offset: Int,
    ): JSONArray {
        val payload = JSONArray()
        // Writable: ensureRollupsAndCollectActive backfills the rollup + trip-list caches.
        val db = helper.writableDatabase
        try {
            val allTrips = ArrayList<JSONObject>()
            // Finalized sessions are served from trip_list_cache (top-N by recency, no per-session
            // recomputation). Active (not-yet-finalized) sessions aren't cached, so compute them
            // live — there are only a handful in flight, so this stays bounded.
            val safeOffset = maxOf(0, offset)
            val safeLimit = maxOf(1, limit)
            val active = ensureRollupsAndCollectActive(db, safeOffset + safeLimit)
            for (session in active) {
                allTrips.addAll(tripJsons(db, session))
            }
            db
                .rawQuery(
                    "SELECT trip_json FROM ${VoltTrackerDb.TABLE_TRIP_LIST_CACHE}" +
                        " ORDER BY ended_at_ms DESC LIMIT ?",
                    arrayOf((safeOffset + safeLimit).toString()),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val parsed =
                            try {
                                JSONObject(cursor.getString(0))
                            } catch (ex: JSONException) {
                                Log.w(TAG, "skipping corrupt cached trip_json", ex)
                                continue
                            }
                        allTrips.add(parsed)
                    }
                }
            Collections.sort(allTrips) { left, right ->
                java.lang.Long.compare(right.optLong("endedAtMs", 0L), left.optLong("endedAtMs", 0L))
            }
            applyLabels(db, allTrips)
            var i = safeOffset
            while (i < allTrips.size && payload.length() < safeLimit) {
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
        val cached = synchronized(activeTripCache) { activeTripCache[cacheKey] }
        if (cached != null && now - cached.createdAtMs <= ACTIVE_TRIP_CACHE_TTL_MS) {
            return JSONObject(cached.json)
        }
        val points =
            ObdStoreRouteProjection.routePointsForSessionJson(
                db,
                session.id,
                ObdStoreRouteProjection.MAX_TRACK_POINTS,
                window.startedAtMs,
                window.endedAtMs,
            )
        var startedAtMs = window.startedAtMs
        var endedAtMs = window.endedAtMs
        if (points.length() > 0) {
            startedAtMs = points.getJSONObject(0).optLong("atMs", startedAtMs)
            endedAtMs = points.getJSONObject(points.length() - 1).optLong("atMs", endedAtMs)
        }
        val distanceMeters =
            ObdStoreRouteProjection.rawRouteDistanceMeters(
                db,
                session.id,
                window.startedAtMs,
                window.endedAtMs,
            )
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
        trip.put("energyKwh", energyKwhForWindowBoxed(db, session.id, window) ?: JSONObject.NULL)
        trip.put("evShare", evDrivingShareBoxed(db, session.id, window) ?: JSONObject.NULL)
        trip.put("avgOutsideTempC", avgOutsideTempCBoxed(db, session.id, window) ?: JSONObject.NULL)
        trip.put("adapterName", session.adapterName)
        trip.put("status", session.status)
        if (session.endedAtMs <= 0) {
            synchronized(activeTripCache) {
                activeTripCache[cacheKey] = CachedTrip(trip.toString(), now)
                pruneActiveTripCache(now, session.id, cacheKey)
            }
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
            val activeTrips = ArrayList<JSONObject>()
            for (session in active) {
                for (trip in tripJsons(db, session)) {
                    activeTrips.add(trip)
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
            payload.put("electricDrivingPct", lifetimeElectricDrivingPctBoxed(db, activeTrips) ?: JSONObject.NULL)
            val lifetimeEnergy = lifetimeEnergyAggregate(db, activeTrips)
            payload.put("loggedEnergyKwh", lifetimeEnergy.energyKwh)
            payload.put("loggedEnergyDistanceMeters", lifetimeEnergy.distanceMeters)
        } catch (ignored: JSONException) {
            // Local numeric/string values are safe.
        }
        return payload
    }

    /** Whole-history energy coverage, independent of the bounded recent-trip dashboard payload. */
    private fun lifetimeEnergyAggregate(
        db: SQLiteDatabase,
        activeTrips: List<JSONObject>,
    ): LifetimeEnergyAggregate {
        val aggregate = LifetimeEnergyAggregate()
        db
            .rawQuery("SELECT trip_json FROM ${VoltTrackerDb.TABLE_TRIP_LIST_CACHE}", null)
            .use { cursor ->
                while (cursor.moveToNext()) {
                    try {
                        aggregate.add(JSONObject(cursor.getString(0)))
                    } catch (ex: JSONException) {
                        Log.w(TAG, "skipping corrupt cached trip while aggregating lifetime energy", ex)
                    }
                }
            }
        for (trip in activeTrips) aggregate.add(trip)
        return aggregate
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

    /**
     * Stamps each trip with its user label (M4) and favorite flag (M4 favorites half) at read time,
     * so a label/favorite change takes effect on the next read without rebuilding the trip-list
     * cache. Both are keyed by route key and read from the status-event store (one batched query
     * each, not N+1); trips with no stored label keep an empty string and default to not-favorite.
     */
    @Throws(JSONException::class)
    private fun applyLabels(
        db: SQLiteDatabase,
        trips: List<JSONObject>,
    ) {
        if (trips.isEmpty()) {
            return
        }
        val sessionIds = trips.mapNotNull { trip -> trip.optLong("sessionId", -1L).takeIf { it > 0L } }.distinct()
        val labels = ObdTripLabels.labelsByRouteKey(db, sessionIds)
        val favorites = ObdTripFavorites.favoriteRouteKeys(db, sessionIds)
        for (trip in trips) {
            val routeKey = trip.optString("id")
            trip.put("label", labels[routeKey] ?: "")
            trip.put("favorite", favorites.contains(routeKey))
        }
    }

    fun invalidateSessionTripCache(sessionId: Long) {
        val db = helper.writableDatabase
        db.delete(VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS, "session_id = ?", arrayOf(sessionId.toString()))
        db.delete(VoltTrackerDb.TABLE_TRIP_LIST_CACHE, "session_id = ?", arrayOf(sessionId.toString()))
        // G2: hiding a trip changes drive-window detection, which feeds the session's inferred-charge
        // drive/SOC boundaries, so drop its cached charge rollup too (recomputes on the next read).
        db.delete(VoltTrackerDb.TABLE_CHARGE_SESSION_ROLLUPS, "session_id = ?", arrayOf(sessionId.toString()))
    }

    /**
     * Materializes durable trip/list summaries for finalized sessions whose raw rows are about to
     * cross retention. This must run before raw deletion: otherwise a drive that was never opened
     * in the dashboard could lose the only rows from which its compact history can be built.
     */
    internal fun materializeFinalizedSessionsBefore(cutoffMs: Long) {
        val db = helper.writableDatabase
        db
            .rawQuery(
                "SELECT s.* FROM ${VoltTrackerDb.TABLE_SESSIONS} s " +
                    "LEFT JOIN ${VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS} r ON r.session_id = s._id " +
                    "WHERE s.mode = ? AND s.ended_at_ms > 0 AND s.ended_at_ms < ? " +
                    "AND (r.session_id IS NULL OR r.rollup_version < ?) ORDER BY s.started_at_ms ASC",
                arrayOf(ObdLocalStore.MODE_OBD, cutoffMs.toString(), ROLLUP_CACHE_VERSION.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    insertRollup(db, ObdStoreSupport.readSession(cursor))
                }
            }
    }

    internal fun materializeFinalizedSession(sessionId: Long) {
        val db = helper.writableDatabase
        db
            .rawQuery(
                "SELECT * FROM ${VoltTrackerDb.TABLE_SESSIONS} WHERE _id = ? AND ended_at_ms > 0 LIMIT 1",
                arrayOf(sessionId.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) insertRollup(db, ObdStoreSupport.readSession(cursor))
            }
    }

    private fun ensureRollupsAndCollectActive(
        db: SQLiteDatabase,
        maxFinalizedBackfills: Int = Int.MAX_VALUE,
    ): List<ObdSessionRecord> {
        val active = ArrayList<ObdSessionRecord>()
        db
            .rawQuery(
                "SELECT s.* FROM ${VoltTrackerDb.TABLE_SESSIONS} s " +
                    "LEFT JOIN ${VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS}" +
                    " r ON r.session_id = s._id WHERE s.mode = ? AND (s.ended_at_ms <= 0 OR r.session_id IS NULL" +
                    " OR r.rollup_version < ?) ORDER BY s.started_at_ms DESC LIMIT ?",
                arrayOf(
                    ObdLocalStore.MODE_OBD,
                    ROLLUP_CACHE_VERSION.toString(),
                    maxOf(1, maxFinalizedBackfills).toString(),
                ),
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
                val points =
                    ObdStoreRouteProjection.routePointsForSessionJson(
                        db,
                        session.id,
                        ObdStoreRouteProjection.MAX_TRACK_POINTS,
                    )
                distance = ObdStoreSupport.distanceMeters(points)
                hasRoute = points.length() >= 2
                maxSpeed = null
                durationMs = 0L
            }
        } catch (ex: JSONException) {
            // Skip the rollup (it will be retried on the next read), but a parse failure here
            // means a corrupted cached trip/route payload — make it visible instead of silent.
            Log.w(TAG, "skipping trip rollup for session ${session.id}: corrupt trip JSON", ex)
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
        // Atomic delete+insert so a concurrent reader never observes a half-rebuilt cache for
        // this session (and a crash mid-rebuild can't leave it empty).
        db.transaction {
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

    private class LifetimeEnergyAggregate {
        var energyKwh = 0.0
        var distanceMeters = 0.0

        fun add(trip: JSONObject) {
            val energy = if (trip.isNull("energyKwh")) Double.NaN else trip.optDouble("energyKwh", Double.NaN)
            val distance = trip.optDouble("distanceMeters", Double.NaN)
            if (energy.isFinite() && energy > 0.0 && distance.isFinite() && distance > 0.0) {
                energyKwh += energy
                distanceMeters += distance
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

    /** Caller must hold the [activeTripCache] monitor. */
    private fun pruneActiveTripCache(
        now: Long,
        sessionId: Long,
        keepKey: String,
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
            // Keep the entry the caller inserted one line earlier — pruning it would defeat the
            // cache exactly when it is under pressure.
            if (key != keepKey && key.startsWith(prefix)) {
                iterator.remove()
            }
        }
    }

    companion object {
        private const val TAG = "VoltTracker"

        // Bump to invalidate cached rollups + the trip-list cache (forces a one-time rebuild on
        // the next read). v5 keeps stationary GPS drift and manual hides out of trip/map totals.
        // v6 rebuilds pointCount/distanceMeters after route-geometry simplification landed.
        // v7 adds per-trip energyKwh (driving trend) and evShare (EV/gas split).
        // v8 adds per-trip avgOutsideTempC (efficiency-vs-temperature insight).
        private const val ROLLUP_CACHE_VERSION = 8
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

        /**
         * Net HV energy over the trip window in kWh (drive minus regen), trapezoidal over the
         * logged pack power — the same integration [com.volttracker.obdpoc.materialize.TripMaterializer]
         * uses for `trip_segments.energy_kwh`. Null when the window has no usable power samples
         * (adapter without HV PIDs, GPS-only logging).
         */
        private fun energyKwhForWindowBoxed(
            db: SQLiteDatabase,
            sessionId: Long,
            window: DriveWindowDetector.DriveWindow,
        ): Double? =
            db
                .rawQuery(
                    "SELECT captured_at_ms, power_kw, pack_voltage, pack_current_a " +
                        "FROM ${VoltTrackerDb.TABLE_TELEMETRY} " +
                        "WHERE session_id = ? AND captured_at_ms >= ? AND captured_at_ms <= ? " +
                        "AND (power_kw IS NOT NULL OR (pack_voltage IS NOT NULL AND pack_current_a IS NOT NULL)) " +
                        "ORDER BY captured_at_ms ASC",
                    arrayOf(sessionId.toString(), window.startedAtMs.toString(), window.endedAtMs.toString()),
                ).use { cursor ->
                    var prevPowerKw: Double? = null
                    var prevAtMs = 0L
                    var energyKwh = 0.0
                    var integrated = 0
                    while (cursor.moveToNext()) {
                        val atMs = cursor.getLong(0)
                        val powerKw =
                            if (!cursor.isNull(1)) {
                                cursor.getDouble(1)
                            } else {
                                PackEnergyMath.dischargePowerKw(cursor.getDouble(2), cursor.getDouble(3))
                                    ?: continue
                            }
                        val previousPowerKw = prevPowerKw
                        if (previousPowerKw != null) {
                            val segmentKwh =
                                PackEnergyMath.trapezoidKwh(previousPowerKw, powerKw, prevAtMs, atMs)
                            if (segmentKwh != null) {
                                energyKwh += segmentKwh
                                integrated += 1
                            }
                        }
                        prevPowerKw = powerKw
                        prevAtMs = atMs
                    }
                    if (integrated == 0) null else energyKwh
                }

        /**
         * Share (0..1) of this trip's driving done on electric, weighted by speed (a per-sample
         * distance proxy) over the classified `driving_ev` vs `driving_gas` samples. The polling
         * cadence is constant within a session, so sample weighting is equivalent to time
         * weighting here — cadence differences only matter ACROSS sessions, which the lifetime
         * aggregate handles by distance-weighting per-trip shares instead. Null when the window
         * has no classified moving samples.
         */
        private fun evDrivingShareBoxed(
            db: SQLiteDatabase,
            sessionId: Long,
            window: DriveWindowDetector.DriveWindow,
        ): Double? =
            evShareFromStateSums(
                db,
                "SELECT vehicle_state, SUM(speed_kph) FROM ${VoltTrackerDb.TABLE_TELEMETRY} " +
                    "WHERE session_id = ? AND captured_at_ms >= ? AND captured_at_ms <= ? " +
                    "AND speed_kph > 0 AND vehicle_state IN ('driving_ev', 'driving_gas') " +
                    "GROUP BY vehicle_state",
                arrayOf(sessionId.toString(), window.startedAtMs.toString(), window.endedAtMs.toString()),
            )

        /**
         * Lifetime "% of driving on electric" (0..100): the distance-weighted average of each
         * trip's evShare, read from the cached trip JSON plus any still-active trips. Weighting
         * by trip distance (rather than summing raw samples across sessions) keeps sessions with
         * faster OBD polling cadences from dominating the aggregate. Null until any classified
         * trip exists.
         */
        private fun lifetimeElectricDrivingPctBoxed(
            db: SQLiteDatabase,
            activeTrips: List<JSONObject>,
        ): Double? {
            var evMeters = 0.0
            var totalMeters = 0.0

            fun accumulate(trip: JSONObject) {
                if (trip.isNull("evShare")) {
                    return
                }
                val meters = trip.optDouble("distanceMeters", 0.0)
                if (meters <= 0.0) {
                    return
                }
                evMeters += trip.optDouble("evShare", 0.0) * meters
                totalMeters += meters
            }
            db
                .rawQuery("SELECT trip_json FROM ${VoltTrackerDb.TABLE_TRIP_LIST_CACHE}", null)
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        val trip =
                            try {
                                JSONObject(cursor.getString(0))
                            } catch (ignored: JSONException) {
                                continue
                            }
                        accumulate(trip)
                    }
                }
            activeTrips.forEach(::accumulate)
            return if (totalMeters > 0.0) evMeters / totalMeters * 100.0 else null
        }

        /**
         * Average outside air temperature over the trip window in deg C, or null when no sample
         * carried one. The ambient PID lives only in each telemetry row's JSON blob (no dedicated
         * column), so this scans the window's json values with a cheap key-scoped extraction
         * instead of a full JSONObject parse per row. Runs once per session at rollup-cache build.
         */
        private fun avgOutsideTempCBoxed(
            db: SQLiteDatabase,
            sessionId: Long,
            window: DriveWindowDetector.DriveWindow,
        ): Double? =
            db
                .rawQuery(
                    "SELECT json FROM ${VoltTrackerDb.TABLE_TELEMETRY} " +
                        "WHERE session_id = ? AND captured_at_ms >= ? AND captured_at_ms <= ? " +
                        "AND json LIKE '%\"outsideTempC\":%'",
                    arrayOf(sessionId.toString(), window.startedAtMs.toString(), window.endedAtMs.toString()),
                ).use { cursor ->
                    var sum = 0.0
                    var count = 0
                    while (cursor.moveToNext()) {
                        val temp = extractJsonNumber(cursor.getString(0), "\"outsideTempC\":") ?: continue
                        // The decoder already bounds the PID to a plausible OAT range; this guard
                        // only drops corrupt blobs.
                        if (temp < -60.0 || temp > 70.0) continue
                        sum += temp
                        count += 1
                    }
                    if (count > 0) sum / count else null
                }

        /** Reads the number right after [marker] in [json]; null for non-numeric values. */
        private fun extractJsonNumber(
            json: String?,
            marker: String,
        ): Double? {
            if (json == null) {
                return null
            }
            val start = json.indexOf(marker).takeIf { it >= 0 }?.plus(marker.length) ?: return null
            var end = start
            while (end < json.length && (json[end].isDigit() || json[end] in "+-.eE")) {
                end += 1
            }
            return json.substring(start, end).toDoubleOrNull()
        }

        private fun evShareFromStateSums(
            db: SQLiteDatabase,
            sql: String,
            args: Array<String>?,
        ): Double? =
            db.rawQuery(sql, args).use { cursor ->
                var ev = 0.0
                var gas = 0.0
                while (cursor.moveToNext()) {
                    when (cursor.getString(0)) {
                        "driving_ev" -> ev = cursor.getDouble(1)
                        "driving_gas" -> gas = cursor.getDouble(1)
                    }
                }
                val total = ev + gas
                if (total > 0.0) ev / total else null
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
