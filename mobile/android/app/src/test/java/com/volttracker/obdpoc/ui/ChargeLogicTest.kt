package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.charge.ChargeHistory
import com.volttracker.obdpoc.ui.charge.ChargeSession
import com.volttracker.obdpoc.ui.charge.ChargeUiState
import com.volttracker.obdpoc.ui.charge.SocPoint
import com.volttracker.obdpoc.ui.charge.chartSpan
import com.volttracker.obdpoc.ui.charge.costText
import com.volttracker.obdpoc.ui.charge.eta
import com.volttracker.obdpoc.ui.charge.levelName
import com.volttracker.obdpoc.ui.charge.measuredPoints
import com.volttracker.obdpoc.ui.charge.monthSummary
import com.volttracker.obdpoc.ui.charge.partOfDay
import com.volttracker.obdpoc.ui.charge.projectedPoints
import com.volttracker.obdpoc.ui.charge.rateFor
import com.volttracker.obdpoc.ui.charge.sessionDetail
import com.volttracker.obdpoc.ui.charge.sessionRows
import com.volttracker.obdpoc.ui.charge.sessionWhen
import com.volttracker.obdpoc.ui.charge.subtitle
import com.volttracker.obdpoc.ui.drive.ChargeEta
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** The Charge tab's derived labels, rows, month total and session-curve geometry. */
class ChargeLogicTest {
    private val utc = TimeZone.getTimeZone("UTC")

    // 2026-04-30 21:42 UTC.
    private val now = 1_777_585_320_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun session(
        startedAtMs: Long,
        endedAtMs: Long? = startedAtMs + hour,
        kwh: Double? = 10.0,
        publicCharger: Boolean = false,
    ) = ChargeSession(startedAtMs, endedAtMs, "L2", 30, 90, kwh, publicCharger)

    @Test
    fun parsesStoreRowsNewestFirstAndSkipsUndatedOnes() {
        val rows =
            JSONArray()
                .put(
                    JSONObject()
                        .put("startedAtMs", 1_000L)
                        .put("endedAtMs", 5_000L)
                        .put("chargerType", "level1")
                        .put("startSoc", 40.4)
                        .put("endSoc", 88.6)
                        .put("energyKwh", 6.5),
                ).put(
                    JSONObject()
                        .put("startedAtMs", 9_000L)
                        .put("endedAtMs", JSONObject.NULL)
                        .put("chargerType", "DC fast (CCS)")
                        .put("startSoc", JSONObject.NULL)
                        .put("endSoc", 70)
                        .put("energyKwh", JSONObject.NULL),
                ).put(JSONObject().put("startedAtMs", 0L))
                .put("not an object")
        val sessions = ChargeHistory.parse(rows)
        assertEquals(2, sessions.size)
        val (fast, slow) = sessions
        assertEquals(9_000L, fast.startedAtMs)
        assertNull(fast.endedAtMs)
        assertEquals("DC fast", fast.level)
        assertTrue(fast.publicCharger)
        assertNull(fast.fromSoc)
        assertEquals(70, fast.toSoc)
        assertNull(fast.energyKwh)
        assertEquals("L1", slow.level)
        assertEquals(40, slow.fromSoc)
        assertEquals(88, slow.toSoc) // 88.6 truncates like every other SOC figure
        assertEquals(6.5, slow.energyKwh ?: Double.NaN, 1e-9)
        assertFalse(slow.publicCharger)
    }

    @Test
    fun chargerTypesMapToShortLevelsWithoutGuessing() {
        assertEquals("L2", ChargeHistory.levelFromChargerType("Level 2"))
        assertEquals("L2", ChargeHistory.levelFromChargerType("AC_2"))
        assertEquals("L1", ChargeHistory.levelFromChargerType("level-1"))
        assertNull(ChargeHistory.levelFromChargerType("inferred"))
        assertNull(ChargeHistory.levelFromChargerType(null))
        // A public Level-2 post bills at the public rate but isn't a DC fast charge.
        assertNull(ChargeHistory.levelFromChargerType("public"))
        assertTrue(ChargeHistory.isPublicCharger("public"))
        assertTrue(ChargeHistory.isPublicCharger("Supercharger"))
    }

    @Test
    fun labelsLeaveOutWhatWasNotRecorded() {
        assertEquals("Level 2 · 41% → 71%", sessionDetail("L2", 41, 71))
        assertEquals("Apr 30 · 21:42", sessionWhen(now, utc, h24 = true))
        assertEquals("to 71%", sessionDetail(null, null, 71))
        assertEquals("Charge", sessionDetail(null, null, null))
        assertEquals("Level 2", levelName("L2"))
        assertEquals("Level 1", levelName("L1"))
        assertEquals("DC fast", levelName("DC fast"))
        assertNull(levelName(null))
        assertEquals("$0.52", costText(4.3, 0.12))
        assertNull(costText(4.3, 0.0))
        assertNull(costText(null, 0.12))
        assertEquals("This morning", partOfDay(now - 14 * hour, utc))
        assertEquals("This afternoon", partOfDay(now - 7 * hour, utc))
        assertEquals("Tonight", partOfDay(now, utc))
    }

