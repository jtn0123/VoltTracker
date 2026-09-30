package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.charge.ChargeSession
import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.charge.ChargeWorth
import com.volttracker.obdpoc.ui.charge.chargeTitle
import com.volttracker.obdpoc.ui.charge.chargeWhen
import com.volttracker.obdpoc.ui.charge.receipt
import com.volttracker.obdpoc.ui.charge.session
import com.volttracker.obdpoc.ui.diag.SohHistory
import com.volttracker.obdpoc.ui.diag.SohPoint
import com.volttracker.obdpoc.ui.diag.sohTrend
import com.volttracker.obdpoc.ui.insights.PeriodSummary
import com.volttracker.obdpoc.ui.insights.PeriodWindow
import com.volttracker.obdpoc.ui.insights.story
import com.volttracker.obdpoc.ui.receipt.Receipt
import com.volttracker.obdpoc.ui.receipt.ReceiptLine
import com.volttracker.obdpoc.ui.receipt.money
import com.volttracker.obdpoc.ui.trips.TripHistory
import com.volttracker.obdpoc.ui.trips.TripSummary
import com.volttracker.obdpoc.ui.trips.TripsDemo
import com.volttracker.obdpoc.ui.trips.TripsUiState
import com.volttracker.obdpoc.ui.trips.gasGallons
import com.volttracker.obdpoc.ui.trips.miles
import com.volttracker.obdpoc.ui.trips.receipt
import com.volttracker.obdpoc.ui.trips.receiptWhen
import com.volttracker.obdpoc.ui.units.METERS_PER_MILE
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** The usefulness pass: trip and charge receipts, Insights in plain sentences, and the battery trend. */
class UsefulnessLogicTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val demo = TripsUiState.demo

    private fun Receipt.value(label: String): String? = (details + costs).firstOrNull { it.label == label }?.value

    private fun Receipt.line(label: String): ReceiptLine? = (details + costs).firstOrNull { it.label == label }

    private fun demoTrip(key: String) = demo.trips.first { it.routeKey == key }

    @Test
    fun aMixedDriveReceiptSplitsTheMilesAndPricesBothFuels() {
        val r = demo.receipt(demoTrip(TripsDemo.MIXED_KEY), utc)
        assertEquals("Tahoe weekend", r.title)
        assertEquals("184.2 mi", r.value("Distance"))
        assertEquals("2 hr 53 min", r.value("Time"))
        assertEquals("38.7 mi · 21%", r.value("On electric"))
        assertEquals("145.5 mi · 79%", r.value("On gas"))
        assertEquals("10.2 kWh", r.value("Battery used"))
        assertNull("logged energy is not an estimate", r.line("Battery used")?.note)
        assertEquals("3.8 gal", r.value("Gas used"))
        assertEquals("est.", r.line("Gas used")?.note)
        assertEquals("64 mph", r.value("Average speed"))
        assertEquals("80 mph", r.value("Top speed"))
        assertEquals("61°F", r.value("Outside"))
        assertEquals(listOf("Electricity", "Gas", "Total"), r.costs.map { it.label })
        assertEquals("$1.22", r.value("Electricity"))
        assertEquals("$16.43", r.value("Gas"))
        assertEquals("$17.65", r.value("Total"))
        assertEquals("Driving on electric saved about $3.14 over gas.", r.saved)
    }

    @Test
    fun anElectricDriveHasNoGasLinesAndWorksInMetric() {
        val r = demo.copy(metricUnits = true).receipt(demoTrip("demo:1"), utc)
        assertNull(r.line("On gas"))
        assertNull(r.line("Gas used"))
        assertEquals("29.6 km", r.value("Distance"))
        assertEquals("15.2 kWh/100 km", r.value("Efficiency"))
        assertEquals(listOf("Electricity", "Total"), r.costs.map { it.label })
        assertEquals("Driving on electric saved about $1.54 over gas.", r.saved)
    }

    @Test
    fun aDriveWithoutEnergyOrRatesEstimatesAndLeavesTheCostOff() {
        val bare = TripSummary("k", 0L, 600_000L, 5 * METERS_PER_MILE)
        val r = TripsUiState(trips = listOf(bare)).receipt(bare, utc)
        assertEquals("est.", r.line("Battery used")?.note)
        assertNull(r.line("Average speed"))
        assertNull(r.line("Outside"))
        assertTrue(r.costs.isEmpty())
        assertNull(r.saved)
        assertNull(bare.gasGallons(38.0))
        // An estimated energy is flagged on its cost line too.
        val priced = TripsUiState(trips = listOf(bare), homeRate = 0.2).receipt(bare, utc)
        assertEquals("est.", priced.line("Electricity")?.note)
    }

    @Test
    fun receiptsShareAsPlainText() {
        val r =
            Receipt(
                "Evening drive",
                "Thu, Apr 30 · 8:14 PM – 8:42 PM",
                listOf(ReceiptLine("Distance", "12.0 mi"), ReceiptLine("Gas used", "0.2 gal", "est.")),
                listOf(ReceiptLine("Total", "$0.90")),
                "Saved a lot.",
            )
        assertEquals(
            "Evening drive\nThu, Apr 30 · 8:14 PM – 8:42 PM\n\nDistance: 12.0 mi\nGas used: 0.2 gal (est.)\n" +
                "Total: $0.90\n\nSaved a lot.\n\n— VoltTracker",
            r.shareText(),
        )
        assertEquals("-$0.30", money(-0.3))
        assertEquals("$0.00", money(-0.001))
    }

    @Test
    fun theReceiptTimeLineGivesTheDayAndBothEnds() {
        val trip = TripSummary("k", 1_777_536_840_000L, 1_777_538_520_000L, 1000.0)
        assertEquals("Thu, Apr 30 · 8:14 AM – 8:42 AM", receiptWhen(trip, utc))
        assertEquals("Thu, Apr 30 · 8:14 – 8:42", receiptWhen(trip, utc, h24 = true))
    }

    @Test
    fun tripRowsParseSpeedAndTemperature() {
        val row =
            JSONObject()
                .put("id", "1:1:2")
                .put("startedAtMs", 1_000L)
                .put("endedAtMs", 2_000L)
                .put("distanceMeters", 1000.0)
                .put("maxSpeedKph", 110.0)
                .put("avgMovingSpeedKph", 52.5)
                .put("avgOutsideTempC", -3.0)
        val trip = TripHistory.parse(JSONArray().put(row)).single()
        assertEquals(110.0, trip.maxSpeedKph ?: 0.0, 1e-9)
        assertEquals(52.5, trip.avgSpeedKph ?: 0.0, 1e-9)
        assertEquals(-3.0, trip.outsideTempC ?: 0.0, 1e-9)
        val zero =
            TripHistory
                .parse(
                    JSONArray().put(row.put("maxSpeedKph", 0.0).put("avgOutsideTempC", JSONObject.NULL)),
                ).single()
        assertNull("a zero top speed is no reading", zero.maxSpeedKph)
        assertNull(zero.outsideTempC)
    }

    @Test
    fun aChargeReceiptAddsUpEnergyPowerCostAndRange() {
        val state = ChargeUiState.demo
        val s = state.sessions.first()
        assertEquals(s, state.session(s.startedAtMs))
        assertNull(state.session(-1L))
        val r = state.receipt(s, ChargeWorth(4.0, 38.0, 4.29), utc)
        assertEquals("Level 2 charge", r.title)
        assertEquals("24% → 91% (+67)", r.value("Battery"))
        assertEquals("Level 2", r.value("Charger"))
        assertEquals("3 hr 24 min", r.value("Plugged in"))
        assertEquals("11.8 kWh", r.value("Energy added"))
        assertEquals("3.5 kW", r.value("Average power"))
        assertEquals("47 mi", r.value("Range added"))
        assertNull("measured efficiency is not an estimate", r.line("Range added")?.note)
        assertEquals("$0.12/kWh", r.value("Rate"))
        assertEquals("home", r.line("Rate")?.note)
        assertEquals("$1.42", r.value("Total"))
        assertEquals("Those miles would cost about $5.33 on gas — this charge saved $3.91.", r.saved)
    }

    @Test
    fun aSparseChargeReceiptLeavesOutWhatWasntLogged() {
        val s = ChargeSession(1_000L, null, null, null, 80, null, publicCharger = true)
        val state = ChargeUiState(sessions = listOf(s), publicRate = 0.4)
        val r = state.receipt(s, ChargeWorth(null, null, 0.0), utc)
        assertEquals("Public charge", r.title)
        assertEquals("to 80%", r.value("Battery"))
        assertEquals("public", r.value("Charger"))
        assertNull(r.line("Plugged in"))
        assertNull(r.line("Energy added"))
        assertTrue(r.costs.isEmpty())
        assertNull(r.saved)
        assertEquals("Charge", chargeTitle(ChargeSession(0L, null, null, null, null, null)))
        assertEquals("Thu, Jan 1 · 12:00 AM", chargeWhen(s, utc))
        // A public charge is billed at the public rate, and range is estimated without drives.
        val priced = state.receipt(s.copy(energyKwh = 10.0), ChargeWorth(null, 40.0, 4.0), utc)
        assertEquals("public", priced.line("Rate")?.note)
        assertEquals("$4.00", priced.value("Total"))
        assertEquals("est.", priced.line("Range added")?.note)
        assertNull("gas would have cost less than this charge", priced.saved)
    }

    private fun trip(
        key: String,
        miles: Double,
        evShare: Double?,
        kwh: Double? = null,
        label: String = "",
    ) = TripSummary(key, 0L, 60_000L, miles * METERS_PER_MILE, kwh, evShare, label)

    private fun period(
        trips: List<TripSummary>,
        electricPct: Int? = 90,
        deltaPts: Int? = null,
        saved: Double? = null,
        miPerKwh: Double? = null,
    ) = PeriodSummary(
        window = PeriodWindow(0L, 1L, "April 2026", emptyList(), emptyList(), 0L..1L, "March"),
        trips = trips,
        electricPct = electricPct,
        deltaPts = deltaPts,
        evMiles = trips.sumOf { it.miles * (it.evShare ?: 1.0) },
        gasMiles = trips.sumOf { it.miles * (1 - (it.evShare ?: 1.0)) },
        totalMiles = trips.sumOf { it.miles },
        buckets = emptyList(),
        saved = saved,
        kwh = 0.0,
        miPerKwh = miPerKwh,
    )

    @Test
    fun insightsTellThePeriodInPlainSentences() {
        val trips = listOf(trip("a", 100.0, 0.5, label = "Road trip"), trip("b", 20.0, 1.0))
        assertEquals(
            listOf(
                "You drove 120 mi over 2 drives, 90% of it on electricity.",
                "That's 6 points more electric than March.",
                "The engine covered 50 mi, mostly on one drive (Road trip).",
                "Driving on electricity saved about $41 over gas, at 4.1 mi/kWh.",
            ),
            period(trips, deltaPts = 6, saved = 41.2, miPerKwh = 4.1).story(zone = utc),
        )
    }

    @Test
    fun insightsSentencesCoverEveryCase() {
        val ev = listOf(trip("a", 10.0, 1.0))
        assertEquals(
            listOf(
                "You drove 10 mi over 1 drive, 100% of it on electricity.",
                "That's 1 point less electric than March.",
                "The engine never had to drive the car.",
                "Your electric driving averaged 4.0 mi/kWh.",
            ),
            period(ev, electricPct = 100, deltaPts = -1, miPerKwh = 4.0).story(zone = utc),
        )
        assertEquals(
            "That's the same electric share as March.",
            period(ev, deltaPts = 0).story(zone = utc)?.get(1),
        )
        val spread = listOf(trip("a", 10.0, 0.5), trip("b", 10.0, 0.5), trip("c", 10.0, 0.5))
        assertEquals(
            listOf(
                "You drove 30 mi over 3 drives, 50% of it on electricity.",
                "The engine covered 15 mi across a few drives.",
                "Driving on electricity saved about $5 over gas.",
            ),
            period(spread, electricPct = 50, saved = 5.0).story(zone = utc),
        )
        assertEquals(listOf("You drove 10 mi over 1 drive."), period(ev, electricPct = null).story(zone = utc))
        assertNull(period(emptyList()).story(zone = utc))
    }

    private val day = 86_400_000L

    @Test
    fun theBatteryTrendNeedsASecondDay() {
        val oneDay = sohTrend(listOf(SohPoint(0L, 95.0), SohPoint(3_600_000L, 94.0)), utc)
        assertEquals(1, oneDay.days.size)
        assertEquals(94.5, oneDay.days.single().pct, 1e-9)
        assertFalse(oneDay.drawable)
        assertEquals("The trend appears once the car has reported on a second day.", oneDay.sentence)
    }

    @Test
    fun theBatteryTrendSaysWhereHealthWent() {
        // Mar 1 2026 onwards.
        val start = 1_772_323_200_000L
        val steady = sohTrend(listOf(SohPoint(start, 95.0), SohPoint(start + day, 95.3)), utc)
        assertEquals("Steady since Mar 2026.", steady.sentence)
        assertTrue(steady.drawable)
        val down = sohTrend(listOf(SohPoint(start, 95.0), SohPoint(start + 30 * day, 94.0)), utc)
        assertEquals("Down 1 point since Mar 2026.", down.sentence)
        val yearly = sohTrend(listOf(SohPoint(start, 96.0), SohPoint(start + 365 * day, 93.5)), utc)
        assertEquals("Down 2.5 points since Mar 2026, about 2.5 points a year.", yearly.sentence)
        val up = sohTrend(listOf(SohPoint(start, 90.0), SohPoint(start + day, 92.0)), utc)
        assertEquals("Up 2 points since Mar 2026 — reads vary a little with temperature.", up.sentence)
        // A single noisy read in a day is outvoted by that day's median.
        val noisy =
            sohTrend(
                listOf(
                    SohPoint(start, 95.0),
                    SohPoint(start + 1, 95.0),
                    SohPoint(start + 2, 80.0),
                    SohPoint(start + day, 95.0),
                ),
                utc,
            )
        assertEquals("Steady since Mar 2026.", noisy.sentence)
    }

    @Test
    fun batteryReadsParseFromHealthOrAmpHours() {
        val rows =
            JSONArray()
                .put(JSONObject().put("capturedAtMs", 1L).put("sohPct", 93.0))
                .put(JSONObject().put("capturedAtMs", 2L).put("capacityAh", 26.0))
                .put(JSONObject().put("capturedAtMs", 3L).put("sohPct", 250.0))
                .put(JSONObject().put("capturedAtMs", 0L).put("sohPct", 90.0))
                .put(JSONObject().put("capturedAtMs", 4L))
        assertEquals(listOf(SohPoint(1L, 93.0), SohPoint(2L, 50.0)), SohHistory.parse(rows))
        val demoReads = SohHistory.demo(1_777_585_320_000L)
        assertEquals(18, demoReads.size)
        assertEquals(96.0, demoReads.first().pct, 1e-9)
        assertEquals(91.0, demoReads.last().pct, 1e-9)
    }
}
