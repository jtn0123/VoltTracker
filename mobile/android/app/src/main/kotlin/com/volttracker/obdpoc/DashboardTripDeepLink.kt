package com.volttracker.obdpoc

import android.content.Intent

/**
 * Holds a deep link into the classic dashboard — a saved drive (from its notification or the
 * native trip screen) or a whole view such as Insights — until the WebView callback surface is
 * ready.
 */
internal class DashboardTripDeepLink(
    private val isDashboardReady: () -> Boolean,
    private val publishTrip: (String, Boolean) -> Unit,
    private val publishView: (String) -> Unit = {},
) {
    private var pendingRouteKey: String? = null
    private var pendingReceipt = false
    private var pendingView: String? = null

    fun capture(intent: Intent?) {
        pendingRouteKey = intent?.getStringExtra(MainActivity.EXTRA_OPEN_TRIP)?.takeIf { it.isNotBlank() }
        pendingReceipt =
            pendingRouteKey != null &&
            intent?.getBooleanExtra(MainActivity.EXTRA_OPEN_TRIP_RECEIPT, false) == true
        // Only the classic dashboard's own tabs; anything else is ignored rather than handed to JS.
        pendingView = intent?.getStringExtra(MainActivity.EXTRA_OPEN_VIEW)?.takeIf { it in OPENABLE_VIEWS }
    }

    fun publishPending() {
        if (!isDashboardReady()) return
        pendingView?.let { view ->
            pendingView = null
            publishView(view)
        }
        val routeKey = pendingRouteKey ?: return
        pendingRouteKey = null
        val receipt = pendingReceipt
        pendingReceipt = false
        publishTrip(routeKey, receipt)
    }

    companion object {
        /** The classic dashboard's `data-view` tabs. */
        val OPENABLE_VIEWS = setOf("drive", "map", "charge", "insights", "diagnostics", "settings")
    }
}
