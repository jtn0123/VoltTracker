package com.volttracker.obdpoc.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * [ObdRouteQueryStore] implementation over [ObdStoreReports]' route/track projections. This is
 * also [getCurrentSessionRouteJson]'s proper home — the active-session lookup used to be inlined
 * on the [ObdLocalStore] facade purely to stay under the detekt TooManyFunctions ratchet (audit
 * item A2).
 */
internal class ObdStoreRoutes(
    private val helper: VoltTrackerDb,
    private val reports: ObdStoreReports,
) : ObdRouteQueryStore {
    override fun getTripRouteJson(sessionId: Long): JSONObject = reports.tripRouteJson(sessionId)

    override fun getTripRouteJson(routeKey: String?): JSONObject = reports.tripRouteJson(routeKey)

    override fun getCurrentSessionRouteJson(): JSONObject {
        // Direct lookup of the newest in-progress (STATUS_ACTIVE) session, independent of how many
        // newer started rows exist: a fixed-size recent scan could miss the live drive after a
        // finalize race or rapid scan/demo churn pushed enough completed rows ahead of it; this
        // status-filtered query always finds it.
        var active: ObdSessionRecord? = null
        helper.readableDatabase
            .query(
                VoltTrackerDb.TABLE_SESSIONS,
                null,
                "status = ?",
                arrayOf(ObdLocalStore.STATUS_ACTIVE),
                null,
                null,
                "started_at_ms DESC",
                "1",
            ).use { cursor ->
                if (cursor.moveToNext()) {
                    active = ObdStoreSupport.readSession(cursor)
                }
            }
        return active?.let { reports.tripRouteJson(it.id) } ?: JSONObject()
    }

    override fun getBatterySohHistoryJson(): JSONArray = reports.batterySohHistoryJson(SOH_HISTORY_LIMIT)

    override fun getRecentRoutesJson(
        limit: Int,
        pointLimit: Int,
    ): JSONArray = reports.recentRoutesProjectionJson(limit, pointLimit)

    private companion object {
        const val SOH_HISTORY_LIMIT = 1000
    }
}
