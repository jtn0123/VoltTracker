package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.components.DASH
import com.volttracker.obdpoc.ui.components.PillTone
import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.diag.DtcCode
import com.volttracker.obdpoc.ui.diag.DtcSeverity
import com.volttracker.obdpoc.ui.diag.HealthHero
import com.volttracker.obdpoc.ui.diag.HealthLine
import com.volttracker.obdpoc.ui.diag.adapterLine
import com.volttracker.obdpoc.ui.diag.agingLine
import com.volttracker.obdpoc.ui.diag.agoText
import com.volttracker.obdpoc.ui.diag.batteryThermal
import com.volttracker.obdpoc.ui.diag.detailLine
import com.volttracker.obdpoc.ui.diag.earlierLine
import com.volttracker.obdpoc.ui.diag.freezeFrameLine
import com.volttracker.obdpoc.ui.diag.hasFreezeFrame
import com.volttracker.obdpoc.ui.diag.healthReport
import com.volttracker.obdpoc.ui.diag.hero
import com.volttracker.obdpoc.ui.diag.hvBattery
import com.volttracker.obdpoc.ui.diag.liveSignalsLine
import com.volttracker.obdpoc.ui.diag.pill
import com.volttracker.obdpoc.ui.diag.serviceGuidance
import com.volttracker.obdpoc.ui.diag.statusCounts
import com.volttracker.obdpoc.ui.diag.statusLine
import com.volttracker.obdpoc.ui.diag.summary
import com.volttracker.obdpoc.ui.diag.title
import com.volttracker.obdpoc.ui.diag.weakestLabel
import com.volttracker.obdpoc.ui.drive.DrivePhase
import com.volttracker.obdpoc.ui.drive.DriveUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Health's copy: code names, pills, qualified service guidance, the hero and the shared report. */
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
    fun serviceGuidanceReportsPriorityWithoutDrivingAssurances() {
        assertNull(serviceGuidance(emptyList()))
        val warning = serviceGuidance(listOf(catalyst, cam))!!
        assertEquals(PillTone.WARN, warning.tone)
        assertTrue(warning.text.startsWith("Service soon"))
        assertTrue(warning.text.contains("Driving safety cannot be determined"))
        val informational =
            serviceGuidance(
                listOf(DtcCode("C07B0", description = "TPMS sensor", severity = DtcSeverity.INFO)),
            )!!
        assertEquals(PillTone.NEUTRAL, informational.tone)
        assertTrue(informational.text.startsWith("Monitor"))
        val unknown = serviceGuidance(listOf(DtcCode("B1000")))!!
        assertTrue(unknown.text.contains("not in the catalog"))
        assertFalse(unknown.text.contains("Safe to drive", ignoreCase = true))
        assertEquals(PillTone.BAD, serviceGuidance(listOf(catalyst, isolation))?.tone)
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
        assertEquals("Not scanned yet", DiagUiState().freezeFrameLine())
        assertEquals("None stored", DiagUiState(codes = emptyList()).freezeFrameLine())
        assertEquals(
            "Captured with P0300",
            demo.copy(freezeFrame = null, codes = listOf(DtcCode("P0300", status = "freeze-frame"))).freezeFrameLine(),
        )
        assertFalse(demo.copy(freezeFrame = null).hasFreezeFrame())
        assertTrue(
            demo.copy(freezeFrame = null, codes = listOf(DtcCode("P0300", status = "freeze-frame"))).hasFreezeFrame(),
        )
        // Health's header speaks Drive's connection line on the link, and the shared label off it.
        val live = DriveUiState(connected = true, adapterLabel = "OBDLink MX+", phase = DrivePhase.DRIVE)
        assertEquals("Live · OBDLink MX+", DiagUiState(connected = true, statusLabel = "Live").statusLine(live))
        assertEquals("Scanning…", DiagUiState(statusLabel = "Scanning…").statusLine(DriveUiState()))
        assertEquals("Not connected", DiagUiState(statusLabel = "Not connected").statusLine(live))
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
    fun theBatteryCardShowsThePacksInternalResistance() {
        assertNull(hvBattery(DriveUiState(), null, null).agingLine())
        // 2240E9 = 0x024A / 2 = 293 mΩ, what the car answered.
        val battery = hvBattery(DriveUiState(packResistanceMohm = 293.0), null, null)
        assertTrue("resistance alone counts as a battery read", battery.reported)
        assertEquals("Internal resistance 293 mΩ", battery.agingLine())
        assertTrue(
            healthReport(DiagUiState(), battery, demo = false).contains("HV battery: internal resistance 293 mΩ"),
        )
        // 2243A5 = 0x04D2: a made-up count in the car's layout.
        val counted = hvBattery(DriveUiState(packResistanceMohm = 293.0, packChargeCount = 1234), null, null)
        assertEquals("Internal resistance 293 mΩ · charged 1,234 times", counted.agingLine())
        assertEquals("Charged 1,234 times", hvBattery(DriveUiState(packChargeCount = 1234), null, null).agingLine())
        assertTrue(
            healthReport(DiagUiState(), counted, demo = false)
                .contains("HV battery: internal resistance 293 mΩ, charged 1,234 times"),
        )
        // 224389 = 0x0012D687 x 10 Wh (made up, like the count).
        val lifetime = hvBattery(DriveUiState(packChargeCount = 1234, lifetimeChargedKwh = 12_345.67), null, null)
        assertEquals("Charged 1,234 times (12,346 kWh)", lifetime.agingLine())
        assertTrue(
            healthReport(
                DiagUiState(),
                lifetime,
                demo = false,
            ).contains("HV battery: charged 1,234 times (12,346 kWh)"),
        )
        val energyOnly = hvBattery(DriveUiState(lifetimeChargedKwh = 12_345.67), null, null)
        assertTrue("lifetime energy alone counts as a battery read", energyOnly.reported)
        assertEquals("12,346 kWh charged", energyOnly.agingLine())
    }

    @Test
    fun theThermalCardReadsTheSectionsPumpHeaterAndElectronicsLoop() {
        assertFalse(batteryThermal(DriveUiState()).reported)
        // What the car answered on 2026-10-03: sections 0x42..0x45 (26-29 °C), pump 0x049F,
        // heater 0000, electronics loop 0x50 (40 °C).
        val drive =
            DriveUiState(
                packSectionTempsF = listOf(78, 80, null, 82, 84, 80),
                batteryCoolantPumpRpm = 1183,
                batteryHeaterW = 0,
                pemCoolantF = 104,
            )
        val thermal = batteryThermal(drive)
        assertTrue(thermal.reported)
        assertEquals(listOf("78°F", "80°F", DASH, "82°F", "84°F", "80°F"), thermal.sectionTexts)
        assertEquals("78–84°F", thermal.sectionRange)
        assertEquals("1,183", thermal.pumpText)
        assertEquals("Off", thermal.heaterText)
        assertEquals("104", thermal.electronicsText)

        val metric = batteryThermal(drive.copy(metricUnits = true))
        assertEquals("26–29°C", metric.sectionRange)
        assertEquals("40", metric.electronicsText)

        val even =
            batteryThermal(
                DriveUiState(packSectionTempsF = listOf(80, 80), batteryHeaterW = 2140, batteryCoolantPumpRpm = 0),
            )
        assertEquals("80°F", even.sectionRange)
        assertEquals("2.1", even.heaterText)
        assertEquals("Off", even.pumpText)

        val report = healthReport(DiagUiState(), hvBattery(DriveUiState(), null, null), demo = false, thermal = thermal)
        assertTrue(
            report.contains(
                "Battery thermal: sections 78–84°F, coolant pump 1,183 rpm, heater off, electronics coolant 104°F",
            ),
        )
        val pumpOnly = batteryThermal(DriveUiState(batteryCoolantPumpRpm = 900))
        assertNull(pumpOnly.sectionRange)
        assertTrue(pumpOnly.reported)
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
        assertTrue(report.contains("Driving safety cannot be determined from these codes alone."))
        assertTrue(report.contains("HV battery: 91% capacity health, 47.3 Ah of 52 Ah new, cell spread 19 mV"))
        val plain = healthReport(DiagUiState(), hvBattery(DriveUiState(), null, null), demo = false)
        assertEquals("Volt Tracker health report\n\nNot scanned yet (Scan to read the car's trouble codes.)", plain)
    }

    private companion object {
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
    }
}
