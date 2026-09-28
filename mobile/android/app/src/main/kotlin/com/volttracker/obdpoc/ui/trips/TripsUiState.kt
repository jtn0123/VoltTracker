package com.volttracker.obdpoc.ui.trips

import com.volttracker.obdpoc.ui.HistoryLoad
import com.volttracker.obdpoc.ui.units.VoltUnits

/**
 * One logged drive, as the store's trip list reports it (`tripsPage` rows). [evShare] is the
 * speed-weighted share of its classified driving done on electric (0..1), null when the drive
 * has no classified samples; [label] is the user's name for it, blank when unset.
 */
data class TripSummary(
    val routeKey: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val distanceMeters: Double,
    val energyKwh: Double? = null,
    val evShare: Double? = null,
    val label: String = "",
)

/** A route point; [gas] is true where the engine was driving the car. */
data class TripPoint(
    val lat: Double,
    val lon: Double,
    val atMs: Long,
    val gas: Boolean = false,
)

/** The selected trip's GPS track, EV / gas marked per point; [failed] when it couldn't be read. */
data class TripRoute(
    val routeKey: String,
    val points: List<TripPoint>,
    val failed: Boolean = false,
)

/**
 * Everything the Trips tab renders. [trips] are newest first; [selectedKey] picks the trip the
 * map shows (the newest when unset); [route] is that trip's track once it has been read.
 * [nowMs] anchors "Today" / "Yesterday" and the month summary.
 */
data class TripsUiState(
    val connected: Boolean = false,
    val statusLabel: String = "No adapter",
    val trips: List<TripSummary> = emptyList(),
    val selectedKey: String? = null,
    val route: TripRoute? = null,
    val nowMs: Long = 0L,
    val homeRate: Double = 0.0,
    val gasMpg: Double? = null,
    val gasPrice: Double = 0.0,
    /** False for demo trips: they have nothing on disk to export. */
    val exportable: Boolean = true,
    /** Whether the logged drives have been read yet (demo and previews are always loaded). */
    val history: HistoryLoad = HistoryLoad.LOADED,
    /** Settings → Units: how distances, speeds and temperatures are shown. */
    val metricUnits: Boolean = false,
) {
    val units: VoltUnits get() = VoltUnits.of(metricUnits)

    /** The trip the map shows. */
    val selected: TripSummary?
        get() = trips.firstOrNull { it.routeKey == selectedKey } ?: trips.firstOrNull()

    /** The selected trip's track, when it has been read (a stale one is never shown). */
    val selectedRoute: TripRoute?
        get() = route?.takeIf { it.routeKey == selected?.routeKey }

    companion object {
        /** Apr 30 2026, 9:42 PM PDT — the demo's "now" (matches the Charge demo). */
        const val DEMO_NOW_MS = 1_777_610_520_000L

        /** The mockups' Trips tab: a week of drives, one long trip that ran on gas. */
        val demo: TripsUiState
            get() =
                TripsUiState(
                    connected = true,
                    statusLabel = "Live · 1 Hz",
                    trips = TripsDemo.trips(DEMO_NOW_MS),
                    selectedKey = TripsDemo.MIXED_KEY,
                    route = TripsDemo.route(TripsDemo.MIXED_KEY, DEMO_NOW_MS),
                    nowMs = DEMO_NOW_MS,
                    homeRate = 0.12,
                    gasMpg = 40.0,
                    gasPrice = 4.50,
                    exportable = false,
                )
    }
}

/** An export the Trips tab can ask for: one drive as GPX / CSV, or every drive as CSV. */
sealed interface TripExport {
    data class One(
        val routeKey: String,
        val format: String,
    ) : TripExport

    data object All : TripExport

    companion object {
        const val GPX = "gpx"
        const val CSV = "csv"
    }
}
