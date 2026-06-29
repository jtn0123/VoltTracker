package com.volttracker.obdpoc

import android.util.Log
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
        return request(
            "setTripRoute",
            errorPayload = { error, message ->
                JSONObject()
                    .put("routeKey", if (routeKey.isEmpty()) JSONObject.NULL else routeKey)
                    .put("payload", MainActivityUtils.errorPayload(error, message))
                    .toString()
            },
        ) {
            JSONObject()
                .put("routeKey", if (routeKey.isEmpty()) JSONObject.NULL else routeKey)
                .put("payload", parseJsonPayload(getTripRoute(routeKey)))
                .toString()
        }
    }

    private fun request(
        callbackName: String,
        errorPayload: (String, String) -> String = { error, message ->
            BridgeJsonResult.error(error, message).serialize()
        },
        readPayload: () -> String,
    ): Boolean =
        try {
            activity.runOnBackground(
                Runnable {
                    val payload =
                        try {
                            readPayload()
                        } catch (ex: RuntimeException) {
                            Log.w(MainActivity.TAG, "async bridge read failed for $callbackName", ex)
                            errorPayload("native_request_failed", "Could not read local data.")
                        }
                    try {
                        activity.publishDashboardPayload(callbackName, payload)
                    } catch (ex: RuntimeException) {
                        Log.w(MainActivity.TAG, "async bridge publish failed for $callbackName", ex)
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
