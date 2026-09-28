package com.volttracker.obdpoc.ui

import androidx.compose.ui.geometry.Size
import com.volttracker.obdpoc.ui.trips.METERS_PER_MILE
import com.volttracker.obdpoc.ui.trips.TripHistory
import com.volttracker.obdpoc.ui.trips.TripMode
import com.volttracker.obdpoc.ui.trips.TripPoint
import com.volttracker.obdpoc.ui.trips.TripRoute
import com.volttracker.obdpoc.ui.trips.TripSummary
import com.volttracker.obdpoc.ui.trips.TripsUiState
import com.volttracker.obdpoc.ui.trips.avgMiPerKwh
import com.volttracker.obdpoc.ui.trips.chipDetail
import com.volttracker.obdpoc.ui.trips.dayGroupLabel
import com.volttracker.obdpoc.ui.trips.efficiencyText
import com.volttracker.obdpoc.ui.trips.electricPct
import com.volttracker.obdpoc.ui.trips.engineOn
import com.volttracker.obdpoc.ui.trips.fitProjection
import com.volttracker.obdpoc.ui.trips.gasMiles
import com.volttracker.obdpoc.ui.trips.groups
import com.volttracker.obdpoc.ui.trips.miPerKwh
import com.volttracker.obdpoc.ui.trips.milesText
import com.volttracker.obdpoc.ui.trips.mode
import com.volttracker.obdpoc.ui.trips.savedVsGas
import com.volttracker.obdpoc.ui.trips.subtitle
import com.volttracker.obdpoc.ui.trips.title
import com.volttracker.obdpoc.ui.trips.whenLine
import com.volttracker.obdpoc.ui.trips.wholeDollars
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** The Trips tab's titles, grouping, month figures, and the route's EV / gas marking. */
class TripsLogicTest {
    private val utc = TimeZone.getTimeZone("UTC")

    // 2026-04-30 21:42 UTC.
    private val now = 1_777_585_320_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private fun trip(
        key: String,
        startedAtMs: Long,
        miles: Double = 10.0,
        kwh: Double? = 2.5,
        evShare: Double? = 1.0,
        label: String = "",
    ) = TripSummary(key, startedAtMs, startedAtMs + 28 * minute, miles * METERS_PER_MILE, kwh, evShare, label)

    @Test
    fun modeFollowsTheElectricShare() {
        assertEquals(TripMode.EV, trip("a", now, evShare = null).mode)
        assertEquals(TripMode.EV, trip("a", now, evShare = 0.99).mode)
        assertEquals(TripMode.MIXED, trip("a", now, evShare = 0.21).mode)
        assertEquals(TripMode.GAS, trip("a", now, evShare = 0.01).mode)
        assertEquals(79.0, trip("a", now, miles = 100.0, evShare = 0.21).gasMiles, 1e-9)
    }

    @Test
    fun titlesUseTheLabelElseThePartOfDay() {
        val morning = now - 13 * hour // 08:42 UTC
        assertEquals("Morning drive", trip("a", morning).title(utc))
        assertEquals("Evening drive", trip("a", now - 2 * hour).title(utc))
        assertEquals("Night drive", trip("a", now - 19 * hour).title(utc))
        assertEquals("Afternoon drive", trip("a", now - 7 * hour).title(utc))
        assertEquals("Commute", trip("a", morning, label = "Commute").title(utc))
        assertEquals("8:42 AM · 28 min", trip("a", morning).whenLine(utc))
    }

    @Test
    fun efficiencyReadsPerMode() {
        assertEquals("4.0 mi/kWh", trip("a", now, miles = 10.0, kwh = 2.5).efficiencyText())
        assertNull("no energy logged", trip("a", now, kwh = null).efficiencyText())
        assertNull("implausible ratio", trip("a", now, miles = 50.0, kwh = 0.5).miPerKwh)
        assertEquals("21% electric", trip("a", now, evShare = 0.21).efficiencyText())
        assertEquals("Gas", trip("a", now, evShare = 0.0).efficiencyText())
        assertEquals("Apr 30 · 184.2 mi · 21% electric", trip("a", now, miles = 184.2, evShare = 0.21).chipDetail(utc))
        assertEquals("38 mi", milesText(38.2))
        assertEquals("6.1 mi", milesText(6.14))
        assertEquals("$38", wholeDollars(37.6))
        assertEquals("-$4", wholeDollars(-4.2))
    }

    @Test
    fun drivesGroupByDayNewestFirst() {
        val state =
            TripsUiState(
                trips =
                    listOf(
                        trip("a", now - hour),
                        trip("b", now - day),
                        trip("c", now - day - hour),
                        trip(
                            "d",
                            now - 3 * day,
                        ),
                    ),
                nowMs = now,
            )
        val groups = state.groups(utc)
        assertEquals(listOf("Today", "Yesterday", "Apr 27"), groups.map { it.label })
        assertEquals(listOf("b", "c"), groups[1].trips.map { it.routeKey })
        assertEquals("Dec 30, 2025", dayGroupLabel(now - 121 * day, now, utc))
    }

