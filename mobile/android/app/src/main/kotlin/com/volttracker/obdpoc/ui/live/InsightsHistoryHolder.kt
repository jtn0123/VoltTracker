package com.volttracker.obdpoc.ui.live

import com.volttracker.obdpoc.ui.HistoryLoad
import com.volttracker.obdpoc.ui.VoltAppUiState
import com.volttracker.obdpoc.ui.insights.CellDrift
import com.volttracker.obdpoc.ui.insights.InsightsPeriod
import com.volttracker.obdpoc.ui.insights.InsightsUiState
import com.volttracker.obdpoc.ui.insights.SpeedEfficiency
import com.volttracker.obdpoc.ui.trips.TripSummary
import com.volttracker.obdpoc.ui.trips.TripsDemo

/**
 * What the Insights tab summarises: the logged drives with the chosen period's efficiency by
 * speed and any drifting cell, or — while the demo runs — the demo's drives (demo data never
 * touches real history). Efficiency by speed is read per period and kept per period, so
 * switching back to one already read shows it at once while a new one reads.
 */
internal class InsightsHistoryHolder {
    private var logged: List<TripSummary> = emptyList()
    private var load = HistoryLoad.LOADING
    private val speeds = mutableMapOf<InsightsPeriod, List<SpeedEfficiency>>()
    private var drift: CellDrift? = null
    private var demoAtMs: Long? = null

    var period: InsightsPeriod = InsightsPeriod.MONTH
        private set

    fun onHistory(
        trips: List<TripSummary>,
        readFor: InsightsPeriod,
        speeds: List<SpeedEfficiency>,
        drift: CellDrift?,
    ) {
        logged = trips
        load = HistoryLoad.LOADED
        this.speeds[readFor] = speeds
        this.drift = drift
    }

    /** The read failed: what was shown stays; with nothing read yet, the tab says so. */
    fun onHistoryFailed() {
        load = load.failed()
    }

    fun select(period: InsightsPeriod) {
        this.period = period
    }

    fun apply(
        s: VoltAppUiState,
        nowMs: Long,
    ): VoltAppUiState {
        val next =
            if (s.settings.demoActive) {
                val at = demoAtMs ?: nowMs.also { demoAtMs = it }
                s.insights.copy(
                    period = period,
                    trips = TripsDemo.trips(at),
                    speedEfficiency = InsightsUiState.DEMO_SPEEDS,
                    cellDrift = InsightsUiState.DEMO_CELL_DRIFT,
                    nowMs = at,
                    history = HistoryLoad.LOADED,
                    speedsLoaded = true,
                )
            } else {
                demoAtMs = null
                s.insights.copy(
                    period = period,
                    trips = logged,
                    speedEfficiency = speeds[period].orEmpty(),
                    cellDrift = drift,
                    // The real clock, so the period's window stays current while the app is open.
                    nowMs = nowMs,
                    history = load,
                    speedsLoaded = period in speeds,
                )
            }
        return if (next == s.insights) s else s.copy(insights = next)
    }
}