    @Test
    fun subtitleNamesTheChargerWhilePluggedIn() {
        assertEquals("Plugged in · Level 2", ChargeUiState(charging = true, level = "L2").subtitle)
        assertEquals("Plugged in", ChargeUiState(charging = true).subtitle)
        // Off the link it is the label every tab shares; on it, what the charger is doing.
        assertEquals("Not connected", ChargeUiState(statusLabel = "Not connected").subtitle)
        assertEquals("Connected · not charging", ChargeUiState(connected = true, statusLabel = "Live").subtitle)
    }

    @Test
    fun publicChargesUseThePublicRateOnlyWhenOneIsSet() {
        val state = ChargeUiState(homeRate = 0.12, publicRate = 0.4)
        assertEquals(0.4, state.rateFor(session(now, publicCharger = true)), 1e-9)
        assertEquals(0.12, state.rateFor(session(now)), 1e-9)
        assertEquals(0.12, state.copy(publicRate = 0.0).rateFor(session(now, publicCharger = true)), 1e-9)
    }

    @Test
    fun theLiveChargeLeadsTheListAndReplacesItsStillOpenLoggedRow() {
        val state =
            ChargeUiState(
                charging = true,
                socPercent = 71.0,
                fromSoc = 41.0,
                addedKwh = 4.3,
                level = "L2",
                homeRate = 0.12,
                sampleAtMs = now,
                sessions = listOf(session(now - hour, endedAtMs = null), session(now - day)),
            )
        val rows = state.sessionRows(zone = utc)
        assertEquals(2, rows.size)
        assertTrue(rows[0].live)
        assertEquals("Tonight", rows[0].title)
        assertEquals("Level 2 · 41% → 71%", rows[0].detail)
        assertEquals("$0.52", rows[0].cost)
        assertEquals("Apr 29 · 9:42 PM", rows[1].title)
        assertFalse(rows[1].live)
        // Unplugged, the open row is a finished charge the logger hasn't closed yet: keep it.
        assertEquals(2, state.copy(charging = false).sessionRows(zone = utc).size)
        assertEquals(1, state.sessionRows(limit = 1, zone = utc).size)
    }

    @Test
    fun monthSummaryTotalsThisMonthOnlyWithTheLiveCharge() {
        val state =
            ChargeUiState(
                charging = true,
                addedKwh = 4.0,
                homeRate = 0.1,
                sessions = listOf(session(now - day, kwh = 10.0), session(now - 40 * day, kwh = 99.0)),
            )
        assertEquals("April · 14.0 kWh · $1.40", state.monthSummary(now, utc))
        // No rate set: energy only.
        assertEquals("April · 14.0 kWh", state.copy(homeRate = 0.0).monthSummary(now, utc))
        // Nothing this month and not charging: no summary line.
        assertNull(ChargeUiState(sessions = listOf(session(now - 40 * day))).monthSummary(now, utc))
    }

    @Test
    fun etaRunsToTheChargeLimit() {
        val base = ChargeUiState(charging = true, socPercent = 50.0, chargeKw = 3.5)
        val full = base.eta as ChargeEta.Finish
        val eighty = base.copy(targetSoc = 80).eta as ChargeEta.Finish
        assertTrue(eighty.remainingMs < full.remainingMs)
        // At the limit there's nothing to estimate; unplugged there's no ETA at all.
        assertNull(base.copy(socPercent = 80.0, targetSoc = 80).eta)
        assertNull(base.copy(charging = false).eta)
    }

    @Test
    fun sessionCurveRunsFromStartThroughNowToTheProjectedFinish() {
        val state =
            ChargeUiState.demo.copy(
                socPoints = listOf(SocPoint(ChargeUiState.demo.sampleAtMs - 10 * 60_000L, 66f)),
            )
        val span = state.chartSpan() ?: error("span expected")
        assertEquals(state.startedAtMs, span.startMs)
        assertEquals(state.sampleAtMs, span.nowMs)
        val finish = span.finishMs ?: error("finish expected")
        assertEquals(finish, span.endMs)
        val measured = state.measuredPoints()
        assertEquals(listOf(41f, 66f, 71f), measured.map { it.soc })
        val projected = state.projectedPoints()
        assertEquals(71f, projected.first().soc, 1e-3f)
        assertEquals(100f, projected.last().soc, 1e-3f)
        assertEquals(finish, projected.last().atMs)
        // The taper: the projection climbs faster early than late.
        assertTrue(projected[1].soc - projected[0].soc > projected.last().soc - projected[projected.size - 2].soc)
    }

    @Test
    fun withoutAnEstimateTheCurveStopsAtNow() {
        val state = ChargeUiState.demo.copy(chargeKw = 0.2)
        val span = state.chartSpan() ?: error("span expected")
        assertNull(span.finishMs)
        assertTrue(span.endMs > span.nowMs)
        assertTrue(state.projectedPoints().isEmpty())
        assertNull(ChargeUiState().chartSpan())
        assertTrue(ChargeUiState().measuredPoints().isEmpty())
    }
}
