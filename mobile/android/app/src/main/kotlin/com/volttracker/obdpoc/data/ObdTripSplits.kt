package com.volttracker.obdpoc.data

import android.database.sqlite.SQLiteDatabase
import com.volttracker.obdpoc.materialize.TripSplitRules
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * User trip split points ("Split trip here" on an in-trip Park stop). Stored the same way as
 * [ObdTripLabels] / [ObdTripFavorites] / [ObdTripExclusions]: as `status_events` rows scoped to the
 * session, so a split survives re-materialization and backups, and needs NO schema change.
 *
 * Each event's `detail` is the split key `sessionId:stopStartMs:stopEndMs` — the Park stop the
 * user split at. The latest event per split key wins: [STATE_SPLIT] makes it an active split,
 * [STATE_MERGED] ("merge back") retires it. Active splits feed [TripSplitRules.analyze] from both
 * `DriveWindowDetector` (trip list, map, route keys) and `TripMaterializer` (saved trip segments),
 * so every surface cuts the session the same way. They only apply to gear-aware sessions — the
 * rules ignore them for legacy sessions, and the write path refuses to record one there.
 */
object ObdTripSplits {
    const val EVENT_KIND: String = "trip_split"
    const val STATE_SPLIT: String = "split"
    const val STATE_MERGED: String = "merged"

    @JvmStatic
    fun splitKey(
        sessionId: Long,
        startMs: Long,
        endMs: Long,
    ): String = String.format(Locale.US, "%d:%d:%d", sessionId, startMs, endMs)

    @JvmStatic
    fun eventPayload(
        splitKey: String,
        routeKey: String,
        span: TripSplitRules.Span,
    ): JSONObject =
        try {
            JSONObject()
                .put("splitKey", splitKey)
                .put("routeKey", routeKey)
                .put("startMs", span.startMs)
                .put("endMs", span.endMs)
        } catch (ignored: JSONException) {
            JSONObject()
        }

    /** Active user split spans for one session, ordered by start. */
    @JvmStatic
    fun activeSplits(
        db: SQLiteDatabase,
        sessionId: Long,
    ): List<TripSplitRules.Span> = activeSplitsBySession(db, listOf(sessionId))[sessionId].orEmpty()

    /**
     * Active user split spans per session across [sessionIds]. Reads oldest-first and keeps the
     * last decision per split key, so a later merge retires an earlier split (and a re-split
     * revives it). Sessions with no active split are absent from the map.
     */
    @JvmStatic
    fun activeSplitsBySession(
        db: SQLiteDatabase,
        sessionIds: List<Long>,
    ): Map<Long, List<TripSplitRules.Span>> {
        if (sessionIds.isEmpty()) {
            return emptyMap()
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
                    ObdTripExclusions.canonicalRouteKey(cursor.getString(1))?.let { latest[it] = cursor.getString(0) }
                }
            }
        val bySession = LinkedHashMap<Long, MutableList<TripSplitRules.Span>>()
        for ((key, state) in latest) {
            if (state != STATE_SPLIT) continue
            val parsed = DriveWindowDetector.parseRouteKey(key) ?: continue
            val startMs = parsed.startedAtMs ?: continue
            val endMs = parsed.endedAtMs ?: continue
            bySession.getOrPut(parsed.sessionId) { ArrayList() }.add(TripSplitRules.Span(startMs, endMs))
        }
        bySession.values.forEach { spans -> spans.sortBy { it.startMs } }
        return bySession
    }
}
