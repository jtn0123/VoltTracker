package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.components.PillTone
import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.diag.DtcCode
import com.volttracker.obdpoc.ui.diag.DtcSeverity
import com.volttracker.obdpoc.ui.diag.HealthHero
import com.volttracker.obdpoc.ui.diag.HealthLine
import com.volttracker.obdpoc.ui.diag.adapterLine
import com.volttracker.obdpoc.ui.diag.agoText
import com.volttracker.obdpoc.ui.diag.detailLine
import com.volttracker.obdpoc.ui.diag.earlierLine
import com.volttracker.obdpoc.ui.diag.freezeFrameLine
import com.volttracker.obdpoc.ui.diag.hasFreezeFrame
import com.volttracker.obdpoc.ui.diag.healthReport
import com.volttracker.obdpoc.ui.diag.hero
import com.volttracker.obdpoc.ui.diag.hvBattery
import com.volttracker.obdpoc.ui.diag.liveSignalsLine
import com.volttracker.obdpoc.ui.diag.pill
import com.volttracker.obdpoc.ui.diag.safeToDrive
import com.volttracker.obdpoc.ui.diag.statusCounts
import com.volttracker.obdpoc.ui.diag.summary
import com.volttracker.obdpoc.ui.diag.title
import com.volttracker.obdpoc.ui.diag.weakestLabel
import com.volttracker.obdpoc.ui.drive.DriveUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Health's copy: code names, pills, the safe-to-drive verdict, the hero and the shared report. */
class HealthLogicTest {
    private val now = DiagUiState.DEMO_NOW_MS
    private val demo = DiagUiState.demo
    private val catalyst = demo.codes.orEmpty()[0]
    private val cam = demo.codes.orEmpty()[1]
    private val isolation =
        DtcCode(
            code = "P0AA6",
            description = "Hybrid battery voltage system isolation fault",
            category = "HV battery",
            severity = DtcSeverity.ALERT,
        )

    @Test
    fun agoTextRoundsDown() {
        assertEquals("just now", agoText(now - 30_000L, now))
        assertEquals("just now", agoText(now + 60_000L, now))
        assertEquals("4 min ago", agoText(now - 4 * MIN, now))
        assertEquals("2 h ago", agoText(now - 150 * MIN, now))
        assertEquals("1 day ago", agoText(now - 30 * HOUR, now))
        assertEquals("3 days ago", agoText(now - 72 * HOUR, now))
    }

    @Test
    fun aShortTrailingQualifierMovesToTheDetailLine() {
        assertEquals("Catalyst system efficiency below threshold", catalyst.title)
        assertEquals("Bank 1 · seen 4× · first 3 days ago", catalyst.detailLine(now))
        assertEquals("Bank 1 · pending · seen once", cam.detailLine(now))
        val long = DtcCode("P0001", description = "Fuel volume regulator (control circuit open or shorted)")
        assertEquals(long.description, long.title)
        assertEquals("seen once", long.detailLine(now))
        assertEquals("permanent · seen once", DtcCode("P0300", status = "permanent").detailLine(now))
        assertEquals("freeze frame · seen once", DtcCode("P0300", status = "freeze-frame").detailLine(now))
    }

    @Test
    fun anUnlistedCodeIsNamedByItsFamily() {
        assertEquals("Ignition system or misfire", DtcCode("P0399").title)
        assertEquals("Unrecognized code", DtcCode("X1").title)
    }

    @Test
    fun pillsSayWhatToDo() {
        assertEquals(HealthLine("Service soon", PillTone.WARN), catalyst.pill())
        assertEquals(HealthLine("Watch", PillTone.WARN), cam.pill())
        assertEquals(HealthLine("Stop safely", PillTone.BAD), isolation.pill())
        assertEquals(HealthLine("Monitor", PillTone.NEUTRAL), DtcCode("P1000", severity = DtcSeverity.INFO).pill())
    }

    @Test
    fun safeToDriveFollowsTheWorstCode() {
        assertNull(safeToDrive(emptyList()))
        assertEquals(
            HealthLine(
                "Safe to drive — have it serviced soon. Neither code affects the electric drive system.",
                PillTone.EV,
            ),
            safeToDrive(listOf(catalyst, cam)),
        )
        assertEquals(
            "Safe to drive — have it serviced soon. It doesn't affect the electric drive system.",
            safeToDrive(listOf(catalyst))?.text,
        )
        assertEquals(
            "Safe to drive — mention it at your next service. None of them affect the electric drive system.",
            safeToDrive(List(3) { DtcCode("P030$it", severity = DtcSeverity.INFO) })?.text,
        )
        assertEquals(
            "Safe to drive — have it serviced soon.",
            safeToDrive(listOf(DtcCode("B1000")))?.text,
        )
        assertEquals(PillTone.BAD, safeToDrive(listOf(catalyst, isolation))?.tone)
    }

    @Test
    fun statusCountsSeparatePendingAndPermanent() {
        assertEquals("1 stored · 1 pending", statusCounts(listOf(catalyst, cam)))
        assertEquals("1 permanent", statusCounts(listOf(DtcCode("P0300", status = "permanent"))))
    }

