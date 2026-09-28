package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.insights.InsightsHistory
import com.volttracker.obdpoc.ui.insights.InsightsPeriod
import com.volttracker.obdpoc.ui.insights.InsightsUiState
import com.volttracker.obdpoc.ui.insights.SpeedEfficiency
import com.volttracker.obdpoc.ui.insights.best
import com.volttracker.obdpoc.ui.insights.deltaText
import com.volttracker.obdpoc.ui.insights.dollarsAndCents
import com.volttracker.obdpoc.ui.insights.midMph
import com.volttracker.obdpoc.ui.insights.summary
import com.volttracker.obdpoc.ui.insights.wholeMiles
import com.volttracker.obdpoc.ui.insights.window
import com.volttracker.obdpoc.ui.trips.TripSummary
import com.volttracker.obdpoc.ui.units.METERS_PER_MILE
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

/** The Insights tab's periods, figures and bars, worked out from logged drives. */
class InsightsLogicTest {
    private val utc = TimeZone.getTimeZone("UTC")

    // Thu 2026-04-30 21:42 UTC.
    private val now = 1_777_585_320_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    // 2026-04-01 00:00 UTC.
    private val april1 = 1_775_001_600_000L

    private fun trip(
        startedAtMs: Long,
        miles: Double,
        evShare: Double? = 1.0,
        kwh: Double? = null,
    ) = TripSummary("k$startedAtMs", startedAtMs, startedAtMs + hour, miles * METERS_PER_MILE, kwh, evShare)

    @Test
    fun aMonthIsSplitIntoWeeksFromTheFirst() {
        val w = InsightsPeriod.MONTH.window(now, firstTripMs = null, zone = utc)
        assertEquals(april1, w.startMs)
        assertEquals(april1 + 30 * day, w.endMs)
        assertEquals("April 2026", w.title)
        assertEquals(listOf("Apr 1", "8", "15", "22", "29"), w.bucketLabels)
        assertEquals("March", w.previousName)
        assertEquals(april1 - 31 * day, w.previous?.first)
    }

