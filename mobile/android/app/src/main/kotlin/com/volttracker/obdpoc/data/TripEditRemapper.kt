package com.volttracker.obdpoc.data

import android.content.ContentValues
import org.json.JSONException
import org.json.JSONObject

/**
 * Rewrites the route keys embedded inside trip-edit status events during a donor merge (audit
 * item B2), extracted from [DatabaseMerger] so the merger stays under its LargeClass ratchet
 * (the [VehicleIdentityMerge] decomposition direction).
 *
 * Trip labels, favorites, and hide/restore flags are event-sourced rows whose
 * `"<sessionId>:<startedAtMs>:<endedAtMs>"` route key is embedded as TEXT in the event's `detail`
 * column and again as `payload.routeKey` (see [ObdTripLabels], [ObdTripFavorites],
 * [ObdTripExclusions]). [DatabaseMerger.copyChildren] remaps the `session_id` COLUMN, but the
 * embedded keys still carried the DONOR's session id — so after a cross-install merge the
 * read-side lookups (which parse the embedded key) never matched: imported labels and favorites
 * vanished and hidden trips reappeared. The window timestamps are stable across installs, so
 * rewriting the leading session-id component with the merge's session map restores the edits.
 */
internal object TripEditRemapper {
    /** `status_events.kind` values whose detail/payload embed a canonical route key. */
    private val TRIP_EDIT_KINDS =
        setOf(
            ObdTripLabels.EVENT_KIND,
            ObdTripFavorites.EVENT_KIND,
            ObdTripExclusions.EVENT_KIND,
        )

    /**
     * Rewrites the embedded route key in a donor `status_events` row (both `detail` and
     * `payload.routeKey`) using [sessionMap]. Non-trip-edit kinds, malformed keys, unparseable
     * payloads, and keys whose session was not remapped are all left untouched — the merge must
     * never fail or corrupt a row because of an odd event.
     */
    fun remapEvent(
        cv: ContentValues,
        sessionMap: Map<Long, Long>,
    ) {
        if (cv.getAsString("kind") !in TRIP_EDIT_KINDS) {
            return
        }
        remapRouteKey(cv.getAsString("detail"), sessionMap)?.let { cv.put("detail", it) }
        remapPayloadRouteKey(cv.getAsString("payload"), sessionMap)?.let { cv.put("payload", it) }
    }

    /**
     * Returns [routeKey] with its leading session-id component remapped through [sessionMap], or
     * null when nothing should change (malformed/partial key, unmapped session, or an identity
     * mapping). The `start`/`end` components are copied through verbatim.
     */
    fun remapRouteKey(
        routeKey: String?,
        sessionMap: Map<Long, Long>,
    ): String? {
        val parsed = DriveWindowDetector.parseRouteKey(routeKey) ?: return null
        val start = parsed.startedAtMs ?: return null
        val end = parsed.endedAtMs ?: return null
        val mapped = sessionMap[parsed.sessionId] ?: return null
        if (mapped == parsed.sessionId) {
            return null
        }
        return "$mapped:$start:$end"
    }

    /** Rewrites `payload.routeKey` inside the JSON payload, or null when nothing should change. */
    private fun remapPayloadRouteKey(
        payload: String?,
        sessionMap: Map<Long, Long>,
    ): String? {
        if (payload.isNullOrEmpty()) {
            return null
        }
        return try {
            val json = JSONObject(payload)
            val remapped = remapRouteKey(json.optString("routeKey", ""), sessionMap) ?: return null
            json.put("routeKey", remapped).toString()
        } catch (ignored: JSONException) {
            // Unparseable payloads copy through untouched; the read side already tolerates them.
            null
        }
    }
}
