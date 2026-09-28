package com.volttracker.obdpoc.ui.live

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
 * touches real history). Efficiency by speed is read per period, so it only shows for the
 * period it was read for.
 */
internal class InsightsHistoryHolder {
    private var logged: List<TripSummary> = emptyList()
    private var loggedAtMs = 0L
    private var speeds: List<SpeedEfficiency> = emptyList()
    private var speedsFor: InsightsPeriod? = null
    private var drift: CellDrift? = null
    private var demoAtMs: Long? = null

    var period: InsightsPeriod = InsightsPeriod.MONTH
        private set

    fun onHistory(
        trips: List<TripSummary>,
        readFor: InsightsPeriod,
        speeds: List<SpeedEfficiency>,
        drift: CellDrift?,
        nowMs: Long,
    ) {
        logged = trips
        loggedAtMs = nowMs
        this.speeds = speeds
        speedsFor = readFor
        this.drift = drift
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
                )
            } else {
                demoAtMs = null
                s.insights.copy(
                    period = period,
                    trips = logged,
                    speedEfficiency = if (speedsFor == period) speeds else emptyList(),
                    cellDrift = drift,
                    nowMs = loggedAtMs,
                )
            }
        return if (next == s.insights) s else s.copy(insights = next)
    }
}
