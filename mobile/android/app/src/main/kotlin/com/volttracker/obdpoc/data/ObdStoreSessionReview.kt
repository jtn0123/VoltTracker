package com.volttracker.obdpoc.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Per-session diagnostic review projection.
 */
object ObdStoreSessionReview {
    @JvmStatic
    fun latestReviewableSession(db: SQLiteDatabase): ObdSessionRecord? {
        for (session in ObdStoreSupport.getRecentSessions(db, 20)) {
            val usefulTelemetry =
                ObdStoreSupport.countRowsWhere(
                    db,
                    VoltTrackerDb.TABLE_TELEMETRY,
                    "session_id = ? AND ${ObdStoreSupport.USEFUL_TELEMETRY_WHERE}",
                    arrayOf(session.id.toString()),
                )
            val pidRows =
                ObdStoreSupport.countRowsWhere(
                    db,
                    VoltTrackerDb.TABLE_PID_OBSERVATIONS,
                    "session_id = ?",
                    arrayOf(session.id.toString()),
                )
            val locationRows =
                ObdStoreSupport.countRowsWhere(
                    db,
                    VoltTrackerDb.TABLE_LOCATION_SAMPLES,
                    "session_id = ?",
                    arrayOf(session.id.toString()),
                )
            if (usefulTelemetry > 0 || pidRows > 0 || locationRows > 0) {
                return session
            }
        }
        return null
    }

    @JvmStatic
    @Throws(JSONException::class)
    fun sessionReview(
        db: SQLiteDatabase,
        session: ObdSessionRecord,
    ): JSONObject {
        val payload = JSONObject()
        payload.put("session", ObdStoreSupport.sessionToJson(session))
        payload.put(
            "pidObservationCount",
            ObdStoreSupport.countRowsWhere(
                db,
                VoltTrackerDb.TABLE_PID_OBSERVATIONS,
                "session_id = ?",
                arrayOf(session.id.toString()),
            ),
        )
        payload.put(
            "locationSampleCount",
            ObdStoreSupport.countRowsWhere(
                db,
                VoltTrackerDb.TABLE_LOCATION_SAMPLES,
                "session_id = ?",
                arrayOf(session.id.toString()),
            ),
        )
        payload.put(
            "eventCount",
            ObdStoreSupport.countRowsWhere(
                db,
                VoltTrackerDb.TABLE_EVENTS,
                "session_id = ?",
                arrayOf(session.id.toString()),
            ),
        )
        payload.put(
            "parsedPidCount",
            ObdStoreSupport.countRowsWhere(
                db,
                VoltTrackerDb.TABLE_PID_OBSERVATIONS,
                "session_id = ? AND (value_text IS NOT NULL AND value_text != '')",
                arrayOf(session.id.toString()),
            ),
        )
        payload.put(
            "unknownPidCount",
            ObdStoreSupport.countRowsWhere(
                db,
                VoltTrackerDb.TABLE_PID_OBSERVATIONS,
                "session_id = ? AND (value_text IS NULL OR value_text = '')",
                arrayOf(session.id.toString()),
            ),
        )
        val usefulTelemetry =
            ObdStoreSupport.countRowsWhere(
                db,
                VoltTrackerDb.TABLE_TELEMETRY,
                "session_id = ? AND ${ObdStoreSupport.USEFUL_TELEMETRY_WHERE}",
                arrayOf(session.id.toString()),
            )
        payload.put("usefulTelemetryCount", usefulTelemetry)
        payload.put("emptyTelemetryCount", maxOf(0L, session.sampleCount - usefulTelemetry))
        payload.put("maxSpeedKph", ObdStoreSupport.maxIntForSession(db, "speed_kph", session.id))
        payload.put("avgSampleIntervalMs", ObdStoreSupport.averageSampleIntervalMs(db, session.id))
        payload.put(
            "backgroundSampleCount",
            ObdStoreSupport.countRowsWhere(
                db,
                VoltTrackerDb.TABLE_TELEMETRY,
                "session_id = ? AND app_foreground = 0",
                arrayOf(session.id.toString()),
            ),
        )
        payload.put(
            "sampleGapEventCount",
            ObdStoreSupport.countRowsWhere(
                db,
                VoltTrackerDb.TABLE_EVENTS,
                "session_id = ? AND detail = ?",
                arrayOf(session.id.toString(), "sample_gap"),
            ),
        )
        payload.put("latestHealth", latestHealthJson(db, session.id))
        payload.put("stateCounts", stateCountsJson(db, session.id))
        payload.put("timeline", recentEventsJson(db, session.id, 20))
        payload.put("recentPidFrames", recentPidFramesJson(db, session.id, 20))
        payload.put("speedTrace", recentSpeedTraceJson(db, session.id, 48))
        payload.put("route", ObdStoreRouteProjection.routeForSession(db, session, 180))
        payload.put("warnings", sessionWarningsJson(db, session.id))
        return payload
    }

