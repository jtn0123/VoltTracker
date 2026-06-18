package com.volttracker.obdpoc

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

internal class VoltBridgeStorage(
    private val activity: DashboardHost,
) {
    fun getStorageSummary(): String =
        StartupTrace.measure("storage_summary_bridge_sync_start", "storage_summary_bridge_sync_end") {
            activity.getStorageSummaryJson()
        }

    fun getStorageDetails(): String = activity.getStorageDetailsJson()

    fun getTrips(): String = activity.getTripsJson()

    fun getInsights(): String = activity.getInsightsJson()

    fun getTripRoute(routeKeyOrSessionId: String?): String =
        activity.getTripRouteJson(bridgeSafe(routeKeyOrSessionId, BRIDGE_MAX_LABEL_LEN))

    fun getCurrentSessionRoute(): String = activity.getCurrentSessionRouteJson()

    fun getBatterySohHistory(): String = activity.getBatterySohHistoryJson()

    fun requestStorageSummary(): Boolean = request("setStorage") { getStorageSummary() }

    fun requestStorageDetails(): Boolean = request("setStorage") { getStorageDetails() }

    fun requestTrips(): Boolean = request("setTrips") { getTrips() }

    fun requestInsights(): Boolean = request("setInsights") { getInsights() }

    fun requestCurrentSessionRoute(): Boolean = request("setCurrentSessionRoute") { getCurrentSessionRoute() }

    fun requestBatterySohHistory(): Boolean = request("setBatterySohHistory") { getBatterySohHistory() }

    fun requestTripRoute(routeKeyOrSessionId: String?): Boolean {
        val routeKey = bridgeSafe(routeKeyOrSessionId, BRIDGE_MAX_LABEL_LEN)
        return request("setTripRoute") {
            JSONObject()
                .put("routeKey", routeKey ?: JSONObject.NULL)
                .put("payload", parseJsonPayload(getTripRoute(routeKey)))
                .toString()
        }
    }

    private fun request(
        callbackName: String,
        readPayload: () -> String,
    ): Boolean =
        try {
            activity.runOnBackground(
                Runnable {
                    try {
                        activity.publishDashboardPayload(callbackName, readPayload())
                    } catch (ex: RuntimeException) {
                        // Keep the shared worker alive; callers will retry on the next render/refresh.
                    }
                },
            )
            true
        } catch (ex: RuntimeException) {
            false
        }

    private fun parseJsonPayload(payload: String): Any =
        try {
            JSONTokener(payload).nextValue()
        } catch (ex: JSONException) {
            JSONObject().put("ok", false).put("error", "payload_parse_failed")
        }
}
