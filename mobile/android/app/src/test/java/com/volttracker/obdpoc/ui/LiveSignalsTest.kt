package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.components.DASH
import com.volttracker.obdpoc.ui.diag.liveSignalGroups
import com.volttracker.obdpoc.ui.drive.DriveMode
import com.volttracker.obdpoc.ui.drive.DriveUiState
import org.junit.Assert.assertEquals
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
}
