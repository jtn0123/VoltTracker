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
    fun storageSummaryJson(): String = storageSummary().serialize()

    internal fun storageSummary(): BridgeJsonResult {
        val store = storeOrUnavailable() ?: return storageUnavailable()
        return try {
            BridgeJsonResult.obj(StorageSummaryJson.buildOverview(store.getStorageOverviewRecord()))
        } catch (ex: RuntimeException) {
            Log.w(TAG, "getStorageSummary failed", ex)
            BridgeJsonResult.error("storage_summary_failed", "Could not read local storage summary.")
        }
    }

    fun storageDetailsJson(): String = storageDetails().serialize()

    internal fun storageDetails(): BridgeJsonResult {
        val store = storeOrUnavailable() ?: return storageUnavailable()
        return try {
            BridgeJsonResult.obj(store.getStorageDetailsJson())
        } catch (ex: RuntimeException) {
            Log.w(TAG, "getStorageDetails failed", ex)
            BridgeJsonResult.error("storage_details_failed", "Could not read local storage details.")
        }
    }

    fun tripsJson(): String = trips().serialize()

    fun tripsPageJson(offset: Int): String = tripsPage(offset).serialize()

    internal fun trips(): BridgeJsonResult = tripsPage(0)

    internal fun tripsPage(offset: Int): BridgeJsonResult {
        val store = storeOrUnavailable() ?: return storageUnavailable()
        return try {
            // Rows come from the trip-list cache (no per-session recomputation) and are
            // small metadata objects, so a deep window is cheap. The map's session list
            // is fed from these beyond the storage summary's few detailed recentRoutes,
            // so this limit decides how many weeks of drives stay reachable there.
            BridgeJsonResult.array(
                if (offset <= 0) {
                    store.getTripsJson(TRIP_LIST_LIMIT)
                } else {
                    store.projections().tripsPage(TRIP_LIST_LIMIT, offset)
                },
            )
        } catch (ex: RuntimeException) {
            Log.w(TAG, "getTripsJson failed", ex)
            BridgeJsonResult.error("trips_read_failed", "Could not read logged trips.")
        }
    }

    fun tripRouteJson(routeKey: String?): String = tripRoute(routeKey).serialize()

    internal fun tripRoute(routeKey: String?): BridgeJsonResult {
        val store = storeOrUnavailable() ?: return storageUnavailable()
        return try {
            BridgeJsonResult.obj(store.routes.getTripRouteJson(routeKey))
        } catch (ex: RuntimeException) {
            Log.w(TAG, "getTripRouteJson failed", ex)
            BridgeJsonResult.error("trip_route_read_failed", "Could not read the trip route.")
        }
    }

    fun insightsJson(): String = insights().serialize()

    internal fun insights(): BridgeJsonResult {
        val store = storeOrUnavailable() ?: return storageUnavailable()
        return try {
            BridgeJsonResult.obj(store.getInsightsJson())
        } catch (ex: RuntimeException) {
            Log.w(TAG, "getInsightsJson failed", ex)
            BridgeJsonResult.error("insights_read_failed", "Could not read vehicle insights.")
        }
    }

    fun currentSessionRouteJson(): String = currentSessionRoute().serialize()

    internal fun currentSessionRoute(): BridgeJsonResult {
        val store = storeOrUnavailable() ?: return storageUnavailable()
        return try {
            BridgeJsonResult.obj(store.routes.getCurrentSessionRouteJson())
        } catch (ex: RuntimeException) {
            Log.w(TAG, "getCurrentSessionRouteJson failed", ex)
            BridgeJsonResult.error("current_route_read_failed", "Could not read the current route.")
        }
    }

    fun batterySohHistoryJson(): String = batterySohHistory().serialize()

    internal fun batterySohHistory(): BridgeJsonResult {
        val store = storeOrUnavailable() ?: return storageUnavailable()
        return try {
            BridgeJsonResult.array(store.routes.getBatterySohHistoryJson())
        } catch (ex: RuntimeException) {
            Log.w(TAG, "getBatterySohHistoryJson failed", ex)
            BridgeJsonResult.error("battery_soh_history_failed", "Could not read battery history.")
        }
    }

    // takeIf { isOpen }: these reads run synchronously on the WebView JS-bridge thread, so a
    // teardown race (store closed while a read is in flight) must degrade to the
    // storage_unavailable payload instead of throwing into the bridge.
    private fun storeOrUnavailable(): ObdLocalStore? = storeProvider()?.takeIf { it.isOpen }

    private fun storageUnavailable(): BridgeJsonResult =
        BridgeJsonResult.error(
            "storage_unavailable",
            "Local storage is not ready yet.",
        )

    private companion object {
        const val TAG = "DashboardStorageReader"
        const val TRIP_LIST_LIMIT = 120
    }
}
