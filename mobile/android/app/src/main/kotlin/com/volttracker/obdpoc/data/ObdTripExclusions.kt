package com.volttracker.obdpoc.data

import android.database.sqlite.SQLiteDatabase
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/** Manual route-window exclusions recorded as status events so raw samples stay intact. */
object ObdTripExclusions {
    const val EVENT_KIND: String = "trip_hidden"
    const val REASON_NOT_TRIP: String = "manual_not_trip"
    const val STATE_HIDDEN: String = "hidden"
    const val STATE_RESTORED: String = "restored"

    @JvmStatic
    fun canonicalRouteKey(raw: String?): String? {
        val parsed = DriveWindowDetector.parseRouteKey(raw) ?: return null
        val start = parsed.startedAtMs ?: return null
        val end = parsed.endedAtMs ?: return null
        if (parsed.sessionId <= 0L || end < start) {
            return null
        }
        return String.format(Locale.US, "%d:%d:%d", parsed.sessionId, start, end)
    }

    @JvmStatic
    fun eventPayload(
        routeKey: String,
        reason: String,
    ): JSONObject =
        try {
            JSONObject()
                .put("routeKey", routeKey)
                .put("reason", reason)
        } catch (ignored: JSONException) {
            JSONObject()
        }

    @JvmStatic
    fun hiddenRouteKeys(
        db: SQLiteDatabase,
        sessionIds: List<Long>,
    ): Set<String> {
        if (sessionIds.isEmpty()) {
            return emptySet()
        }
        val placeholders = sessionIds.joinToString(",") { "?" }
        val args = arrayOf(EVENT_KIND, *sessionIds.map { it.toString() }.toTypedArray())
        val latest = LinkedHashMap<String, String>()
        db
            .rawQuery(
                "SELECT state, detail FROM ${VoltTrackerDb.TABLE_EVENTS} " +
                    "WHERE kind = ? AND session_id IN ($placeholders) AND detail IS NOT NULL " +
                    "ORDER BY occurred_at_ms ASC, _id ASC",
                args,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val state = cursor.getString(0)
                    canonicalRouteKey(cursor.getString(1))?.let { latest[it] = state }
                }
            }
        return latest.filterValues { it != STATE_RESTORED }.keys
    }

    @JvmStatic
    fun isHidden(
        db: SQLiteDatabase,
        routeKey: String?,
    ): Boolean {
        val canonical = canonicalRouteKey(routeKey) ?: return false
        // The canonical key is "sessionId:start:end", and markTripNotTrip records the trip_hidden
        // event under that same session_id, so scope the lookup by session_id (like hiddenRouteKeys
        // does). The bare `kind = ? AND detail = ?` predicate matched no index — it full-scanned
        // status_events on every route open — whereas session_id is the leading column of
        // idx_events_session_time, so this narrows to the session's few rows.
        val sessionId = canonical.substringBefore(":")
        db
            .rawQuery(
                "SELECT state FROM ${VoltTrackerDb.TABLE_EVENTS} " +
                    "WHERE session_id = ? AND kind = ? AND detail = ? " +
                    "ORDER BY occurred_at_ms DESC, _id DESC LIMIT 1",
                arrayOf(sessionId, EVENT_KIND, canonical),
            ).use { cursor ->
                return cursor.moveToFirst() && cursor.getString(0) != STATE_RESTORED
            }
    }
}