    @Throws(JSONException::class)
    private fun latestHealthJson(
        db: SQLiteDatabase,
        sessionId: Long,
    ): JSONObject =
        db
            .query(
                VoltTrackerDb.TABLE_TELEMETRY,
                arrayOf("json"),
                "session_id = ? AND ${ObdStoreSupport.USEFUL_TELEMETRY_WHERE}",
                arrayOf(sessionId.toString()),
                null,
                null,
                "captured_at_ms DESC",
                "1",
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    return JSONObject()
                }
                val rawJson = ObdStoreSupport.parseObject(cursor.getString(cursor.getColumnIndexOrThrow("json")))
                val payload = JSONObject()
                payload.put("appForeground", rawJson.optBoolean("appForeground", true))
                payload.put("foregroundServiceActive", rawJson.optBoolean("foregroundServiceActive", false))
                payload.put("backgroundSampleCount", rawJson.optInt("backgroundSampleCount", 0))
                payload.put("sampleGapCount", rawJson.optInt("sampleGapCount", 0))
                payload.put("lastSampleGapMs", rawJson.optLong("lastSampleGapMs", 0L))
                payload.put("maxSampleGapMs", rawJson.optLong("maxSampleGapMs", 0L))
                payload
            }

    @Throws(JSONException::class)
    private fun stateCountsJson(
        db: SQLiteDatabase,
        sessionId: Long,
    ): JSONObject {
        val payload = JSONObject()
        db
            .rawQuery(
                "SELECT vehicle_state, COUNT(*) FROM ${VoltTrackerDb.TABLE_TELEMETRY}" +
                    " WHERE session_id = ? AND ${ObdStoreSupport.USEFUL_TELEMETRY_WHERE} GROUP BY vehicle_state",
                arrayOf(sessionId.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val state = ObdStoreSupport.clean(cursor.getString(0))
                    payload.put(if (state.isEmpty()) "unknown" else state, cursor.getLong(1))
                }
            }
        return payload
    }

    @Throws(JSONException::class)
    private fun sessionWarningsJson(
        db: SQLiteDatabase,
        sessionId: Long,
    ): JSONArray {
        val payload = JSONArray()
        val chargeHints =
            ObdStoreSupport.countRowsWhere(
                db,
                VoltTrackerDb.TABLE_TELEMETRY,
                "session_id = ? AND charge_transition_hint = 1",
                arrayOf(sessionId.toString()),
            )
        val speedRejected =
            ObdStoreSupport.countRowsWhere(
                db,
                VoltTrackerDb.TABLE_EVENTS,
                "session_id = ? AND detail = ?",
                arrayOf(sessionId.toString(), "speed_rejected"),
            )
        val gpsSamples =
            ObdStoreSupport.countRowsWhere(
                db,
                VoltTrackerDb.TABLE_LOCATION_SAMPLES,
                "session_id = ?",
                arrayOf(sessionId.toString()),
            )
        val unknownPids =
            ObdStoreSupport.countRowsWhere(
                db,
                VoltTrackerDb.TABLE_PID_OBSERVATIONS,
                "session_id = ? AND (value_text IS NULL OR value_text = '')",
                arrayOf(sessionId.toString()),
            )
        val sampleGaps =
            ObdStoreSupport.countRowsWhere(
                db,
                VoltTrackerDb.TABLE_EVENTS,
                "session_id = ? AND detail = ?",
                arrayOf(sessionId.toString(), "sample_gap"),
            )
        val backgroundEvents =
            ObdStoreSupport.countRowsWhere(
                db,
                VoltTrackerDb.TABLE_EVENTS,
                "session_id = ? AND (detail = ? OR detail = ?)",
                arrayOf(sessionId.toString(), "app_backgrounded", "app_foregrounded"),
            )
        val emptyTelemetry =
            ObdStoreSupport.countRowsWhere(
                db,
                VoltTrackerDb.TABLE_TELEMETRY,
                "session_id = ? AND NOT ${ObdStoreSupport.USEFUL_TELEMETRY_WHERE}",
                arrayOf(sessionId.toString()),
            )
        val movingWithZeroRpm =
            ObdStoreSupport.countRowsWhere(
                db,
                VoltTrackerDb.TABLE_TELEMETRY,
                "session_id = ? AND speed_kph > 0 AND rpm = 0 AND ${ObdStoreSupport.USEFUL_TELEMETRY_WHERE}",
                arrayOf(sessionId.toString()),
            )
        val powerRows =
            ObdStoreSupport.countRowsWhere(
                db,
                VoltTrackerDb.TABLE_TELEMETRY,
                "session_id = ? AND power_kw IS NOT NULL AND ${ObdStoreSupport.USEFUL_TELEMETRY_WHERE}",
                arrayOf(sessionId.toString()),
            )
        if (chargeHints > 0 || speedRejected > 0) {
            payload.put(
                warning(
                    "charge-speed-hint",
                    "Rejected 255 km/h speed frame seen. Treat it as a charging/transition clue, not vehicle speed.",
                    maxOf(chargeHints, speedRejected),
                ),
            )
        }
        if (gpsSamples == 0L) {
            payload.put(warning("gps-missing", "No GPS samples were stored for this session.", 0))
        }
        if (unknownPids > 0) {
            payload.put(warning("pid-unparsed", "Some PID responses are stored but not parsed yet.", unknownPids))
        }
        if (sampleGaps > 0) {
            payload.put(warning("sample-gap", "Logging had one or more long sample gaps while the session was active.", sampleGaps))
        }
        if (backgroundEvents > 0) {
            payload.put(
                warning(
                    "background-tested",
                    "App foreground/background transitions were captured for this session.",
                    backgroundEvents,
                ),
            )
        }
        if (emptyTelemetry > 0) {
            payload.put(
                warning(
                    "empty-telemetry",
                    "This older session contains empty telemetry rows from a broken adapter pipe. Product summaries now ignore them.",
                    emptyTelemetry,
                ),
            )
        }
        if (movingWithZeroRpm > 0) {
            payload.put(
                warning(
                    "rpm-zero-moving",
                    "Vehicle speed was observed while standard engine RPM stayed at 0. Validate engine-running behavior with Scan.",
                    movingWithZeroRpm,
                ),
            )
        }
        if (powerRows == 0L) {
            payload.put(
                warning(
                    "power-pid-missing",
                    "No real power/kW rows are stored yet. Pack or charger power needs validated Volt-specific PIDs.",
                    0,
                ),
            )
        }
        return payload
    }

    @Throws(JSONException::class)
    private fun warning(
        code: String,
        detail: String,
        count: Long,
    ): JSONObject {
        val item = JSONObject()
        item.put("code", code)
        item.put("detail", detail)
        item.put("count", count)
        return item
    }

    @Throws(JSONException::class)
    private fun recentEventsJson(
        db: SQLiteDatabase,
        sessionId: Long,
        limit: Int,
    ): JSONArray {
        val payload = JSONArray()
        db
            .query(
                VoltTrackerDb.TABLE_EVENTS,
                arrayOf("occurred_at_ms", "kind", "state", "detail", "blocked", "payload"),
                "session_id = ?",
                arrayOf(sessionId.toString()),
                null,
                null,
                "occurred_at_ms DESC",
                ObdStoreSupport.boundedLimit(limit),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val item = JSONObject()
                    item.put("atMs", cursor.getLong(cursor.getColumnIndexOrThrow("occurred_at_ms")))
                    item.put("kind", ObdStoreSupport.clean(cursor.getString(cursor.getColumnIndexOrThrow("kind"))))
                    item.put("state", ObdStoreSupport.clean(cursor.getString(cursor.getColumnIndexOrThrow("state"))))
                    item.put("detail", ObdStoreSupport.clean(cursor.getString(cursor.getColumnIndexOrThrow("detail"))))
                    item.put("blocked", cursor.getInt(cursor.getColumnIndexOrThrow("blocked")) != 0)
                    item.put("payload", ObdStoreSupport.parseObject(cursor.getString(cursor.getColumnIndexOrThrow("payload"))))
                    payload.put(item)
                }
            }
        return ObdStoreSupport.reverse(payload)
    }

    @Throws(JSONException::class)
    private fun recentPidFramesJson(
        db: SQLiteDatabase,
        sessionId: Long,
        limit: Int,
    ): JSONArray {
        val payload = JSONArray()
        db
            .query(
                VoltTrackerDb.TABLE_PID_OBSERVATIONS,
                arrayOf(
                    "observed_at_ms",
                    "command",
                    "header",
                    "pid",
                    "name",
                    "value_text",
                    "value_numeric",
                    "unit",
                    "raw_response",
                    "json",
                ),
                "session_id = ?",
                arrayOf(sessionId.toString()),
                null,
                null,
                "observed_at_ms DESC",
                ObdStoreSupport.boundedLimit(limit),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val item = JSONObject()
                    val valueText =
                        ObdStoreSupport.clean(
                            cursor.getString(cursor.getColumnIndexOrThrow("value_text")),
                        )
                    val rawJson =
                        ObdStoreSupport.parseObject(
                            cursor.getString(cursor.getColumnIndexOrThrow("json")),
                        )
                    item.put("atMs", cursor.getLong(cursor.getColumnIndexOrThrow("observed_at_ms")))
                    item.put(
                        "command",
                        ObdStoreSupport.clean(
                            cursor.getString(cursor.getColumnIndexOrThrow("command")),
                        ),
                    )
                    val header =
                        ObdStoreSupport.clean(
                            cursor.getString(cursor.getColumnIndexOrThrow("header")),
                        )
                    item.put("header", header)
                    item.put(
                        "pid",
                        cleanString(cursor, "pid"),
                    )
                    item.put(
                        "name",
                        cleanString(cursor, "name"),
                    )
                    item.put("valueText", valueText)
                    item.put(
                        "unit",
                        cleanString(cursor, "unit"),
                    )
                    item.put(
                        "rawResponse",
                        ObdStoreSupport.clean(
                            cursor.getString(cursor.getColumnIndexOrThrow("raw_response")),
                        ),
                    )
                    item.put("durationMs", rawJson.optLong("durationMs", 0L))
                    item.put("gotPrompt", rawJson.optBoolean("gotPrompt", false))
                    item.put("parsed", valueText.isNotEmpty())
                    payload.put(item)
                }
            }
        return payload
    }

    @Throws(JSONException::class)
    private fun recentSpeedTraceJson(
        db: SQLiteDatabase,
        sessionId: Long,
        limit: Int,
    ): JSONArray {
        val payload = JSONArray()
        db
            .query(
                VoltTrackerDb.TABLE_TELEMETRY,
                arrayOf("captured_at_ms", "speed_kph", "vehicle_state", "json"),
                "session_id = ? AND ${ObdStoreSupport.USEFUL_TELEMETRY_WHERE}",
                arrayOf(sessionId.toString()),
                null,
                null,
                "captured_at_ms DESC",
                ObdStoreSupport.boundedLimit(limit),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val item = JSONObject()
                    val rawJson =
                        ObdStoreSupport.parseObject(
                            cursor.getString(cursor.getColumnIndexOrThrow("json")),
                        )
                    item.put("atMs", cursor.getLong(cursor.getColumnIndexOrThrow("captured_at_ms")))
                    val boxedSpeed = ObdStoreSupport.nullableIntBoxed(cursor, "speed_kph")
                    item.put("speedKph", boxedSpeed ?: JSONObject.NULL)
                    item.put("state", cleanString(cursor, "vehicle_state"))
                    item.put("chargeTransitionHint", rawJson.optBoolean("chargeTransitionHint", false))
                    item.put("speedRejectedKph", rawJson.optInt("speedRejectedKph", 0))
                    payload.put(item)
                }
            }
        return ObdStoreSupport.reverse(payload)
    }

    private fun cleanString(
        cursor: Cursor,
        columnName: String,
    ): String = ObdStoreSupport.clean(cursor.getString(cursor.getColumnIndexOrThrow(columnName)))
}
