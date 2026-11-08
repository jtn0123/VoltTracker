package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.charge.ChargeHistory
import com.volttracker.obdpoc.ui.charge.ChargeSession
import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.charge.ChargeWorth
import com.volttracker.obdpoc.ui.charge.ESTIMATE_NOTE
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

    private companion object {
        // Wed Apr 29 2026 6:00 PM → Thu Apr 30 7:30 AM, UTC.
        const val INFERRED_FROM = 1_777_485_600_000L
        const val INFERRED_TO = 1_777_534_200_000L
    }

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
        assertEquals(s, state.session(s.id))
        assertNull(state.session("charge:-1"))
        assertNull(state.session(null))
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
    fun anInferredStoreRowReachesItsReceiptAndShareTextAsAnEstimate() {
        // The row exactly as ObdStoreChargeSummary writes an inferred charge between two drives.
        val row =
            JSONObject()
                .put("id", "inferred:7:$INFERRED_FROM:8:$INFERRED_TO")
                .put("startedAtMs", INFERRED_FROM)
                .put("endedAtMs", INFERRED_TO)
                .put("chargerType", "inferred")
                .put("startSoc", 20.0)
                .put("endSoc", 90.0)
                .put("powerKw", JSONObject.NULL)
                .put("energyKwh", 9.8)
                .put("confidence", 0.7)
                .put("provenance", "inferred")
        val s = ChargeHistory.parse(JSONArray().put(row)).single()
        val state = ChargeUiState(sessions = listOf(s), homeRate = 0.12)
        assertEquals(s, state.session("inferred:7:$INFERRED_FROM:8:$INFERRED_TO"))
        val r = state.receipt(s, ChargeWorth(4.0, 38.0, 4.29), utc)
        assertEquals("Estimated charge", r.title)
        assertEquals("Wed, Apr 29 · between 6:00 PM and 7:30 AM", r.whenLine)
        assertEquals("20% → 90% (+70)", r.value("Battery"))
        assertEquals("Unknown", r.value("Charger"))
        // The span is when the charge could have happened, not how long the car was plugged in.
        assertEquals("13 hr 30 min", r.value("Observation window"))
        assertNull(r.line("Plugged in"))
        assertNull("no average power over a window the car wasn't plugged in for", r.line("Average power"))
        assertEquals(ReceiptLine("Energy added", "9.8 kWh", "est."), r.line("Energy added"))
        assertEquals("est.", r.line("Range added")?.note)
        assertEquals(ReceiptLine("Total", "$1.18", "est."), r.line("Total"))
        assertEquals("Those miles would cost about $4.43 on gas — this charge saved about $3.25.", r.saved)
        assertEquals(ESTIMATE_NOTE, r.note)
        val text = r.shareText()
        assertTrue(text.startsWith("Estimated charge\nWed, Apr 29 · between 6:00 PM and 7:30 AM\n$ESTIMATE_NOTE\n\n"))
        assertTrue(text.contains("Observation window: 13 hr 30 min\n"))
        assertTrue(text.contains("Energy added: 9.8 kWh (est.)\n"))
        assertTrue(text.contains("Total: $1.18 (est.)\n"))
        assertFalse(text.contains("Plugged in"))
        assertFalse(text.contains("Average power"))
        assertFalse("the row id stays out of the share text", text.contains("inferred:"))

        // The same charge watched by the adapter keeps the measured wording, and no note.
        val observedRow = JSONObject(row.toString()).put("provenance", "observed")
        val measured = ChargeHistory.parse(JSONArray().put(observedRow)).single()
        val m = state.copy(sessions = listOf(measured)).receipt(measured, ChargeWorth(4.0, 38.0, 4.29), utc)
        assertEquals("Charge", m.title)
        assertEquals("Wed, Apr 29 · 6:00 PM – 7:30 AM", m.whenLine)
        assertEquals("13 hr 30 min", m.value("Plugged in"))
        assertEquals(ReceiptLine("Energy added", "9.8 kWh"), m.line("Energy added"))
        assertEquals("0.7 kW", m.value("Average power"))
        assertNull(m.note)
        assertTrue(m.shareText().startsWith("Charge\nWed, Apr 29 · 6:00 PM – 7:30 AM\n\n"))
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
