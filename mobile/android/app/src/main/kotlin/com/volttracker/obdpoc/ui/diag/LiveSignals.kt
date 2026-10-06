package com.volttracker.obdpoc.ui.diag

import com.volttracker.obdpoc.ui.components.DASH
import com.volttracker.obdpoc.ui.drive.DriveMode
import com.volttracker.obdpoc.ui.drive.DriveUiState
import java.util.Locale
import kotlin.math.roundToInt

/** One reading on Health › Live signals: "Pack voltage" · "359 V", or [DASH] when not reported. */
data class SignalRow(
    val label: String,
    val value: String,
)

/** A titled group of [SignalRow]s ("HV battery", "Drive unit", …). */
data class SignalGroup(
    val title: String,
    val rows: List<SignalRow>,
)

/**
 * The live readings worth reading at a glance, grouped the way a driver thinks about the car, in
 * the chosen units. Missing readings show [DASH] rather than vanishing, so the list doesn't jump
 * around as samples arrive. The readings only some Volts report (oil life, inverter temperature,
 * 12 V charge; the 2017 refuses or blanks them) and the SW-CAN broadcasts only an OBDLink hears
 * (transmission temperature on a 2017, energy left, the energy & trips group) stay out until the car
 * sends one, so a car or adapter that never has them never shows a row that is always blank. Only
 * meaningful while connected; the screen shows an empty state otherwise.
 */
fun liveSignalGroups(drive: DriveUiState): List<SignalGroup> {
    val u = drive.units

    fun temp(f: Int?) = f?.let { u.tempText(it.toDouble()) } ?: DASH

    fun ifReported(
        label: String,
        f: Int?,
        format: (Int) -> String = { temp(it) },
    ): SignalRow? = f?.let { SignalRow(label, format(it)) }
    return listOf(
        SignalGroup(
            "HV battery",
            listOfNotNull(
                SignalRow("Charge", drive.displayedSocPercent?.let { "${it.roundToInt()}%" } ?: DASH),
                SignalRow("Pack voltage", drive.packVolts?.let { "${it.roundToInt()} V" } ?: DASH),
                SignalRow("Pack current", drive.packAmps?.let { "${oneDecimal(it)} A" } ?: DASH),
                SignalRow("Pack temperature", temp(drive.packTempF)),
                SignalRow("Lowest cell", drive.minCellVolts?.let { "%.3f V".format(Locale.US, it) } ?: DASH),
                SignalRow("Highest cell", drive.maxCellVolts?.let { "%.3f V".format(Locale.US, it) } ?: DASH),
                SignalRow("Cell spread", drive.cellSpreadMv?.let { "${it.roundToInt()} mV" } ?: DASH),
                drive.energyLeftKwh?.let { SignalRow("Energy left", kwh(it)) },
            ),
        ),
        SignalGroup(
            "Drive unit",
            listOfNotNull(
                SignalRow("Speed", drive.speedMph?.let { u.speedText(it.toDouble()) } ?: DASH),
                SignalRow("Power", drive.powerKw?.let { "${oneDecimal(it)} kW" } ?: DASH),
                SignalRow("Motor A", drive.motorAKw?.let { "${oneDecimal(it)} kW" } ?: DASH),
                SignalRow("Motor B", drive.motorBKw?.let { "${oneDecimal(it)} kW" } ?: DASH),
                SignalRow("Motor A temperature", temp(drive.motorTempF)),
                SignalRow("Motor B temperature", temp(drive.motorBTempF)),
                ifReported("Inverter temperature", drive.inverterTempF),
                ifReported("Transmission temperature", drive.transTempF),
                SignalRow("Gear", drive.gear.takeUnless { it == "--" } ?: DASH),
            ),
        ),
        SignalGroup(
            "Engine",
            listOfNotNull(
                SignalRow("Running", if (drive.mode == DriveMode.GAS) "Yes" else "No"),
                SignalRow("RPM", if (drive.mode == DriveMode.GAS) "${drive.rpm}" else "0"),
                SignalRow("Coolant", temp(drive.coolantF)),
                SignalRow("Oil temperature", temp(drive.oilTempF)),
                SignalRow("Fuel", drive.fuelPercent?.let { "${it.roundToInt()}%" } ?: DASH),
                ifReported("Oil life", drive.oilLifePct) { "$it%" },
            ),
        ),
        SignalGroup(
            "12 V & cabin",
            listOfNotNull(
                SignalRow(
                    "12 V battery",
                    (drive.aux12Volts ?: drive.auxVolts)?.let { "%.2f V".format(Locale.US, it) } ?: DASH,
                ),
                ifReported("12 V charge", drive.aux12SocPercent) { "$it%" },
                SignalRow("Cabin", temp(drive.cabinTempF)),
                SignalRow("Outside", temp(drive.ambientF)),
                SignalRow("GPS accuracy", drive.gpsAccuracyFt?.let { gpsText(it, drive.metricUnits) } ?: DASH),
            ),
        ),
        energyAndTrips(drive),
    ).filter { it.rows.isNotEmpty() }
}

/**
 * The car's own energy screen (since the last full charge) and the cluster's trip odometers. Only
 * an OBDLink hears these broadcasts, so each row shows only once the car has sent it.
 */
private fun energyAndTrips(drive: DriveUiState): SignalGroup {
    val u = drive.units
    return SignalGroup(
        "Energy & trips",
        listOfNotNull(
            drive.cycleDrivingKwh?.let { SignalRow("Used driving", kwh(it)) },
            drive.cycleClimateKwh?.let { SignalRow("Used by climate", kwh(it)) },
            drive.cycleConditioningKwh?.let { SignalRow("Used conditioning battery", kwh(it)) },
            drive.carTripAMiles?.let { SignalRow("Trip A", "${u.distanceOneDecimal(it)} ${u.distanceUnit}") },
            drive.carTripBMiles?.let { SignalRow("Trip B", "${u.distanceOneDecimal(it)} ${u.distanceUnit}") },
        ),
    )
}

private fun kwh(v: Double): String = "${oneDecimal(v)} kWh"

private fun oneDecimal(v: Double): String = "%.1f".format(Locale.US, v)

private fun gpsText(
    feet: Int,
    metric: Boolean,
): String = if (metric) "±${(feet * METERS_PER_FOOT).roundToInt()} m" else "±$feet ft"

private const val METERS_PER_FOOT = 0.3048
