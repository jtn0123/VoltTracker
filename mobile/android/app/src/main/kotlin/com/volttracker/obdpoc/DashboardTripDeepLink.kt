package com.volttracker.obdpoc

import android.content.Intent

/** Holds a saved-drive notification route until the WebView callback surface is ready. */
internal class DashboardTripDeepLink(
    private val isDashboardReady: () -> Boolean,
    private val publishTrip: (String, Boolean) -> Unit,
) {
    private var pendingRouteKey: String? = null
    private var pendingReceipt = false

    fun capture(intent: Intent?) {
        pendingRouteKey = intent?.getStringExtra(MainActivity.EXTRA_OPEN_TRIP)?.takeIf { it.isNotBlank() }
        pendingReceipt =
            pendingRouteKey != null &&
            intent?.getBooleanExtra(MainActivity.EXTRA_OPEN_TRIP_RECEIPT, false) == true
    }

    fun publishPending() {
        if (!isDashboardReady()) return
        val routeKey = pendingRouteKey ?: return
        pendingRouteKey = null
        val receipt = pendingReceipt
        pendingReceipt = false
        publishTrip(routeKey, receipt)
    }
}
