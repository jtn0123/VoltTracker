package com.volttracker.obdpoc.ui

import com.volttracker.obdpoc.ui.components.DASH
import com.volttracker.obdpoc.ui.diag.liveSignalGroups
import com.volttracker.obdpoc.ui.drive.DriveMode
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.drive.Gen2Status
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

    @Test
    fun theCarsStatusWordsShowOnlyOnceReported() {
        val empty = DriveUiState(connected = true)
        val labels = liveSignalGroups(empty).flatMap { it.rows }.map { it.label }
        val statusRows = listOf("Conditioning", "Coolant pump", "Last start reason", "Remote starts left")
        assertTrue("no status rows before the car answers: $labels", labels.none { it in statusRows })
        assertTrue(liveSignalGroups(empty).none { it.title == "Charging" })

        val reported =
            empty.copy(
                batteryCoolantPumpRpm = 1200,
                gen2 =
                    Gen2Status(
                        engineStartReason = "Battery charge low",
                        auxTransPumpPct = 20,
                        peCoolantPumpPct = 40,
                        batteryConditioning = "Active cooling",
                        maintenance12v = "Off",
                        chargeState = "Charging",
                        chargeSystem = "Ready",
                        chargeEnableCheck = "Passed",
                        chargeControlCheck = "Passed",
                        chargeStart = "Tue 23:00",
                        chargeComplete = "Wed 04:30",
                        chargeRecords = listOf("Full charge", null, "Unplugged at the car", null, null),
                        acPowerW = 1100,
                        cabinHeatPowerW = 0,
                        remoteStartSecondsLeft = 590,
                        remoteStartsLeft = 2,
                    ),
            )
        assertEquals("1,200 rpm", value(reported, "Coolant pump"))
        assertEquals("Active cooling", value(reported, "Conditioning"))
        assertEquals("20%", value(reported, "Aux transmission pump"))
        assertEquals("40%", value(reported, "Electronics coolant pump"))
        assertEquals("Battery charge low", value(reported, "Last start reason"))
        assertEquals("Off", value(reported, "12 V top-up from pack"))
        assertEquals("1100 W", value(reported, "A/C power"))
        assertEquals("0 W", value(reported, "Cabin heater power"))
        assertEquals("9:50", value(reported, "Remote start time left"))
        assertEquals("2", value(reported, "Remote starts left"))
        val charging = liveSignalGroups(reported).single { it.title == "Charging" }
        assertEquals(
            listOf(
                "Charge state",
                "Charge system",
                "Enable check",
                "Control check",
                "Charge starts",
                "Charge completes",
                "Charge record 1",
                "Charge record 3",
            ),
            charging.rows.map { it.label },
        )
        assertEquals("Unplugged at the car", value(reported, "Charge record 3"))
        // The car's charge times read in the phone's clock.
        assertEquals("Tue 11:00 PM", value(reported, "Charge starts"))
        val in24h = liveSignalGroups(reported, h24 = true).flatMap { it.rows }.first { it.label == "Charge starts" }
        assertEquals("Tue 23:00", in24h.value)
    }

    @Test
    fun theExtraGen2ReadsShowOnlyOnceReported() {
        val empty = DriveUiState(connected = true)
        val extraRows =
            listOf(
                "Coolant temperature",
                "Insulation",
                "EGR command",
                "EGR cooler 1",
                "EGR cooler 2",
                "12 V capacity",
                "Last started by",
                "Charger input duty",
                "Charger output duty",
            )
        val labels = liveSignalGroups(empty).flatMap { it.rows }.map { it.label }
        assertTrue("no extra rows before the car answers: $labels", labels.none { it in extraRows })

        val reported =
            empty.copy(
                hvIsolationKohm = 2_500,
                gen2 =
                    Gen2Status(
                        remoteStartReason = "Ignition switch",
                        batteryCoolantF = 68,
                        chargerAcDutyPct = 72,
                        chargerOutputDutyPct = 68,
                        egrCommandedPct = 12,
                        egrCooler1F = 194,
                        egrCooler2F = 183,
                        aux12vCapacityAh = 41.5,
                    ),
            )
        assertEquals("68°F", value(reported, "Coolant temperature"))
        assertEquals("2,500 kΩ", value(reported, "Insulation"))
        assertEquals("12%", value(reported, "EGR command"))
        assertEquals("194°F", value(reported, "EGR cooler 1"))
        assertEquals("183°F", value(reported, "EGR cooler 2"))
        assertEquals("41.5 Ah", value(reported, "12 V capacity"))
        assertEquals("Ignition switch", value(reported, "Last started by"))
        assertEquals("72%", value(reported, "Charger input duty"))
        assertEquals("68%", value(reported, "Charger output duty"))
        // A metric driver reads the temperatures in Celsius.
        assertEquals("20°C", value(reported.copy(metricUnits = true), "Coolant temperature"))
    }
}
