package com.volttracker.obdpoc.data

import android.database.sqlite.SQLiteDatabase
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/** Manual route-window exclusions recorded as status events so raw samples stay intact. */
object ObdTripExclusions {
    const val EVENT_KIND: String = "trip_hidden"
    const val REASON_NOT_TRIP: String = "manual_not_trip"

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
        val hidden = HashSet<String>()
        db
            .rawQuery(
                "SELECT detail FROM ${VoltTrackerDb.TABLE_EVENTS} " +
                    "WHERE kind = ? AND session_id IN ($placeholders) AND detail IS NOT NULL",
                args,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    canonicalRouteKey(cursor.getString(0))?.let(hidden::add)
                }
            }
        return hidden
    }

    @JvmStatic
    fun isHidden(
        db: SQLiteDatabase,
        routeKey: String?,
    ): Boolean {
        val canonical = canonicalRouteKey(routeKey) ?: return false
        db
            .rawQuery(
                "SELECT 1 FROM ${VoltTrackerDb.TABLE_EVENTS} " +
                    "WHERE kind = ? AND detail = ? LIMIT 1",
                arrayOf(EVENT_KIND, canonical),
            ).use { cursor ->
                return cursor.moveToFirst()
            }
    }
}
