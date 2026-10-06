package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.drive.ChargeEta
import com.volttracker.obdpoc.ui.insights.InsightsPeriod
import com.volttracker.obdpoc.ui.insights.SpeedEfficiency
import com.volttracker.obdpoc.ui.live.LiveUiStateStore
import com.volttracker.obdpoc.ui.trips.TripRoute
import com.volttracker.obdpoc.ui.trips.TripSummary
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store's history loading states (loading vs none logged vs a failed read), the clock the
 * history tabs use, and the live readings that must clear rather than linger: stale cluster SOC,
 * a dropped link, and the charge target the time-to-full aims at.
 */
class LiveHistoryAndUnitsTest {
    private val drive = TripSummary("1:10:20", 10L, 20L, 1_000.0)

    private fun sample(
        at: Long,
        block: JSONObject.() -> Unit = {},
    ): JSONObject =
        JSONObject()
            .put("updatedAt", at)
            .put("soc", 63)
            .apply(block)

    @Test
    fun theHistoryTabsSayLoadingUntilTheirFirstRead() {
        val s = LiveUiStateStore().state.value
        assertEquals(HistoryLoad.LOADING, s.charge.history)
        assertEquals(HistoryLoad.LOADING, s.trips.history)
        assertEquals(HistoryLoad.LOADING, s.insights.history)
        assertFalse(s.insights.speedsLoaded)
    }

    @Test
    fun anEmptyReadIsLoadedAndAFailedFirstReadSaysSo() {
        val store = LiveUiStateStore()
        store.onChargeHistory(emptyList())
        assertEquals(HistoryLoad.LOADED, store.state.value.charge.history)

        store.onTripHistoryFailed()
        assertEquals(HistoryLoad.FAILED, store.state.value.trips.history)
        store.onTripHistory(listOf(drive))
        assertEquals(HistoryLoad.LOADED, store.state.value.trips.history)
        // A later failure keeps the list already shown.
        store.onTripHistoryFailed()
        assertEquals(HistoryLoad.LOADED, store.state.value.trips.history)
        assertEquals(listOf(drive), store.state.value.trips.trips)

        store.onInsightsHistoryFailed()
        assertEquals(HistoryLoad.FAILED, store.state.value.insights.history)
        store.onChargeHistoryFailed()
        assertEquals(HistoryLoad.LOADED, store.state.value.charge.history)
    }

    @Test
    fun theDemoIsNeverLoading() {
        val store = LiveUiStateStore()
        store.onChargeHistoryFailed()
        store.onTelemetry(sample(1_000L) { put("source", "demo") })
        val s = store.state.value
        assertEquals(HistoryLoad.LOADED, s.charge.history)
        assertEquals(HistoryLoad.LOADED, s.trips.history)
        assertEquals(HistoryLoad.LOADED, s.insights.history)
        assertTrue(s.insights.speedsLoaded)
    }

    @Test
    fun aRouteThatFailedToReadSaysSoAndIsReadAgain() {
        val store = LiveUiStateStore()
        store.onTripHistory(listOf(drive))
        store.onTripRoute(TripRoute(drive.routeKey, emptyList(), failed = true))
        assertTrue(
            store.state.value.trips.selectedRoute
                ?.failed == true,
        )
        assertEquals(drive.routeKey, store.tripRouteToRead())

        store.onTripRoute(TripRoute(drive.routeKey, emptyList()))
        assertNull("an empty route that did read is not read again", store.tripRouteToRead())
    }

    @Test
    fun insightsKeepEachPeriodsSpeedsAndSayWhenOneIsStillReading() {
        val store = LiveUiStateStore()
        val month = listOf(SpeedEfficiency(40, 4.7))
        val year = listOf(SpeedEfficiency(30, 4.4))
        store.onInsightsHistory(listOf(drive), InsightsPeriod.MONTH, month, null)
        assertTrue(store.state.value.insights.speedsLoaded)

        store.selectInsightsPeriod(InsightsPeriod.YEAR)
        assertFalse(store.state.value.insights.speedsLoaded)
        store.onInsightsHistory(listOf(drive), InsightsPeriod.YEAR, year, null)
        assertEquals(year, store.state.value.insights.speedEfficiency)

        // Switching back shows the month's figures at once rather than emptying the chart.
        store.selectInsightsPeriod(InsightsPeriod.MONTH)
        assertEquals(month, store.state.value.insights.speedEfficiency)
        assertTrue(store.state.value.insights.speedsLoaded)
    }

