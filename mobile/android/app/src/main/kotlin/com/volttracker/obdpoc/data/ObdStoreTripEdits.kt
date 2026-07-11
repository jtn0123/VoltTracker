package com.volttracker.obdpoc.data

/**
 * [ObdTripEditStore] implementation: composes the event-sourced trip edits (labels, favorites,
 * hide/restore) out of [ObdStoreWriter] event writes plus [ObdStoreTrips] cache invalidation.
 * Bodies moved off the [ObdLocalStore] facade when it was narrowed behind capability interfaces
 * (audit item A2).
 */
internal class ObdStoreTripEdits(
    private val writer: ObdStoreWriter,
    private val trips: ObdStoreTrips,
) : ObdTripEditStore {
    override fun setTripLabel(
        routeKey: String?,
        label: String?,
    ): Boolean {
        val canonical = ObdTripExclusions.canonicalRouteKey(routeKey) ?: return false
        val parsed = DriveWindowDetector.parseRouteKey(canonical) ?: return false
        // canonicalRouteKey guarantees a 3-part key, so both timestamps are present here.
        val startedAtMs = parsed.startedAtMs ?: return false
        val endedAtMs = parsed.endedAtMs ?: return false
        val cleanLabel = ObdTripLabels.cleanLabel(label)
        writer.recordEvent(
            parsed.sessionId,
            ObdTripLabels.EVENT_KIND,
            if (cleanLabel.isEmpty()) "cleared" else "labeled",
            canonical,
            false,
            ObdTripLabels.eventPayload(canonical, cleanLabel),
        )
        writer.setTripSegmentLabel(parsed.sessionId, startedAtMs, endedAtMs, cleanLabel)
        return true
    }

    override fun setTripFavorite(
        routeKey: String?,
        favorite: Boolean,
    ): Boolean {
        val canonical = ObdTripExclusions.canonicalRouteKey(routeKey) ?: return false
        val parsed = DriveWindowDetector.parseRouteKey(canonical) ?: return false
        writer.recordEvent(
            parsed.sessionId,
            ObdTripFavorites.EVENT_KIND,
            if (favorite) "favorited" else "unfavorited",
            canonical,
            false,
            ObdTripFavorites.eventPayload(canonical, favorite),
        )
        return true
    }

    override fun setTripHidden(
        routeKey: String?,
        hidden: Boolean,
    ): Boolean {
        val canonical = ObdTripExclusions.canonicalRouteKey(routeKey) ?: return false
        val parsed = DriveWindowDetector.parseRouteKey(canonical) ?: return false
        writer.recordEvent(
            parsed.sessionId,
            ObdTripExclusions.EVENT_KIND,
            if (hidden) ObdTripExclusions.STATE_HIDDEN else ObdTripExclusions.STATE_RESTORED,
            canonical,
            false,
            ObdTripExclusions.eventPayload(
                canonical,
                if (hidden) ObdTripExclusions.REASON_NOT_TRIP else "manual_restore",
            ),
        )
        trips.invalidateSessionTripCache(parsed.sessionId)
        return true
    }
}
