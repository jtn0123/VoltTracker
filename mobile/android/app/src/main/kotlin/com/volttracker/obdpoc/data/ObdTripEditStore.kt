package com.volttracker.obdpoc.data

/**
 * Trip-edit writes the dashboard drives through the bridge: user labels, favorites, and
 * hide/restore. Extends the [ObdSessionStore]/[ObdQueryStore] convention of narrow capability
 * interfaces — bridge-side callers reach this via [ObdLocalStore.tripEdits] instead of flat
 * methods on the facade.
 */
interface ObdTripEditStore {
    /**
     * Sets (or clears, when [label] is blank) the user label for the trip identified by [routeKey].
     * Persists the label as a route-key-keyed status event so it survives trip re-materialization,
     * and best-effort stamps the matching materialized `trip_segments` row's `label` column. Returns
     * false when the route key is unparseable.
     */
    fun setTripLabel(
        routeKey: String?,
        label: String?,
    ): Boolean

    /**
     * Sets or clears the user "favorite" flag for the trip identified by [routeKey] (M4). Persists
     * the flag as a route-key-keyed status event so it survives trip re-materialization (no schema
     * change — mirrors [setTripLabel]). The latest event for a route key wins, so un-favoriting
     * writes a `favorite=false` event that supersedes an earlier favorite. Returns false when the
     * route key is unparseable.
     */
    fun setTripFavorite(
        routeKey: String?,
        favorite: Boolean,
    ): Boolean

    /** Hides ("not a trip") or restores the trip identified by [routeKey]. */
    fun setTripHidden(
        routeKey: String?,
        hidden: Boolean,
    ): Boolean

    /**
     * Splits the trip identified by [routeKey] at its in-trip Park stop [stopStartMs]..[stopEndMs]
     * ("Split trip here"), persisting a user split point (see [ObdTripSplits]). The first half keeps
     * the trip's label and favorite; the second half starts fresh. Returns null when the key is
     * unparseable, the session predates gear-aware splitting, or the span is not a Park stop of
     * that trip.
     */
    fun splitTripAtStop(
        routeKey: String?,
        stopStartMs: Long,
        stopEndMs: Long,
    ): TripSplitOutcome?

    /**
     * Undoes a user split ("merge back") identified by its [splitKey] (`sessionId:start:end`). The
     * merged trip takes the first half's label and favorite. Returns null when no such split is
     * active.
     */
    fun mergeTripSplit(splitKey: String?): TripSplitOutcome?
}
