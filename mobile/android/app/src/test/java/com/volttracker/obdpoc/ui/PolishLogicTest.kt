package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.car.BodyGroup
import com.volttracker.obdpoc.ui.car.CarUiState
import com.volttracker.obdpoc.ui.car.NEEDS_OBDLINK
import com.volttracker.obdpoc.ui.car.aux12Tile
import com.volttracker.obdpoc.ui.car.batterySummary
import com.volttracker.obdpoc.ui.car.missingLine
import com.volttracker.obdpoc.ui.car.tiresDescription
import com.volttracker.obdpoc.ui.components.DASH
import com.volttracker.obdpoc.ui.components.DEMO_SUBTITLE
import com.volttracker.obdpoc.ui.components.NOT_REPORTED
import com.volttracker.obdpoc.ui.components.appBarSubtitle
import com.volttracker.obdpoc.ui.components.orDash
import com.volttracker.obdpoc.ui.components.spoken
import com.volttracker.obdpoc.ui.components.withUnit
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.drive.TirePressures
import com.volttracker.obdpoc.ui.drive.cellBalanceText
import com.volttracker.obdpoc.ui.drive.cellsDescription
import com.volttracker.obdpoc.ui.drive.description
import com.volttracker.obdpoc.ui.drive.energyFlow
import com.volttracker.obdpoc.ui.drive.estimatingText
import com.volttracker.obdpoc.ui.drive.etaLead
import com.volttracker.obdpoc.ui.drive.gaugeDescription
import com.volttracker.obdpoc.ui.drive.gearKnown
import com.volttracker.obdpoc.ui.drive.outsideTempLabel
import com.volttracker.obdpoc.ui.drive.powerTraceDescription
import com.volttracker.obdpoc.ui.settings.NumberField
import com.volttracker.obdpoc.ui.settings.adapterName
import com.volttracker.obdpoc.ui.settings.adapterStatus
import com.volttracker.obdpoc.ui.settings.numberError
import com.volttracker.obdpoc.ui.units.METERS_PER_MILE
import com.volttracker.obdpoc.ui.units.haversineMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared wording, placeholders and spoken descriptions from the polish pass. */
class PolishLogicTest {
    @Test
    fun placeholdersAreOneBareDashThatReadsAsNotReported() {
        assertEquals(DASH, orDash(null))
        assertEquals("5", orDash("5"))
        assertEquals("38 mi", withUnit("38", "mi"))
        assertEquals("71%", withUnit("71", "%", separator = ""))
        assertEquals(DASH, withUnit(null, "mi"))
        assertEquals(NOT_REPORTED, spoken(DASH))
        assertEquals("3.6 kW", spoken("3.6 kW"))
    }

    @Test
    fun cellBalanceIsPlainWords() {
        assertEquals("Cells balanced (19 mV)", cellBalanceText(19.2))
        assertEquals("Cells 50 mV apart", cellBalanceText(50.0))
        assertEquals("Cells 62 mV apart", cellBalanceText(62.0))
    }

    @Test
    fun powerTraceReadsNowPeakAndRegen() {
        assertEquals("Pack power over the last minute: no readings yet", powerTraceDescription(emptyList()))
        assertEquals(
            "Pack power over the last minute: now 12 kilowatts, peak 40 kilowatts, regen up to 20 kilowatts",
            powerTraceDescription(listOf(5f, 40f, -20f, 12f)),
        )
        assertEquals(
            "Pack power over the last minute: now 3 kilowatts regen, regen up to 3 kilowatts",
            powerTraceDescription(listOf(-3f)),
        )
        assertEquals("Pack power over the last minute: now 0 kilowatts", powerTraceDescription(listOf(0.4f)))
    }

    @Test
    fun cellsReadCountSpanAndLowestGroup() {
        val state =
            DriveUiState(
                cellVoltages = listOf(3.9, null, 3.91),
                minCellVolts = 3.9,
                maxCellVolts = 3.91,
                minCellNumber = 1,
            )
        assertEquals("2 cell groups from 3.900 to 3.910 volts, lowest is group 1", cellsDescription(state))
        assertEquals("0 cell groups", cellsDescription(DriveUiState()))
    }

    @Test
    fun gaugeDescriptionSaysWhatTheRingShows() {
        assertEquals("Battery Not reported. Connect to see your Volt live", gaugeDescription(DriveUiState()))
        val driving = gaugeDescription(DriveUiState.demo)
        assertTrue(driving, driving.startsWith("47 miles per hour, "))
        assertTrue(driving, driving.contains("kilowatts"))
        val parked = gaugeDescription(DriveUiState.demoParked)
        assertTrue(parked, parked.startsWith("Battery ") && parked.contains(" percent, "))
        assertTrue(parked, parked.contains("electric range"))
        val charging = gaugeDescription(DriveUiState.demoCharging, h24 = true)
        assertTrue(charging, charging.contains("Full by "))
        assertTrue(charging, charging.contains("1 hr 26 min"))
        assertTrue(charging, charging.contains("charging at"))
        assertFalse(charging, charging.contains("AM") || charging.contains("PM"))
    }