    @Test
    fun tripsAndInsightsFollowTheClockNotTheTimeOfTheRead() {
        var now = 1_000_000L
        val store = LiveUiStateStore(nowMs = { now })
        store.onTripHistory(listOf(drive))
        store.onInsightsHistory(listOf(drive), InsightsPeriod.MONTH, emptyList(), null)
        now = 90_000_000L
        store.selectTrip(drive.routeKey)
        assertEquals(now, store.state.value.trips.nowMs)
        assertEquals(now, store.state.value.insights.nowMs)
    }

    @Test
    fun theClusterSocGoesStaleLikeTheOtherBroadcasts() {
        val store = LiveUiStateStore()
        store.onTelemetry(sample(1_000L) { put("displayedSocPct", 70.0) })
        assertEquals(70.0, store.state.value.drive.displayedSocPercent ?: 0.0, 0.0)

        store.onTelemetry(sample(2_000L) { put("displayedSocStaleMs", 200_000) })
        assertNull(store.state.value.drive.displayedSocPercent)
        assertNull(store.state.value.charge.displayedSocPercent)
    }

    @Test
    fun theOutsideTemperatureGoesStaleToo() {
        val store = LiveUiStateStore()
        store.onTelemetry(sample(1_000L) { put("outsideTempC", 20.0) })
        assertEquals(68, store.state.value.drive.ambientF)
        store.onTelemetry(sample(2_000L))
        assertEquals(68, store.state.value.drive.ambientF)
        store.onTelemetry(sample(3_000L) { put("outsideTempStaleMs", 200_000) })
        assertNull(store.state.value.drive.ambientF)
    }

    @Test
    fun aDroppedLinkClearsTheLiveReadingsInsteadOfFreezingThem() {
        val store = LiveUiStateStore()
        store.onStatus(JSONObject().put("state", "connected").put("adapter", "OBDLink MX+"))
        store.onTelemetry(
            sample(1_000L) {
                put("powerKw", 21.4)
                put("packVoltage", 364.0)
                put("packCurrentA", 58.8)
                put("batteryTemp", 23)
                put("outsideTempC", 20)
                put("motorAPowerKw", 14.2)
                put("displayedSocPct", 66.0)
            },
        )
        assertEquals(364.0, store.state.value.drive.packVolts ?: 0.0, 0.0)

        store.onStatus(JSONObject().put("state", "disconnected"))
        val d = store.state.value.drive
        assertNull(d.powerKw)
        assertNull(d.packVolts)
        assertNull(d.packAmps)
        assertNull(d.packTempF)
        assertNull(d.ambientF)
        assertNull(d.motorAKw)
        assertNull(d.displayedSocPercent)
        assertNull(d.chargeEta)
    }

    @Test
    fun timeToFullAimsAtTheChargeTarget() {
        val store = LiveUiStateStore()
        store.onSettings { it.copy(chargeTargetPct = 80) }
        val charging: JSONObject.() -> Unit = {
            put("vehicleState", "charging")
            put("chargerPowerKw", 3.5)
            put("chargerAcVoltage", 240.0)
        }
        store.onTelemetry(
            sample(1_000L) {
                charging()
                put("soc", 50)
            },
        )
        // 30 points of 14 kWh at 3.5 kW: 1.2 h, not the 2 h to 100 %.
        assertEquals(ChargeEta.Finish(4_320_000L), store.state.value.drive.chargeEta)
        assertEquals(80, store.state.value.drive.chargeTargetPct)

        store.onTelemetry(
            sample(2_000L) {
                charging()
                put("soc", 79.5)
            },
        )
        assertEquals(ChargeEta.NearlyFull, store.state.value.drive.chargeEta)
        store.onTelemetry(
            sample(3_000L) {
                charging()
                put("soc", 81)
            },
        )
        assertNull("past the target there is nothing to wait for", store.state.value.drive.chargeEta)
    }

    @Test
    fun theUnitsSettingReachesEveryTab() {
        val store = LiveUiStateStore()
        store.onSettings { it.copy(metricUnits = true) }
        val s = store.state.value
        assertTrue(s.drive.units.metric)
        assertTrue(s.charge.units.metric)
        assertTrue(s.trips.units.metric)
        assertTrue(s.insights.units.metric)
        assertTrue(s.car.metricUnits)
    }
}