    @Test
    fun theHeroCoversEveryState() {
        assertEquals(
            HealthHero("2 trouble codes", "1 stored · 1 pending · 2 h ago", PillTone.WARN),
            demo.hero(),
        )
        assertEquals(PillTone.BAD, demo.copy(codes = listOf(isolation)).hero().tone)
        assertEquals("1 trouble code", demo.copy(codes = listOf(catalyst)).hero().title)
        assertEquals(
            "1 stored · last seen 2 h ago",
            demo.copy(codes = listOf(catalyst), scannedAtMs = null).hero().subtitle,
        )
        assertEquals(
            HealthHero("No trouble codes", "The last scan found none · 2 h ago", PillTone.EV),
            demo.copy(codes = emptyList()).hero(),
        )
        assertEquals(
            "Codes cleared · 4 min ago",
            demo.copy(codes = emptyList(), clearedAtMs = now - 4 * MIN).hero().subtitle,
        )
        assertEquals("None saved", demo.copy(codes = emptyList(), scannedAtMs = null).hero().subtitle)
        assertEquals(
            HealthHero("Not scanned yet", "Scan to read the car's trouble codes.", PillTone.NEUTRAL),
            DiagUiState().hero(),
        )
        assertEquals(
            HealthHero("Checking the car…", "Reading…", PillTone.NEUTRAL),
            demo.copy(busyLabel = "Reading…").hero(),
        )
    }

    @Test
    fun theCarTabSummaryNeverCallsUnscannedClean() {
        assertEquals("Not scanned yet", DiagUiState().summary())
        assertEquals("2 trouble codes · scanned 2 h ago", demo.summary())
        assertEquals("1 trouble code", demo.copy(codes = listOf(cam), scannedAtMs = null).summary())
        assertEquals("No trouble codes · scanned 2 h ago", demo.copy(codes = emptyList()).summary())
    }

    @Test
    fun earlierCodesAreNamedUpToThree() {
        assertNull(demo.earlierLine())
        assertEquals(
            "Earlier: P0171 — not found on the last scan",
            demo.copy(earlierCodes = listOf("P0171")).earlierLine(),
        )
        assertEquals(
            "Earlier: A, B, C +2 more — cleared since",
            demo.copy(earlierCodes = listOf("A", "B", "C", "D", "E"), clearedAtMs = now).earlierLine(),
        )
    }

    @Test
    fun listRows() {
        assertEquals("Captured with P0420", demo.freezeFrameLine())
        assertEquals("None stored", demo.copy(freezeFrame = null).freezeFrameLine())
        assertEquals("None stored", DiagUiState().freezeFrameLine())
        assertEquals(
            "Captured with P0300",
            demo.copy(freezeFrame = null, codes = listOf(DtcCode("P0300", status = "freeze-frame"))).freezeFrameLine(),
        )
        assertFalse(demo.copy(freezeFrame = null).hasFreezeFrame())
        assertTrue(
            demo.copy(freezeFrame = null, codes = listOf(DtcCode("P0300", status = "freeze-frame"))).hasFreezeFrame(),
        )
        assertEquals("Not connected", liveSignalsLine(DriveUiState()))
        assertEquals("78 readings coming in", liveSignalsLine(DriveUiState(connected = true, signalCount = 78)))
        assertEquals("Waiting for data", liveSignalsLine(DriveUiState(connected = true)))
        assertEquals("OBDLink MX+ · connected", adapterLine("OBDLink MX+", true))
        assertEquals("OBDLink MX+ · not connected", adapterLine("OBDLink MX+", false))
        assertEquals("No adapter remembered", adapterLine("--", false))
        assertEquals("No adapter remembered", adapterLine(" ", true))
    }

    @Test
    fun theBatteryCardUsesDrivesCellReads() {
        val empty = hvBattery(DriveUiState(), null, null)
        assertFalse(empty.reported)
        assertEquals(96, empty.groups)
        assertNull(empty.weakestLabel())
        val parked = hvBattery(DriveUiState.demoParked, 91.0, 47.3)
        assertTrue(parked.reported)
        assertEquals(DriveUiState.demoParked.cellVoltages, parked.cells)
        assertEquals(DriveUiState.demoParked.cellSpreadMv, parked.spreadMv)
        assertEquals("#47 · 3.893 V", parked.copy(weakestCell = 47, weakestVolts = 3.8931).weakestLabel())
        assertEquals("#3", parked.copy(weakestCell = 3, weakestVolts = null).weakestLabel())
        assertTrue(hvBattery(DriveUiState(), null, 40.0).reported)
    }

    @Test
    fun theReportSaysWhatTheScreenSays() {
        val battery = hvBattery(DriveUiState(cellSpreadMv = 19.0), 91.0, 47.3)
        val report = healthReport(demo, battery, demo = true)
        assertTrue(report.startsWith("Volt Tracker health report\n\nDemo data — not from a real car."))
        assertTrue(report.contains("2 trouble codes (1 stored · 1 pending · 2 h ago)"))
        assertTrue(
            report.contains(
                "- P0420 Catalyst system efficiency below threshold — Service soon " +
                    "(Bank 1 · seen 4× · first 3 days ago)",
            ),
        )
        assertTrue(report.contains("Neither code affects the electric drive system."))
        assertTrue(report.contains("HV battery: 91% capacity health, 47.3 Ah of 52 Ah new, cell spread 19 mV"))
        val plain = healthReport(DiagUiState(), hvBattery(DriveUiState(), null, null), demo = false)
        assertEquals("Volt Tracker health report\n\nNot scanned yet (Scan to read the car's trouble codes.)", plain)
    }

    private companion object {
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
    }
}
