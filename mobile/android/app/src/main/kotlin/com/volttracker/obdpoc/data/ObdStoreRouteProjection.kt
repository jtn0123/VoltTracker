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
            sessionJson.put("startedAtMs", windowStartMs.toLong())
            sessionJson.put("endedAtMs", windowEndMs.toLong())
        }
        payload.put("session", sessionJson)
        payload.put("points", points)
        payload.put("pointCount", points.length())
        payload.put("distanceMeters", ObdStoreSupport.distanceMeters(points))
        payload.put("bounds", ObdStoreSupport.boundsFor(points))
        payload.put("socTrack", socTrackForSessionJson(db, session.id, limit, windowStartMs, windowEndMs))
        payload.put("powerTrack", powerTrackForSessionJson(db, session.id, limit, windowStartMs, windowEndMs))
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
                return locationPoints
            }
        }
        if (telemetryTotal == 0L) {
            return JSONArray()
        }
        return downsampledRoutePoints(
            db,
            VoltTrackerDb.TABLE_TELEMETRY,
            arrayOf("captured_at_ms", "latitude", "longitude", "accuracy_m", "gps_speed_mps", "bearing_deg", "soc"),
            telemetryWhere,
            sessionArg,
            telemetryTotal,
            target,
            false,
        )
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
                sampledRouteCursor(db, table, columns, where, whereArgs, stride, target)
            } else {
                db.query(table, columns, where, whereArgs, null, null, "captured_at_ms ASC")
            }
        cursor.use { cursor ->
            var idx = 0L
            while (cursor.moveToNext()) {
                val item = buildRoutePointItem(cursor, fromLocationSamples)
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

    private fun sampledRouteCursor(
        db: SQLiteDatabase,
        table: String,
        columns: Array<String>,
        where: String,
        whereArgs: Array<String>,
        stride: Long,
        target: Int,
    ): Cursor {
        val projection = columns.joinToString(", ")
        val sampledLimit = maxOf(1, target - 2)
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
    ): JSONObject {
        val item = JSONObject()
        item.put("atMs", cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")))
        item.put("lat", cursor.getDouble(cursor.getColumnIndexOrThrow("latitude")))
        item.put("lng", cursor.getDouble(cursor.getColumnIndexOrThrow("longitude")))
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

    private fun jsonNumberOrNull(value: Number?): Any = value ?: JSONObject.NULL

    @Throws(JSONException::class)
    private fun socTrackForSessionJson(
        db: SQLiteDatabase,
        sessionId: Long,
        limit: Int,
        windowStartMs: Long?,
        windowEndMs: Long?,
    ): JSONArray {
        var where = "session_id = ? AND soc IS NOT NULL"
        var args = arrayOf(sessionId.toString())
        if (windowStartMs != null && windowEndMs != null) {
            where = "session_id = ? AND captured_at_ms >= ? AND captured_at_ms <= ? AND soc IS NOT NULL"
            args = arrayOf(sessionId.toString(), windowStartMs.toString(), windowEndMs.toString())
        }
        return downsampledScalarTrack(db, args, "soc", where, "soc", maxOf(1, minOf(limit, MAX_TRACK_POINTS)))
    }

    @Throws(JSONException::class)
    private fun powerTrackForSessionJson(
        db: SQLiteDatabase,
        sessionId: Long,
        limit: Int,
        windowStartMs: Long?,
        windowEndMs: Long?,
    ): JSONArray {
        var where = "session_id = ? AND power_kw IS NOT NULL"
        var args = arrayOf(sessionId.toString())
        if (windowStartMs != null && windowEndMs != null) {
            where = "session_id = ? AND captured_at_ms >= ? AND captured_at_ms <= ? AND power_kw IS NOT NULL"
            args = arrayOf(sessionId.toString(), windowStartMs.toString(), windowEndMs.toString())
        }
        return downsampledScalarTrack(db, args, "power_kw", where, "powerKw", maxOf(1, minOf(limit, MAX_TRACK_POINTS)))
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
        db
            .query(
                VoltTrackerDb.TABLE_TELEMETRY,
                arrayOf("captured_at_ms", column),
                where,
                args,
                null,
                null,
                "captured_at_ms ASC",
            ).use { cursor ->
                val track = JSONArray()
                var tail: JSONObject? = null
                var idx = 0L
                while (cursor.moveToNext()) {
                    val item = JSONObject()
                    item.put("atMs", cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")))
                    item.put(jsonKey, cursor.getDouble(cursor.getColumnIndexOrThrow(column)))
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
