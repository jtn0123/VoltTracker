package com.volttracker.obdpoc.ui.live

import com.volttracker.obdpoc.ui.HistoryLoad
import com.volttracker.obdpoc.ui.VoltAppUiState
import com.volttracker.obdpoc.ui.trips.TripRoute
import com.volttracker.obdpoc.ui.trips.TripSummary
import com.volttracker.obdpoc.ui.trips.TripsDemo

/**
 * What the Trips tab shows: the logged drives and the selected one's route, or — while the demo
 * runs — sample drives (demo data never touches real history). Remembers the selection for each
 * so switching the demo off returns to the drive that was showing.
 */
internal class TripHistoryHolder {
    private var logged: List<TripSummary> = emptyList()
    private var loggedRoute: TripRoute? = null
    private var loggedKey: String? = null
    private var load = HistoryLoad.LOADING
    private var demoAtMs: Long? = null
    private var demoKey: String = TripsDemo.MIXED_KEY

    fun onHistory(trips: List<TripSummary>) {
        logged = trips
        load = HistoryLoad.LOADED
        if (trips.none { it.routeKey == loggedKey }) loggedKey = null
    }

    /** The drive list couldn't be read: a list already shown stays; otherwise the tab says so. */
    fun onHistoryFailed() {
        load = load.failed()
    }

    fun onRoute(route: TripRoute) {
        loggedRoute = route
    }

    /** Picks [key] for the map (in the demo or the logged list, whichever is showing). */
    fun select(
        key: String,
        demo: Boolean,
    ) {
        if (demo) demoKey = key else loggedKey = key
    }

    /**
     * The logged drive whose route still has to be read, or null (demo, none, or already read).
     * A route whose read failed is read again on the next request (the next visit to Trips).
     */
    fun routeToRead(demo: Boolean): String? {
        if (demo) return null
        val key = loggedKey ?: logged.firstOrNull()?.routeKey ?: return null
        val route = loggedRoute
        return key.takeIf { route == null || route.routeKey != it || route.failed }
    }

    fun apply(
        s: VoltAppUiState,
        nowMs: Long,
    ): VoltAppUiState {
        val next =
            if (s.settings.demoActive) {
                val at = demoAtMs ?: nowMs.also { demoAtMs = it }
                s.trips.copy(
                    trips = TripsDemo.trips(at),
                    selectedKey = demoKey,
                    route = TripsDemo.route(demoKey, at),
                    nowMs = at,
                    exportable = false,
                    history = HistoryLoad.LOADED,
                )
            } else {
                demoAtMs = null
                s.trips.copy(
                    trips = logged,
                    selectedKey = loggedKey,
                    route = loggedRoute,
                    // The real clock, so "Today" and the month stay right across midnight.
                    nowMs = nowMs,
                    exportable = true,
                    history = load,
                )
            }
        return if (next == s.trips) s else s.copy(trips = next)
    }
}
