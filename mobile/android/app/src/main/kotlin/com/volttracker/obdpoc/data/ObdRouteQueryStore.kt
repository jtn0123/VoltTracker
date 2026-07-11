package com.volttracker.obdpoc.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Route/track projections the dashboard's map views read: per-trip routes, the in-progress live
 * track, recent-route thumbnails, and the battery-health trend. Extends the
 * [ObdSessionStore]/[ObdQueryStore] convention of narrow capability interfaces — callers reach
 * this via [ObdLocalStore.routes] instead of flat methods on the facade.
 */
interface ObdRouteQueryStore {
    fun getTripRouteJson(sessionId: Long): JSONObject

    fun getTripRouteJson(routeKey: String?): JSONObject

    /**
     * Route projection for the in-progress (status = [ObdLocalStore.STATUS_ACTIVE]) session, or an
     * empty object when nothing is recording. Lets the dashboard rehydrate the live track after
     * the WebView is torn down and recreated mid-drive (the foreground service keeps writing
     * location samples to the active session's row the whole time). Reuses the same projection as
     * completed trips.
     */
    fun getCurrentSessionRouteJson(): JSONObject

    /** Battery-health snapshots (oldest-first) for the dashboard's pack-health trend chart. */
    fun getBatterySohHistoryJson(): JSONArray

    /** The newest [limit] completed routes, each downsampled to at most [pointLimit] points. */
    fun getRecentRoutesJson(
        limit: Int,
        pointLimit: Int,
    ): JSONArray
}
