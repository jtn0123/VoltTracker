package com.volttracker.obdpoc.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Route and scalar-track projections for dashboard polyline/track renderers.
 */
object ObdStoreRouteProjection {
    const val MAX_TRACK_POINTS: Int = 500

    /** Routes longer than this get a geometry simplification pass before serializing. */
    const val SIMPLIFY_MIN_POINTS: Int = 300

    /** Douglas-Peucker tolerance: detail finer than this is invisible at trip-map zoom levels. */
    const val SIMPLIFY_TOLERANCE_METERS: Double = 12.0

    @JvmStatic
    @Throws(JSONException::class)
    fun routeForSession(
        db: SQLiteDatabase,
        session: ObdSessionRecord,
        limit: Int,
    ): JSONObject = routeForSession(db, session, limit, null, null, null)

    @JvmStatic
    @Throws(JSONException::class)
    fun routeForSession(
        db: SQLiteDatabase,
        session: ObdSessionRecord,
        limit: Int,
        windowStartMs: Long?,
        windowEndMs: Long?,
        routeId: String?,
    ): JSONObject {
        val payload = JSONObject()
        val points = routePointsForSessionJson(db, session.id, limit, windowStartMs, windowEndMs)
        val sessionJson = ObdStoreSupport.sessionToJson(session)
        if (!routeId.isNullOrEmpty()) {
            sessionJson.put("id", routeId)
            sessionJson.put("sessionId", session.id)
        }
        if (windowStartMs != null && windowEndMs != null) {
            sessionJson.put("startedAtMs", windowStartMs)
            sessionJson.put("endedAtMs", windowEndMs)
        }
        payload.put("session", sessionJson)
        payload.put("points", points)
        payload.put("pointCount", points.length())
        payload.put("distanceMeters", rawRouteDistanceMeters(db, session.id, windowStartMs, windowEndMs))
        payload.put("bounds", ObdStoreSupport.boundsFor(points))
        payload.put(
            "socTrack",
            scalarTrackForSessionJson(db, session.id, limit, windowStartMs, windowEndMs, "soc", "soc"),
        )
        payload.put(
            "powerTrack",
            scalarTrackForSessionJson(db, session.id, limit, windowStartMs, windowEndMs, "power_kw", "powerKw"),
        )
        return payload
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun recentRoutes(
        db: SQLiteDatabase,
        sessionLimit: Int,
        pointLimit: Int,
    ): JSONArray {
        val payload = JSONArray()
        val sessions =
            ObdStoreSupport
                .getRecentSessions(db, maxOf(100, sessionLimit))
                .filter { ObdSessionClassifier.isTripSession(db, it) }
        val windowsBySession = DriveWindowDetector.windowsForSessions(db, sessions)
        val hiddenRouteKeys = ObdTripExclusions.hiddenRouteKeys(db, sessions.map { it.id })
        for (session in sessions) {
            for (window in windowsBySession[session.id].orEmpty()) {
                if (hiddenRouteKeys.contains(window.routeKey())) {
                    continue
                }
                val route =
                    routeForSession(db, session, pointLimit, window.startedAtMs, window.endedAtMs, window.routeKey())
                val points = route.optJSONArray("points")
                if (points == null || points.length() < 2) {
                    continue
                }
                // The trip list exposes ids built from the point-clipped start/end (which differ
                // from the window bounds for post-split windows), so a trip hidden by its list id
                // must also be honored here or it would stay visible on the map.
                if (hiddenRouteKeys.contains(clippedRouteKey(session.id, points, window))) {
                    continue
                }
                if (!ObdSessionClassifier.isMeaningfulTrip(
                        points.length(),
                        route.optDouble("distanceMeters", 0.0),
                        ObdSessionClassifier.maxSpeedKphForWindow(db, session.id, window),
                    )
                ) {
                    continue
                }
                payload.put(route)
                if (payload.length() >= sessionLimit) {
                    return payload
                }
            }
        }
        return payload
    }

    /**
     * Route key in the same point-clipped form `ObdStoreTrips.tripJson` uses for the trip-list id:
     * `sessionId:firstPointAtMs:lastPointAtMs`, falling back to the window bounds.
     */
    @Throws(JSONException::class)
    private fun clippedRouteKey(
        sessionId: Long,
        points: JSONArray,
        window: DriveWindowDetector.DriveWindow,
    ): String {
        val startedAtMs = points.getJSONObject(0).optLong("atMs", window.startedAtMs)
        val endedAtMs = points.getJSONObject(points.length() - 1).optLong("atMs", window.endedAtMs)
        return "$sessionId:$startedAtMs:$endedAtMs"
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun routePointsForSessionJson(
        db: SQLiteDatabase,
        sessionId: Long,
        limit: Int,
    ): JSONArray = routePointsForSessionJson(db, sessionId, limit, null, null)

    @JvmStatic
    @Throws(JSONException::class)
    fun routePointsForSessionJson(
        db: SQLiteDatabase,
        sessionId: Long,
        limit: Int,
        windowStartMs: Long?,
        windowEndMs: Long?,
    ): JSONArray {
        val target = maxOf(1, minOf(limit, MAX_TRACK_POINTS))
        val bounded = windowStartMs != null && windowEndMs != null
        val sessionArg =
            if (bounded) {
                arrayOf(sessionId.toString(), windowStartMs.toString(), windowEndMs.toString())
            } else {
                arrayOf(sessionId.toString())
            }
        val sessionWhere =
            if (bounded) {
                "session_id = ? AND captured_at_ms >= ? AND captured_at_ms <= ?"
            } else {
                "session_id = ?"
            }

        val telemetryWhere = "$sessionWhere AND latitude IS NOT NULL AND longitude IS NOT NULL"
        val totals = routePointTotals(db, sessionWhere, telemetryWhere, sessionArg)
        val locationTotal = totals.location
        val telemetryTotal = totals.telemetry
        if (locationTotal > 0 && locationTotal >= telemetryTotal) {
            val locationPoints =
                downsampledRoutePoints(
                    db,
                    VoltTrackerDb.TABLE_LOCATION_SAMPLES,
                    arrayOf(
                        "captured_at_ms",
                        "latitude",
                        "longitude",
                        "accuracy_m",
                        "speed_mps",
                        "bearing_deg",
                        "altitude_m",
                    ),
                    sessionWhere,
                    sessionArg,
                    locationTotal,
                    target,
                    true,
                )
            if (locationPoints.length() >= 2 || telemetryTotal == 0L) {
                return simplifiedForSerialization(locationPoints)
            }
        }
        if (telemetryTotal == 0L) {
            return JSONArray()
        }
        return simplifiedForSerialization(
            downsampledRoutePoints(
                db,
                VoltTrackerDb.TABLE_TELEMETRY,
                arrayOf("captured_at_ms", "latitude", "longitude", "accuracy_m", "gps_speed_mps", "bearing_deg", "soc"),
                telemetryWhere,
                sessionArg,
                telemetryTotal,
                target,
                false,
            ),
        )
    }

    @Throws(JSONException::class)
    private fun simplifiedForSerialization(points: JSONArray): JSONArray =
        if (points.length() > SIMPLIFY_MIN_POINTS) {
            simplifyRoutePoints(points, SIMPLIFY_TOLERANCE_METERS)
        } else {
            points
        }

    /**
     * Douglas-Peucker simplification on serialized route points: drops points whose perpendicular
     * deviation from the surrounding chord is under [toleranceMeters], so near-collinear runs
     * (highway stretches) collapse while every corner sharper than the tolerance survives. The
     * first and last points — and their timestamps, which trip/route ids are built from — are
     * always kept, so simplification never changes a route key. Consumers read `pointCount` from
     * the serialized array, so it reflects the simplified count by design.
     */
    @JvmStatic
    @Throws(JSONException::class)
    fun simplifyRoutePoints(
        points: JSONArray,
        toleranceMeters: Double,
    ): JSONArray {
        val count = points.length()
        if (count <= 2) {
            return points
        }
        val lat = DoubleArray(count)
        val lng = DoubleArray(count)
        for (i in 0 until count) {
            val item = points.getJSONObject(i)
            lat[i] = item.getDouble("lat")
            lng[i] = item.getDouble("lng")
        }
        val keep = BooleanArray(count)
        keep[0] = true
        keep[count - 1] = true
        // Iterative Douglas-Peucker (explicit stack avoids deep recursion on long routes).
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, count - 1))
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            var maxDistance = 0.0
            var maxIndex = -1
            for (i in first + 1 until last) {
                val distance =
                    perpendicularDistanceMeters(lat[i], lng[i], lat[first], lng[first], lat[last], lng[last])
                if (distance > maxDistance) {
                    maxDistance = distance
                    maxIndex = i
                }
            }
            if (maxIndex >= 0 && maxDistance > toleranceMeters) {
                keep[maxIndex] = true
                stack.addLast(intArrayOf(first, maxIndex))
                stack.addLast(intArrayOf(maxIndex, last))
            }
        }
        val simplified = JSONArray()
        for (i in 0 until count) {
            if (keep[i]) {
                simplified.put(points.getJSONObject(i))
            }
        }
        return simplified
    }

    /**
     * Perpendicular distance from a point to the segment between two anchors, in meters, using a
     * local equirectangular projection — accurate to well under the tolerance at route scale.
     */
    private fun perpendicularDistanceMeters(
        pLat: Double,
        pLng: Double,
        aLat: Double,
        aLng: Double,
        bLat: Double,
        bLng: Double,
    ): Double {
        val metersPerDegLat = 111_320.0
        val metersPerDegLng = metersPerDegLat * Math.cos(Math.toRadians((aLat + bLat) / 2.0))
        val ax = aLng * metersPerDegLng
        val ay = aLat * metersPerDegLat
        val bx = bLng * metersPerDegLng
        val by = bLat * metersPerDegLat
        val px = pLng * metersPerDegLng
        val py = pLat * metersPerDegLat
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0.0) {
            return Math.hypot(px - ax, py - ay)
        }
        val t = (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun routePointTotals(
        db: SQLiteDatabase,
        locationWhere: String,
        telemetryWhere: String,
        args: Array<String>,
    ): RoutePointTotals {
        db
            .rawQuery(
                "SELECT " +
                    "(SELECT COUNT(*) FROM ${VoltTrackerDb.TABLE_LOCATION_SAMPLES} WHERE $locationWhere), " +
                    "(SELECT COUNT(*) FROM ${VoltTrackerDb.TABLE_TELEMETRY} WHERE $telemetryWhere)",
                args + args,
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    return RoutePointTotals(0L, 0L)
                }
                return RoutePointTotals(cursor.getLong(0), cursor.getLong(1))
            }
    }

    /**
     * Computes mileage from every accepted stored point. Geometry sent to the dashboard is bounded
     * and simplified for rendering, but display optimization must never become the source of truth
     * for trip distance, efficiency, or lifetime totals.
     */
    internal fun rawRouteDistanceMeters(
        db: SQLiteDatabase,
        sessionId: Long,
        windowStartMs: Long?,
        windowEndMs: Long?,
    ): Double {
        val bounded = windowStartMs != null && windowEndMs != null
        val where =
            if (bounded) {
                "session_id = ? AND captured_at_ms >= ? AND captured_at_ms <= ?"
            } else {
                "session_id = ?"
            }
        val args =
            if (bounded) {
                arrayOf(sessionId.toString(), windowStartMs.toString(), windowEndMs.toString())
            } else {
                arrayOf(sessionId.toString())
            }
        val telemetryWhere = "$where AND latitude IS NOT NULL AND longitude IS NOT NULL"
        val totals = routePointTotals(db, where, telemetryWhere, args)
        val table =
            if (totals.location > 0L && totals.location >= totals.telemetry) {
                VoltTrackerDb.TABLE_LOCATION_SAMPLES
            } else {
                VoltTrackerDb.TABLE_TELEMETRY
            }
        val selectedWhere = if (table == VoltTrackerDb.TABLE_TELEMETRY) telemetryWhere else where
        var total = 0.0
        var previousLat: Double? = null
        var previousLng: Double? = null
        db
            .query(
                table,
                arrayOf("latitude", "longitude"),
                selectedWhere,
                args,
                null,
                null,
                "captured_at_ms ASC, _id ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val lat = cursor.getDouble(0)
                    val lng = cursor.getDouble(1)
                    if (!ObdStoreSupport.validLatLng(lat, lng)) {
                        previousLat = null
                        previousLng = null
                        continue
                    }
                    val priorLat = previousLat
                    val priorLng = previousLng
                    if (priorLat != null && priorLng != null) {
                        total += ObdStoreSupport.haversineMeters(priorLat, priorLng, lat, lng)
                    }
                    previousLat = lat
                    previousLng = lng
                }
            }
        return total
    }

    @Throws(JSONException::class)
    private fun downsampledRoutePoints(
        db: SQLiteDatabase,
        table: String,
        columns: Array<String>,
        where: String,
        whereArgs: Array<String>,
        total: Long,
        target: Int,
        fromLocationSamples: Boolean,
    ): JSONArray {
        val stride = strideFor(total, target)
        val points = JSONArray()
        var tail: JSONObject? = null
        val cursor =
            if (total > target && stride > 1L) {
                sampledCursor(db, table, columns, where, whereArgs, stride, target)
            } else {
                db.query(table, columns, where, whereArgs, null, null, "captured_at_ms ASC")
            }
        cursor.use { cursor ->
            var idx = 0L
            while (cursor.moveToNext()) {
                val item = buildRoutePointItem(cursor, fromLocationSamples) ?: continue
                if (total > target && stride > 1L) {
                    points.put(item)
                    continue
                }
                if (cursor.isLast) {
                    tail = item
                    break
                }
                val strideKeep = total <= target || idx % stride == 0L
                val withinCap = total <= target || points.length() < target - 1
                if (strideKeep && withinCap) {
                    points.put(item)
                }
                idx++
            }
        }
        tail?.let { points.put(it) }
        return points
    }

    private fun sampledCursor(
        db: SQLiteDatabase,
        table: String,
        columns: Array<String>,
        where: String,
        whereArgs: Array<String>,
        stride: Long,
        target: Int,
    ): Cursor {
        val projection = columns.joinToString(", ")
        if (target <= 1) {
            return db.rawQuery(
                "SELECT $projection FROM $table WHERE ($where) ORDER BY captured_at_ms ASC, _id ASC LIMIT 1",
                whereArgs,
            )
        }
        val sampledLimit = maxOf(0, target - 2)
        val sql =
            "SELECT $projection FROM $table WHERE ($where) AND (" +
                "_id = (SELECT _id FROM $table WHERE ($where) ORDER BY captured_at_ms ASC, _id ASC LIMIT 1) " +
                "OR _id IN (SELECT _id FROM $table WHERE ($where) AND (_id % $stride = 0) " +
                "ORDER BY captured_at_ms ASC LIMIT $sampledLimit) " +
                "OR _id = (SELECT _id FROM $table WHERE ($where) ORDER BY captured_at_ms DESC, _id DESC LIMIT 1)" +
                ") ORDER BY captured_at_ms ASC, _id ASC"
        return db.rawQuery(sql, whereArgs + whereArgs + whereArgs + whereArgs)
    }

    @Throws(JSONException::class)
    private fun buildRoutePointItem(
        cursor: Cursor,
        fromLocationSamples: Boolean,
    ): JSONObject? {
        val latitude = cursor.getDouble(cursor.getColumnIndexOrThrow("latitude"))
        val longitude = cursor.getDouble(cursor.getColumnIndexOrThrow("longitude"))
        if (!ObdStoreSupport.validLatLng(latitude, longitude)) {
            return null
        }
        val item = JSONObject()
        item.put("atMs", cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")))
        item.put("lat", latitude)
        item.put("lng", longitude)
        item.put("accuracyM", jsonNumberOrNull(ObdStoreSupport.nullableDoubleBoxed(cursor, "accuracy_m")))
        item.put("bearingDeg", jsonNumberOrNull(ObdStoreSupport.nullableDoubleBoxed(cursor, "bearing_deg")))
        if (fromLocationSamples) {
            item.put("speedMps", jsonNumberOrNull(ObdStoreSupport.nullableDoubleBoxed(cursor, "speed_mps")))
            item.put("altM", jsonNumberOrNull(ObdStoreSupport.nullableDoubleBoxed(cursor, "altitude_m")))
        } else {
            item.put("speedMps", jsonNumberOrNull(ObdStoreSupport.nullableDoubleBoxed(cursor, "gps_speed_mps")))
            item.put("soc", jsonNumberOrNull(ObdStoreSupport.nullableDoubleBoxed(cursor, "soc")))
        }
        return item
    }

    private fun jsonNumberOrNull(value: Number?): Any = value?.takeIf { it.toDouble().isFinite() } ?: JSONObject.NULL

    @Throws(JSONException::class)
    private fun scalarTrackForSessionJson(
        db: SQLiteDatabase,
        sessionId: Long,
        limit: Int,
        windowStartMs: Long?,
        windowEndMs: Long?,
        column: String,
        jsonKey: String,
    ): JSONArray {
        var where = "session_id = ? AND $column IS NOT NULL"
        var args = arrayOf(sessionId.toString())
        if (windowStartMs != null && windowEndMs != null) {
            where = "session_id = ? AND captured_at_ms >= ? AND captured_at_ms <= ? AND $column IS NOT NULL"
            args = arrayOf(sessionId.toString(), windowStartMs.toString(), windowEndMs.toString())
        }
        return downsampledScalarTrack(db, args, column, where, jsonKey, maxOf(1, minOf(limit, MAX_TRACK_POINTS)))
    }

    @Throws(JSONException::class)
    private fun downsampledScalarTrack(
        db: SQLiteDatabase,
        args: Array<String>,
        column: String,
        where: String,
        jsonKey: String,
        target: Int,
    ): JSONArray {
        val total = ObdStoreSupport.countRowsWhere(db, VoltTrackerDb.TABLE_TELEMETRY, where, args)
        if (total <= 0L) {
            return JSONArray()
        }
        val stride = strideFor(total, target)
        val columns = arrayOf("captured_at_ms", column)
        val cursor =
            if (total > target && stride > 1L) {
                sampledCursor(db, VoltTrackerDb.TABLE_TELEMETRY, columns, where, args, stride, target)
            } else {
                db.query(VoltTrackerDb.TABLE_TELEMETRY, columns, where, args, null, null, "captured_at_ms ASC")
            }
        cursor.use { cursor ->
            val track = JSONArray()
            var tail: JSONObject? = null
            var idx = 0L
            while (cursor.moveToNext()) {
                val item = JSONObject()
                item.put("atMs", cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")))
                item.put(jsonKey, cursor.getDouble(cursor.getColumnIndexOrThrow(column)))
                if (total > target && stride > 1L) {
                    track.put(item)
                    continue
                }
                if (cursor.isLast) {
                    tail = item
                    break
                }
                val strideKeep = total <= target || idx % stride == 0L
                val withinCap = total <= target || track.length() < target - 1
                if (strideKeep && withinCap) {
                    track.put(item)
                }
                idx++
            }
            tail?.let { track.put(it) }
            return track
        }
    }

    private fun strideFor(
        total: Long,
        target: Int,
    ): Long {
        if (total <= target || target <= 1) {
            return 1L
        }
        // Ceiling division so the stride covers the WHOLE span in at most target-1 steps. Floor
        // division yielded stride == 1 for total in (target, 2*target-2], which made the caller
        // keep the first target-1 rows plus the final row — collapsing the route's middle-to-tail
        // into a single straight chord instead of sampling evenly.
        val span = total - 1L
        val slots = (target - 1).toLong()
        return maxOf(1L, (span + slots - 1L) / slots)
    }

    private class RoutePointTotals(
        val location: Long,
        val telemetry: Long,
    )
}
