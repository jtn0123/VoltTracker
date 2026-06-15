package com.volttracker.obdpoc.data

import android.database.sqlite.SQLiteDatabase
import org.json.JSONException
import org.json.JSONObject

/**
 * User-authored trip favorites (M4 — favorites half). Stored the same way as [ObdTripLabels] /
 * [ObdTripExclusions]: as `status_events` rows keyed by canonical route key, so the favorite flag
 * survives trip re-materialization and is dropped with its session's raw rows (the event's
 * `session_id` FK cascades / set-nulls like every other status event). NO schema change is needed.
 *
 * The latest event for a route key wins, so un-favoriting writes a `favorite=false` event that
 * supersedes an earlier `favorite=true`. The dashboard stamps the resolved boolean onto each trip's
 * JSON (see [ObdStoreTrips.applyLabels]) so it can show/sort by it.
 */
object ObdTripFavorites {
    const val EVENT_KIND: String = "trip_favorite"

    @JvmStatic
    fun eventPayload(
        routeKey: String,
        favorite: Boolean,
    ): JSONObject =
        try {
            JSONObject()
                .put("routeKey", routeKey)
                .put("favorite", favorite)
        } catch (ignored: JSONException) {
            JSONObject()
        }

    /**
     * The set of route keys currently favorited across [sessionIds]. Reads newest-first and keeps
     * the first decision seen per key, so a later un-favorite event (favorite=false) clears an
     * earlier favorite. Keys whose latest event is favorite=false are omitted.
     */
    @JvmStatic
    fun favoriteRouteKeys(
        db: SQLiteDatabase,
        sessionIds: List<Long>,
    ): Set<String> {
        if (sessionIds.isEmpty()) {
            return emptySet()
        }
        val placeholders = sessionIds.joinToString(",") { "?" }
        val args = arrayOf(EVENT_KIND, *sessionIds.map { it.toString() }.toTypedArray())
        val favorites = HashSet<String>()
        val decided = HashSet<String>()
        db
            .rawQuery(
                "SELECT detail, payload FROM ${VoltTrackerDb.TABLE_EVENTS} " +
                    "WHERE kind = ? AND session_id IN ($placeholders) AND detail IS NOT NULL " +
                    "ORDER BY occurred_at_ms DESC, _id DESC",
                args,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val routeKey = ObdTripExclusions.canonicalRouteKey(cursor.getString(0)) ?: continue
                    if (!decided.add(routeKey)) {
                        continue
                    }
                    if (favoriteFromPayload(cursor.getString(1))) {
                        favorites.add(routeKey)
                    }
                }
            }
        return favorites
    }

    private fun favoriteFromPayload(payload: String?): Boolean {
        if (payload.isNullOrEmpty()) {
            return false
        }
        return try {
            JSONObject(payload).optBoolean("favorite", false)
        } catch (ignored: JSONException) {
            false
        }
    }
}
