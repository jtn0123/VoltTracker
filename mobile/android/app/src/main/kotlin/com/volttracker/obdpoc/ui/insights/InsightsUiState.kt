package com.volttracker.obdpoc.ui.insights

import com.volttracker.obdpoc.ui.HistoryLoad
import com.volttracker.obdpoc.ui.trips.TripSummary
import com.volttracker.obdpoc.ui.trips.TripsDemo
import com.volttracker.obdpoc.ui.trips.TripsUiState
import com.volttracker.obdpoc.ui.units.VoltUnits

/** The span the Insights tab summarises. */
enum class InsightsPeriod(
    val label: String,
) {
    WEEK("Week"),
    MONTH("Month"),
    YEAR("Year"),
    ALL("All"),
}

/** Electric efficiency in one 10-mph band ([mph] is its lower edge). */
data class SpeedEfficiency(
    val mph: Int,
    val miPerKwh: Double,
)

/** A cell sitting [belowMeanMv] under the pack mean, [driftMv] lower than [days] days before. */
data class CellDrift(
    val cell: Int,
    val belowMeanMv: Int,
    val driftMv: Int,
    val days: Int,
)

/**
 * Everything the Insights screen renders, as one immutable value: the logged drives (the
 * period figures and bars are worked out from them), the selected period's efficiency by speed,
 * any drifting cell, and the cost settings. Pure data — previewable with no service running.
 */
data class InsightsUiState(
    val connected: Boolean = false,
    val statusLabel: String = "No adapter",
    val period: InsightsPeriod = InsightsPeriod.MONTH,
    val trips: List<TripSummary> = emptyList(),
    /** Efficiency by speed for [period]; empty until read, or when too little was logged. */
    val speedEfficiency: List<SpeedEfficiency> = emptyList(),
    val cellDrift: CellDrift? = null,
    val nowMs: Long = 0L,
    val homeRate: Double = 0.0,
    val gasMpg: Double? = null,
    val gasPrice: Double = 0.0,
    /** Whether the logged drives have been read yet (demo and previews are always loaded). */
    val history: HistoryLoad = HistoryLoad.LOADED,
    /** Whether [speedEfficiency] has been read for [period]; false while that read is running. */
    val speedsLoaded: Boolean = true,
    /** Settings → Units: how distances, speeds and temperatures are shown. */
    val metricUnits: Boolean = false,
) {
    val units: VoltUnits get() = VoltUnits.of(metricUnits)

    companion object {
        /** The demo's efficiency by speed (a Volt's usual curve, best around 45 mph). */
        val DEMO_SPEEDS =
            listOf(
                SpeedEfficiency(10, 4.2),
                SpeedEfficiency(20, 4.5),
                SpeedEfficiency(30, 4.6),
                SpeedEfficiency(40, 4.7),
                SpeedEfficiency(50, 4.1),
                SpeedEfficiency(60, 3.5),
                SpeedEfficiency(70, 3.0),
            )

        val DEMO_CELL_DRIFT = CellDrift(cell = 47, belowMeanMv = 18, driftMv = 12, days = 14)

        /** Sample state over the demo's drive history (the same drives the Trips demo lists). */
        val demo: InsightsUiState
            get() =
                InsightsUiState(
                    connected = true,
                    statusLabel = "Live · 1 Hz",
                    trips = TripsDemo.trips(TripsUiState.DEMO_NOW_MS),
                    speedEfficiency = DEMO_SPEEDS,
                    cellDrift = DEMO_CELL_DRIFT,
                    nowMs = TripsUiState.DEMO_NOW_MS,
                    homeRate = 0.12,
                    gasMpg = 38.0,
                    gasPrice = 4.29,
                )
    }
}
