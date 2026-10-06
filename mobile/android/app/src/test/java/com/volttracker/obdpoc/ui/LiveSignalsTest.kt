package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.components.DASH
import com.volttracker.obdpoc.ui.diag.liveSignalGroups
import com.volttracker.obdpoc.ui.drive.DriveMode
import com.volttracker.obdpoc.ui.drive.DriveUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSignalsTest {
    private fun value(
        drive: DriveUiState,
        label: String,
    ): String = liveSignalGroups(drive).flatMap { it.rows }.first { it.label == label }.value

    @Test
    fun groupsKeepAStableOrderAndShowDashesForMissingReadings() {
        val empty = DriveUiState(connected = true)
        assertEquals(
            listOf("HV battery", "Drive unit", "Engine", "12 V & cabin"),
            liveSignalGroups(empty).map { it.title },
        )
        assertEquals(DASH, value(empty, "Pack voltage"))
        assertEquals(DASH, value(empty, "Gear"))
        assertEquals(DASH, value(empty, "12 V battery"))
        assertEquals("No", value(empty, "Running"))
        assertEquals("0", value(empty, "RPM"))
    }

    @Test
    fun readingsFormatInTheChosenUnits() {
        val drive =
            DriveUiState(
                connected = true,
                displayedSocPercent = 76.4,
                packVolts = 358.6,
                packAmps = -23.04,
                packTempF = 75,
                minCellVolts = 3.9194,
                cellSpreadMv = 14.2,
                speedMph = 42,
                powerKw = 15.94,
                gear = "D",
                mode = DriveMode.GAS,
                rpm = 1450,
                auxVolts = 14.2,
                gpsAccuracyFt = 20,
            )
        assertEquals("76%", value(drive, "Charge"))
        assertEquals("359 V", value(drive, "Pack voltage"))
        assertEquals("-23.0 A", value(drive, "Pack current"))
        assertEquals("75°F", value(drive, "Pack temperature"))
        assertEquals("3.919 V", value(drive, "Lowest cell"))
        assertEquals("14 mV", value(drive, "Cell spread"))
        assertEquals("42 mph", value(drive, "Speed"))
        assertEquals("15.9 kW", value(drive, "Power"))
        assertEquals("D", value(drive, "Gear"))
        assertEquals("1450", value(drive, "RPM"))
        assertEquals("14.20 V", value(drive, "12 V battery"))
        assertEquals("±20 ft", value(drive, "GPS accuracy"))

        val metric = drive.copy(metricUnits = true)
        assertEquals("68 km/h", value(metric, "Speed"))
        assertEquals("24°C", value(metric, "Pack temperature"))
        assertEquals("±6 m", value(metric, "GPS accuracy"))
    }

    @Test
    fun readingsTheCarRefusesStayOutUntilOneArrives() {
        val refused = listOf("Inverter temperature", "Transmission temperature", "Oil life", "12 V charge")
        val empty = DriveUiState(connected = true)
        val labels = liveSignalGroups(empty).flatMap { it.rows }.map { it.label }
        assertTrue("no always-blank rows: $labels", labels.none { it in refused || it == "Torque" })
        // Readings this car does report keep their place while they're still missing.
        assertEquals(DASH, value(empty, "Oil temperature"))
        assertEquals(DASH, value(empty, "Motor B temperature"))

        val reported =
            empty.copy(
                oilTempF = 190,
                motorTempF = 140,
                motorBTempF = 131,
                inverterTempF = 118,
                transTempF = 141,
                oilLifePct = 87,
                aux12SocPercent = 86,
            )
        assertEquals("190°F", value(reported, "Oil temperature"))
        assertEquals("140°F", value(reported, "Motor A temperature"))
        assertEquals("131°F", value(reported, "Motor B temperature"))
        assertEquals("118°F", value(reported, "Inverter temperature"))
        assertEquals("141°F", value(reported, "Transmission temperature"))
        assertEquals("87%", value(reported, "Oil life"))
        assertEquals("86%", value(reported, "12 V charge"))
    }

    @Test
    fun energyAndTripsShowOnlyOnceTheCarSendsThem() {
        val empty = DriveUiState(connected = true)
        val labels = liveSignalGroups(empty).flatMap { it.rows }.map { it.label }
        assertTrue("no energy rows before the broadcast: $labels", labels.none { it == "Energy left" })
        assertTrue(liveSignalGroups(empty).none { it.title == "Energy & trips" })

        val heard =
            empty.copy(
                energyLeftKwh = 8.5,
                cycleDrivingKwh = 4.0,
                cycleClimateKwh = 0.2,
                cycleConditioningKwh = 0.0,
                carTripAMiles = 20.14,
            )
        assertEquals("8.5 kWh", value(heard, "Energy left"))
        val group = liveSignalGroups(heard).single { it.title == "Energy & trips" }
        assertEquals(
            listOf("Used driving", "Used by climate", "Used conditioning battery", "Trip A"),
            group.rows.map { it.label },
        )
        assertEquals("4.0 kWh", value(heard, "Used driving"))
        assertEquals("0.0 kWh", value(heard, "Used conditioning battery"))
        assertEquals("20.1 mi", value(heard, "Trip A"))
        assertEquals("32.4 km", value(heard.copy(metricUnits = true), "Trip A"))
        assertEquals("Energy & trips", liveSignalGroups(heard).last().title)
    }
}
