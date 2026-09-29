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
 * around as samples arrive. Only meaningful while connected; the screen shows an empty state
 * otherwise.
 */
fun liveSignalGroups(drive: DriveUiState): List<SignalGroup> {
    val u = drive.units

    fun temp(f: Int?) = f?.let { u.tempText(it.toDouble()) } ?: DASH
    return listOf(
        SignalGroup(
            "HV battery",
            listOf(
                SignalRow("Charge", drive.displayedSocPercent?.let { "${it.roundToInt()}%" } ?: DASH),
                SignalRow("Pack voltage", drive.packVolts?.let { "${it.roundToInt()} V" } ?: DASH),
                SignalRow("Pack current", drive.packAmps?.let { "${oneDecimal(it)} A" } ?: DASH),
                SignalRow("Pack temperature", temp(drive.packTempF)),
                SignalRow("Lowest cell", drive.minCellVolts?.let { "%.3f V".format(Locale.US, it) } ?: DASH),
                SignalRow("Highest cell", drive.maxCellVolts?.let { "%.3f V".format(Locale.US, it) } ?: DASH),
                SignalRow("Cell spread", drive.cellSpreadMv?.let { "${it.roundToInt()} mV" } ?: DASH),
            ),
        ),
        SignalGroup(
            "Drive unit",
            listOf(
                SignalRow("Speed", u.speedText(drive.speedMph.toDouble())),
                SignalRow("Power", "${oneDecimal(drive.powerKw)} kW"),
                SignalRow("Motor A", drive.motorAKw?.let { "${oneDecimal(it)} kW" } ?: DASH),
                SignalRow("Motor B", drive.motorBKw?.let { "${oneDecimal(it)} kW" } ?: DASH),
                SignalRow("Torque", drive.torqueNm?.let { "$it Nm" } ?: DASH),
                SignalRow("Motor temperature", temp(drive.motorTempF)),
                SignalRow("Inverter temperature", temp(drive.inverterTempF)),
                SignalRow("Transmission temperature", temp(drive.transTempF)),
                SignalRow("Gear", drive.gear.takeUnless { it == "--" } ?: DASH),
            ),
        ),
        SignalGroup(
            "Engine",
            listOf(
                SignalRow("Running", if (drive.mode == DriveMode.GAS) "Yes" else "No"),
                SignalRow("RPM", if (drive.mode == DriveMode.GAS) "${drive.rpm}" else "0"),
                SignalRow("Coolant", temp(drive.coolantF)),
                SignalRow("Fuel", drive.fuelPercent?.let { "${it.roundToInt()}%" } ?: DASH),
                SignalRow("Oil life", drive.oilLifePct?.let { "$it%" } ?: DASH),
            ),
        ),
        SignalGroup(
            "12 V & cabin",
            listOf(
                SignalRow(
                    "12 V battery",
                    (drive.aux12Volts ?: drive.auxVolts)?.let { "%.2f V".format(Locale.US, it) } ?: DASH,
                ),
                SignalRow("12 V charge", drive.aux12SocPercent?.let { "$it%" } ?: DASH),
                SignalRow("Cabin", temp(drive.cabinTempF)),
                SignalRow("Outside", temp(drive.ambientF)),
                SignalRow("GPS accuracy", drive.gpsAccuracyFt?.let { gpsText(it, drive.metricUnits) } ?: DASH),
            ),
        ),
    )
}

private fun oneDecimal(v: Double): String = "%.1f".format(Locale.US, v)

private fun gpsText(
    feet: Int,
    metric: Boolean,
): String = if (metric) "±${(feet * METERS_PER_FOOT).roundToInt()} m" else "±$feet ft"

private const val METERS_PER_FOOT = 0.3048
