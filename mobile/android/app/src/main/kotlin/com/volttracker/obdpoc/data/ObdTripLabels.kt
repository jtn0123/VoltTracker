package com.volttracker.obdpoc.data

import android.database.sqlite.SQLiteDatabase
import org.json.JSONException
import org.json.JSONObject

/**
 * User-authored trip labels (M4). Stored the same way as [ObdTripExclusions]: as `status_events`
 * rows keyed by canonical route key, so a label survives trip re-materialization and is dropped
 * with its session's raw rows (the event's `session_id` FK cascades / set-nulls like every other
 * status event). The latest event for a route key wins; an empty label clears it.
 *
 * The on-disk `trip_segments.label` column exists for materialized-trip provenance, but the trips
 * the dashboard renders are keyed by route key (`sessionId:startedAt:endedAt`) from drive-window
 * detection — not 1:1 with materialized segments — so the route-key-keyed event store is what the
 * trip JSON joins against.
 */
object ObdTripLabels {
    const val EVENT_KIND: String = "trip_label"

    /** Max label length persisted; longer input is truncated before storage. */
    const val MAX_LABEL_LEN: Int = 80

    @JvmStatic
    fun cleanLabel(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        return if (trimmed.length <= MAX_LABEL_LEN) trimmed else trimmed.substring(0, MAX_LABEL_LEN)
    }

    @JvmStatic
    fun eventPayload(
        routeKey: String,
        label: String,
    ): JSONObject =
        try {
            JSONObject()
                .put("routeKey", routeKey)
                .put("label", cleanLabel(label))
        } catch (ignored: JSONException) {
            JSONObject()
        }

    /**
     * Latest non-empty label per route key across [sessionIds]. A later empty-label event clears a
     * route's entry, so we read newest-first and keep the first decision we see for each key.
     */
    @JvmStatic
    fun labelsByRouteKey(
        db: SQLiteDatabase,
        sessionIds: List<Long>,
    ): Map<String, String> {
        if (sessionIds.isEmpty()) {
            return emptyMap()
        }
        val placeholders = sessionIds.joinToString(",") { "?" }
        val args = arrayOf(EVENT_KIND, *sessionIds.map { it.toString() }.toTypedArray())
        val labels = HashMap<String, String>()
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
                    val label = labelFromPayload(cursor.getString(1))
                    if (label.isNotEmpty()) {
                        labels[routeKey] = label
                    }
                }
            }
        return labels
    }

    private fun labelFromPayload(payload: String?): String {
        if (payload.isNullOrEmpty()) {
            return ""
        }
        return try {
            cleanLabel(JSONObject(payload).optString("label", ""))
        } catch (ignored: JSONException) {
            ""
        }
    }
}