    @Test
    fun monthFiguresSummariseThisMonthsDrives() {
        val state =
            TripsUiState(
                trips =
                    listOf(
                        trip("a", now - hour, miles = 20.0, kwh = 5.0),
                        trip("b", now - day, miles = 100.0, kwh = 5.0, evShare = 0.2),
                        trip("old", now - 40 * day, miles = 500.0),
                    ),
                nowMs = now,
                homeRate = 0.10,
                gasMpg = 40.0,
                gasPrice = 4.0,
            )
        assertEquals("2 drives · 120 mi · April", state.subtitle(utc))
        // EV miles 20 + 20 of 120 → 33 %.
        assertEquals(33, state.electricPct(utc))
        // 40 EV miles over 10 kWh.
        assertEquals(4.0, state.avgMiPerKwh(utc) ?: 0.0, 1e-9)
        // 40 EV mi / 40 mpg × $4 − 10 kWh × $0.10 = $3.
        assertEquals(3.0, state.savedVsGas(utc) ?: 0.0, 1e-9)
        assertNull("needs an MPG", state.copy(gasMpg = null).savedVsGas(utc))
        assertEquals("No drives yet in April", TripsUiState(nowMs = now).subtitle(utc))
        assertNull(TripsUiState(nowMs = now).electricPct(utc))
        assertEquals("1 drive · 20 mi · April", state.copy(trips = state.trips.take(1)).subtitle(utc))
    }

    @Test
    fun rowsParseNewestFirstAndSkipUndatedOnes() {
        val rows =
            JSONArray()
                .put(row("1:100:200", 100L).put("evShare", 0.4).put("energyKwh", 1.5).put("label", " Tahoe "))
                .put(row("1:300:400", 300L).put("evShare", JSONObject.NULL))
                .put(row("", 500L))
                .put(row("2:0:0", 0L))
        val trips = TripHistory.parse(rows)
        assertEquals(listOf("1:300:400", "1:100:200"), trips.map { it.routeKey })
        assertNull(trips[0].evShare)
        assertEquals(0.4, trips[1].evShare ?: 0.0, 1e-9)
        assertEquals(1.5, trips[1].energyKwh ?: 0.0, 1e-9)
        assertEquals("Tahoe", trips[1].label)
    }

    @Test
    fun routePointsTakeTheModeInForceAtTheirTime() {
        val route =
            JSONObject().put(
                "points",
                JSONArray()
                    .put(point(0.0, 1_000L))
                    .put(point(0.001, 2_000L))
                    .put(point(0.002, 3_000L))
                    .put(JSONObject().put("lat", "x").put("lng", 1.0)),
            )
        val modes = JSONArray().put(mode(1_500L, false)).put(mode(2_500L, true))
        val parsed = TripHistory.route("k", route, modes, fallbackGas = false)
        // Before the first change the first mode holds; the bad point is dropped.
        assertEquals(listOf(false, false, true), parsed.points.map { it.gas })
        val unclassified = TripHistory.route("k", route, JSONArray(), fallbackGas = true)
        assertTrue(unclassified.points.all { it.gas })
    }

    @Test
    fun engineOnMarksTheFirstSwitchToGas() {
        val points = (0..4).map { TripPoint(0.0, it * 0.01, it * 1_000L, gas = it >= 3) }
        val mark = TripRoute("k", points).engineOn() ?: error("expected a mark")
        assertEquals(3, mark.index)
        // Three of the track's four equal legs.
        assertEquals(0.75, mark.fraction, 1e-6)
        assertNull(TripRoute("k", points.map { it.copy(gas = true) }).engineOn())
    }

    @Test
    fun theMapFitKeepsTheRoutesShape() {
        val points = listOf(TripPoint(0.0, 0.0, 0L), TripPoint(0.0, 0.02, 1L), TripPoint(0.01, 0.02, 2L))
        val project = fitProjection(points, Size(400f, 300f), padSide = 20f, padTop = 20f, padBottom = 80f)
        val a = project(points[0])
        val b = project(points[1])
        val c = project(points[2])
        // Twice as wide as tall: the 360 px width binds, so the height is 180 px, centred in 200.
        assertEquals(360f, b.x - a.x, 0.5f)
        assertEquals(180f, a.y - c.y, 0.5f)
        assertEquals(20f, a.x, 0.5f)
        assertEquals(30f, c.y, 0.5f)
    }

    private fun row(
        id: String,
        startedAtMs: Long,
    ) = JSONObject()
        .put("id", id)
        .put("startedAtMs", startedAtMs)
        .put("endedAtMs", startedAtMs + 50L)
        .put("distanceMeters", 1_000.0)

    private fun point(
        lon: Double,
        atMs: Long,
    ) = JSONObject().put("lat", 0.0).put("lng", lon).put("atMs", atMs)

    private fun mode(
        atMs: Long,
        gas: Boolean,
    ) = JSONObject().put("atMs", atMs).put("gas", gas)
}
