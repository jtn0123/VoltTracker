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
        for (session in ObdStoreSupport.getRecentSessions(db, maxOf(100, sessionLimit))) {
            if (ObdLocalStore.MODE_OBD != session.mode) {
                continue
            }
            for (window in DriveWindowDetector.windowsForSession(db, session)) {
                val route = routeForSession(db, session, pointLimit, window.startedAtMs, window.endedAtMs, window.routeKey())
                if (route.optJSONArray("points")!!.length() < 2) {
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

        val locationTotal = ObdStoreSupport.countRowsWhere(db, VoltTrackerDb.TABLE_LOCATION_SAMPLES, sessionWhere, sessionArg)
        val telemetryWhere = "$sessionWhere AND latitude IS NOT NULL AND longitude IS NOT NULL"
        val telemetryTotal = ObdStoreSupport.countRowsWhere(db, VoltTrackerDb.TABLE_TELEMETRY, telemetryWhere, sessionArg)
        if (locationTotal > 0 && locationTotal >= telemetryTotal) {
            val locationPoints =
                downsampledRoutePoints(
                    db,
                    VoltTrackerDb.TABLE_LOCATION_SAMPLES,
                    arrayOf("captured_at_ms", "latitude", "longitude", "accuracy_m", "speed_mps", "bearing_deg", "altitude_m"),
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
        db.query(table, columns, where, whereArgs, null, null, "captured_at_ms ASC").use { cursor ->
            var idx = 0L
            while (cursor.moveToNext()) {
                val item = buildRoutePointItem(cursor, fromLocationSamples)
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
        if (total == 0L) {
            return JSONArray()
        }
        val stride = strideFor(total, target)
        val track = JSONArray()
        var tail: JSONObject? = null
        db
            .query(VoltTrackerDb.TABLE_TELEMETRY, arrayOf("captured_at_ms", column), where, args, null, null, "captured_at_ms ASC")
            .use { cursor ->
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
            }
        tail?.let { track.put(it) }
        return track
    }

    private fun strideFor(
        total: Long,
        target: Int,
    ): Long {
        if (total <= target || target <= 1) {
            return 1L
        }
        return maxOf(1L, (total - 1L) / (target - 1).toLong())
    }
}
