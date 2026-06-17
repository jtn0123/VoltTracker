package com.volttracker.obdpoc

internal class VoltBridgeStorage(
    private val activity: DashboardHost,
) {
    fun getStorageSummary(): String = activity.getStorageSummaryJson()

    fun getStorageDetails(): String = activity.getStorageDetailsJson()

    fun getTrips(): String = activity.getTripsJson()

    fun getInsights(): String = activity.getInsightsJson()

    fun getTripRoute(routeKeyOrSessionId: String?): String =
        activity.getTripRouteJson(bridgeSafe(routeKeyOrSessionId, BRIDGE_MAX_LABEL_LEN))

    fun getCurrentSessionRoute(): String = activity.getCurrentSessionRouteJson()

    fun getBatterySohHistory(): String = activity.getBatterySohHistoryJson()
}