    @Test
    fun chargeFinishFollowsTheChargeLimit() {
        assertEquals("Full by ", etaLead(100))
        assertEquals("80% by ", etaLead(80))
        assertEquals("Estimating time to full…", estimatingText(100))
        assertEquals("Estimating time to 80%…", estimatingText(80))
    }

    @Test
    fun gearIsKnownOnlyForARealPosition() {
        assertTrue(DriveUiState(gear = "D").gearKnown)
        assertTrue(DriveUiState(gear = "L").gearKnown)
        assertFalse(DriveUiState().gearKnown)
        assertFalse(DriveUiState(gear = "PR").gearKnown)
        assertFalse(DriveUiState(gear = "").gearKnown)
    }

    @Test
    fun energyFlowReadsEveryNode() {
        val offline = energyFlow(DriveUiState()).description()
        assertTrue(offline, offline.startsWith("Energy flow: "))
        assertTrue(offline, offline.contains("battery $NOT_REPORTED"))
        val charging = energyFlow(DriveUiState.demoCharging).description()
        assertTrue(charging, charging.contains("Grid 3.6 kW"))
        assertTrue(charging, charging.contains("battery 71%"))
    }

    @Test
    fun adapterCardNeverSaysNoAdapterTwice() {
        assertEquals("No adapter", adapterName("--"))
        assertEquals("No adapter", adapterName(""))
        assertEquals("OBDLink MX+", adapterName("OBDLink MX+"))
        assertEquals("Not connected", adapterStatus("--", "No adapter"))
        assertEquals("Idle · OBDLink MX+", adapterStatus("OBDLink MX+", "Idle · OBDLink MX+"))
    }

    @Test
    fun numberEditorExplainsItsRangeAndBadInput() {
        assertEquals("From $0 to $2 per kWh", NumberField("Home electricity rate", "$/kWh", 0.0, 2.0).rangeLabel)
        assertEquals("From 50% to 100%", NumberField("Charge target", "%", 50.0, 100.0).rangeLabel)
        assertEquals("From 5 to 150 mpg", NumberField("Gas vehicle mpg", "mpg", 5.0, 150.0).rangeLabel)
        assertEquals("Enter a number", numberError("abc", null))
        assertNull(numberError("", null))
        assertNull(numberError("0.12", 0.12))
    }

    @Test
    fun carSaysWhatAMissingReadingNeeds() {
        val car = CarUiState()
        assertEquals(NEEDS_OBDLINK, car.missingLine(BodyGroup.TIRES))
        assertEquals(NOT_REPORTED, car.missingLine(BodyGroup.AUX12))
        assertEquals(DASH, aux12Tile(DriveUiState()).value)
        assertEquals("91% health · cells balanced (19 mV)", batterySummary(91.0, 19.0))
        assertEquals("Tires not reported", tiresDescription(null, metric = false))
        assertEquals(
            "Tires front left 38 psi, front right 37 psi, rear left 36 psi, rear right 35 psi",
            tiresDescription(TirePressures(38.0, 37.0, 36.0, 35.0), metric = false),
        )
    }

    @Test
    fun haversineIsSharedAndSane() {
        // One degree of longitude on the equator is ~111.2 km.
        assertEquals(111_195.0, haversineMeters(0.0, 0.0, 0.0, 1.0), 1.0)
        assertEquals(0.0, haversineMeters(42.0, -83.0, 42.0, -83.0), 1e-9)
        assertEquals(1609.344, METERS_PER_MILE, 0.0)
    }

    @Test
    fun demoHeadersNeverClaimALiveAdapter() {
        assertEquals("Live · OBDLink MX+", appBarSubtitle("Live · OBDLink MX+", statusSubtitle = true, demo = false))
        assertEquals(DEMO_SUBTITLE, appBarSubtitle("Live · OBDLink MX+", statusSubtitle = true, demo = true))
        assertEquals("Sample data · 48 drives", appBarSubtitle("48 drives", statusSubtitle = false, demo = true))
        assertNull(appBarSubtitle(null, statusSubtitle = true, demo = true))
    }

    @Test
    fun outsideChipNeedsALiveReading() {
        assertEquals("64°F outside", outsideTempLabel(DriveUiState.demo))
        assertNull(outsideTempLabel(DriveUiState.demo.copy(connected = false)))
        assertNull(outsideTempLabel(DriveUiState.demo.copy(ambientF = null)))
    }
}
