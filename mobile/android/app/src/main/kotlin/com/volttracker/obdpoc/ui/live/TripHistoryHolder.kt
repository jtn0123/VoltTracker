package com.volttracker.obdpoc.ui.live

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
    private var loggedAtMs = 0L
    private var demoAtMs: Long? = null
    private var demoKey: String = TripsDemo.MIXED_KEY

    fun onHistory(
        trips: List<TripSummary>,
        nowMs: Long,
    ) {
        logged = trips
        loggedAtMs = nowMs
        if (trips.none { it.routeKey == loggedKey }) loggedKey = null
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

    /** The logged drive whose route still has to be read, or null (demo, none, or already read). */
    fun routeToRead(demo: Boolean): String? {
        if (demo) return null
        val key = loggedKey ?: logged.firstOrNull()?.routeKey ?: return null
        return key.takeIf { loggedRoute?.routeKey != it }
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
                )
            } else {
                demoAtMs = null
                s.trips.copy(
                    trips = logged,
                    selectedKey = loggedKey,
                    route = loggedRoute,
                    nowMs = loggedAtMs,
                    exportable = true,
                )
            }
        return if (next == s.trips) s else s.copy(trips = next)
    }
}