    @Test
    fun aWeekRunsSundayToSaturdayByDay() {
        val w = InsightsPeriod.WEEK.window(now, firstTripMs = null, zone = utc)
        assertEquals(april1 + 25 * day, w.startMs)
        assertEquals("Apr 26 – May 2", w.title)
        assertEquals(listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"), w.bucketLabels)
        assertEquals("last week", w.previousName)
    }

    @Test
    fun aYearIsSplitIntoMonths() {
        val w = InsightsPeriod.YEAR.window(now, firstTripMs = null, zone = utc)
        assertEquals("2026", w.title)
        assertEquals("JFMAMJJASOND".map { it.toString() }, w.bucketLabels)
        assertEquals("2025", w.previousName)
    }

    @Test
    fun allTimeUsesMonthsForAYearAndYearsBeyond() {
        val short = InsightsPeriod.ALL.window(now, firstTripMs = april1 - 40 * day, zone = utc)
        assertEquals("Since Feb 2026", short.title)
        assertEquals(listOf("Feb", "Mar", "Apr"), short.bucketLabels)
        assertNull(short.previousName)
        val long = InsightsPeriod.ALL.window(now, firstTripMs = april1 - 400 * day, zone = utc)
        assertEquals("Since Feb 2025", long.title)
        assertEquals(listOf("2025", "2026"), long.bucketLabels)
    }

    @Test
    fun theMonthSummaryWeighsElectricMilesAndComparesWithLastMonth() {
        val state =
            InsightsUiState(
                trips =
                    listOf(
                        trip(april1 + hour, 100.0, evShare = 0.8, kwh = 20.0),
                        trip(april1 + 9 * day, 50.0, evShare = null),
                        trip(april1 + 16 * day, 100.0, evShare = 0.6, kwh = 15.0),
                        trip(april1 - 10 * day, 100.0, evShare = 0.5),
                    ),
                nowMs = now,
                homeRate = 0.10,
                gasMpg = 40.0,
                gasPrice = 4.0,
            )
        val s = state.summary(utc)
        // Classified: 140 EV of 200 mi = 70 %; March was 50 %.
        assertEquals(70, s.electricPct)
        assertEquals(20, s.deltaPts)
        assertEquals("▲ 20 pts vs March", s.deltaText())
        // The unclassified drive counts as electric in the miles, as on the Trips tab.
        assertEquals(190.0, s.evMiles, 1e-9)
        assertEquals(60.0, s.gasMiles, 1e-9)
        assertEquals(250.0, s.totalMiles, 1e-9)
        assertEquals(listOf(80.0, 50.0, 60.0, 0.0, 0.0), s.buckets.map { it.evMiles })
        assertEquals(listOf(20.0, 0.0, 40.0, 0.0, 0.0), s.buckets.map { it.gasMiles })
        assertEquals(35.0, s.kwh, 1e-9)
        // 140 EV miles over 35 kWh.
        assertEquals(4.0, s.miPerKwh ?: 0.0, 1e-9)
        // 190 EV mi / 40 mpg × $4 − (35 kWh + 50 mi / 3.5) × $0.10.
        assertEquals(190.0 / 40.0 * 4.0 - (35.0 + 50.0 / 3.5) * 0.10, s.saved ?: 0.0, 1e-9)
        assertNull("needs an MPG", state.copy(gasMpg = null).summary(utc).saved)
    }

    @Test
    fun deltaReadsDownOrLevel() {
        val march = trip(april1 - 10 * day, 100.0, evShare = 0.5)
        val down = InsightsUiState(trips = listOf(trip(april1 + hour, 10.0, evShare = 0.3), march), nowMs = now)
        assertEquals("▼ 20 pts vs March", down.summary(utc).deltaText())
        val level = down.copy(trips = listOf(trip(april1 + hour, 10.0, evShare = 0.5), march))
        assertEquals("Same as March", level.summary(utc).deltaText())
        val none = InsightsUiState(trips = listOf(trip(april1 + hour, 10.0)), nowMs = now)
        assertNull(none.summary(utc).deltaText())
        assertNull(none.copy(period = InsightsPeriod.ALL).summary(utc).deltaText())
    }

    @Test
    fun nothingLoggedLeavesTheFiguresEmpty() {
        val s = InsightsUiState(nowMs = now).summary(utc)
        assertNull(s.electricPct)
        assertNull(s.miPerKwh)
        assertEquals(0.0, s.kwh, 0.0)
        assertEquals(5, s.buckets.size)
    }

    @Test
    fun moneyAndMilesFormat() {
        assertEquals("$41" to ".20", dollarsAndCents(41.2))
        assertEquals("-$4" to ".50", dollarsAndCents(-4.5))
        assertEquals("$1,234" to ".57", dollarsAndCents(1234.567))
        assertEquals("1,041", wholeMiles(1040.6))
    }

    @Test
    fun speedBandsParseAndTheBestIsHighlighted() {
        val rows =
            JSONArray()
                .put(JSONObject().put("mph", 30).put("miPerKwh", 4.6))
                .put(JSONObject().put("mph", 40).put("miPerKwh", 4.7))
                .put(JSONObject().put("mph", 50).put("miPerKwh", Double.NaN.toString()))
                .put(JSONObject().put("miPerKwh", 3.0))
        val bands = InsightsHistory.speeds(rows)
        assertEquals(listOf(SpeedEfficiency(30, 4.6), SpeedEfficiency(40, 4.7)), bands)
        assertEquals(45, bands.best()?.midMph)
        assertNull(emptyList<SpeedEfficiency>().best())
    }

    @Test
    fun cellDriftParsesOnlyAReportedCell() {
        assertNull(InsightsHistory.cellDrift(JSONObject()))
        val drift =
            InsightsHistory.cellDrift(
                JSONObject()
                    .put("cell", 47)
                    .put("belowMeanMv", 18)
                    .put("driftMv", 12)
                    .put("days", 14),
            )
        assertEquals(47, drift?.cell)
        assertEquals(18, drift?.belowMeanMv)
        assertEquals(12, drift?.driftMv)
        assertEquals(14, drift?.days)
    }
}
