package com.volttracker.obdpoc

import android.util.Log
import com.volttracker.obdpoc.data.ObdLocalStore

/**
 * Reads SQLite-backed dashboard payloads and normalizes failures into the JSON error shape the
 * WebView already understands.
 */
class DashboardStorageReader(
    private val storeProvider: () -> ObdLocalStore?,
) {
    fun storageSummaryJson(): String {
        val store = storeOrUnavailable() ?: return storageUnavailable()
        return try {
            StorageSummaryJson.build(store.getStorageSummaryRecord()).toString()
        } catch (ex: RuntimeException) {
            Log.w(TAG, "getStorageSummary failed", ex)
            MainActivityUtils.errorPayload("storage_summary_failed", "Could not read local storage summary.").toString()
        }
    }

    fun tripsJson(): String {
        val store = storeOrUnavailable() ?: return storageUnavailable()
        return try {
            store.getTripsJson(40).toString()
        } catch (ex: RuntimeException) {
            Log.w(TAG, "getTripsJson failed", ex)
            MainActivityUtils.errorPayload("trips_read_failed", "Could not read logged trips.").toString()
        }
    }

    fun tripRouteJson(routeKey: String?): String {
        val store = storeOrUnavailable() ?: return storageUnavailable()
        return try {
            store.getTripRouteJson(routeKey).toString()
        } catch (ex: RuntimeException) {
            Log.w(TAG, "getTripRouteJson failed", ex)
            MainActivityUtils.errorPayload("trip_route_read_failed", "Could not read the trip route.").toString()
        }
    }

    fun insightsJson(): String {
        val store = storeOrUnavailable() ?: return storageUnavailable()
        return try {
            store.getInsightsJson().toString()
        } catch (ex: RuntimeException) {
            Log.w(TAG, "getInsightsJson failed", ex)
            MainActivityUtils.errorPayload("insights_read_failed", "Could not read vehicle insights.").toString()
        }
    }

    private fun storeOrUnavailable(): ObdLocalStore? = storeProvider()

    private fun storageUnavailable(): String =
        MainActivityUtils
            .errorPayload(
                "storage_unavailable",
                "Local storage is not ready yet.",
            ).toString()

    private companion object {
        const val TAG = "DashboardStorageReader"
    }
}
