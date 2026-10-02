package com.volttracker.obdpoc.ui.trips

import com.volttracker.obdpoc.ui.drive.clockLabel
import com.volttracker.obdpoc.ui.drive.durationLabel
import com.volttracker.obdpoc.ui.drive.oneDecimal
import com.volttracker.obdpoc.ui.receipt.Receipt
import com.volttracker.obdpoc.ui.receipt.ReceiptLine
import com.volttracker.obdpoc.ui.receipt.money
import com.volttracker.obdpoc.ui.units.VoltUnits
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** The kWh a drive used: as logged, else estimated from its electric miles (flagged). */
private data class DriveEnergy(
    val kwh: Double,
    val estimated: Boolean,
)

private fun TripSummary.energy(): DriveEnergy? {
    energyKwh?.takeIf { it >= MIN_RECEIPT_KWH }?.let { return DriveEnergy(it, estimated = false) }
    return if (evMiles >= MIN_RECEIPT_MILES) DriveEnergy(evMiles / ASSUMED_MI_PER_KWH, estimated = true) else null
}

/** Gallons the engine burned for [gasMiles] at the car's MPG; null with no MPG set or no gas miles. */
fun TripSummary.gasGallons(gasMpg: Double?): Double? {
    val mpg = gasMpg?.takeIf { it > 0.0 } ?: return null
    return if (gasMiles >= MIN_RECEIPT_MILES) gasMiles / mpg else null
}

/**
 * [trip] as a receipt: distance, time, the electric / gas split, energy and efficiency, gas
 * burned, speeds and outside temperature, then what it cost at the Settings rates and what the
 * electric miles saved against gas. Figures the drive didn't log are left off, not dashed.
 */
fun TripsUiState.receipt(
    trip: TripSummary,
    zone: TimeZone = TimeZone.getDefault(),
    h24: Boolean = false,
): Receipt {
    val u = units
    val energy = trip.energy()
    val gallons = trip.gasGallons(gasMpg)
    val details =
        buildList {
            add(ReceiptLine("Distance", "${u.distanceOneDecimal(trip.miles)} ${u.distanceUnit}"))
            add(ReceiptLine("Time", durationLabel(trip.endedAtMs - trip.startedAtMs)))
            add(ReceiptLine("On electric", splitText(trip.evMiles, trip.miles, u)))
            if (trip.gasMiles >= MIN_RECEIPT_MILES) add(ReceiptLine("On gas", splitText(trip.gasMiles, trip.miles, u)))
            energy?.let {
                add(
                    ReceiptLine(
                        "Battery used",
                        "${oneDecimal(it.kwh)} kWh",
                        EST.takeIf { _ ->
                            it.estimated
                        },
                    ),
                )
            }
            trip.miPerKwh?.let(u::efficiencyText)?.let { add(ReceiptLine("Efficiency", it)) }
            gallons?.let { add(ReceiptLine("Gas used", volumeText(it, u), EST)) }
            trip.avgSpeedKph?.let { add(ReceiptLine("Average speed", u.speedText(it / VoltUnits.KM_PER_MI))) }
            trip.maxSpeedKph?.let { add(ReceiptLine("Top speed", u.speedText(it / VoltUnits.KM_PER_MI))) }
            trip.outsideTempC?.let { add(ReceiptLine("Outside", u.tempText(it * F_PER_C + F_OFFSET))) }
        }
    val electricity = energy?.takeIf { homeRate > 0.0 }?.let { it.kwh * homeRate }
    val gas = gallons?.takeIf { gasPrice > 0.0 }?.let { it * gasPrice }
    val costs =
        buildList {
            electricity?.let { add(ReceiptLine("Electricity", money(it), EST.takeIf { _ -> energy.estimated })) }
            gas?.let { add(ReceiptLine("Gas", money(it), EST)) }
            if (electricity != null ||
                gas != null
            ) {
                add(ReceiptLine("Total", money((electricity ?: 0.0) + (gas ?: 0.0))))
            }
        }
    val saved =
        listOf(trip).savedVsGas(gasMpg, gasPrice, homeRate)?.takeIf { it >= MIN_SAVED }?.let {
            "Driving on electric saved about ${money(it)} over gas."
        }
    return Receipt(trip.title(zone), receiptWhen(trip, zone, h24), details, costs, saved)
}

/** "Thu, Apr 30 · 8:14 – 8:42 AM" style: the day, then start – end clock times. */
fun receiptWhen(
    trip: TripSummary,
    zone: TimeZone = TimeZone.getDefault(),
    h24: Boolean = false,
): String {
    val day = SimpleDateFormat("EEE, MMM d", Locale.US).apply { timeZone = zone }.format(Date(trip.startedAtMs))
    val from = clockLabel(trip.startedAtMs, zone = zone, h24 = h24)
    val to = clockLabel(trip.endedAtMs, zone = zone, h24 = h24)
    return "$day · $from – $to"
}

/** "12.1 mi · 66%": a share of the drive, with its percent of the whole. */
private fun splitText(
    part: Double,
    whole: Double,
    u: VoltUnits,
): String {
    val pct = if (whole > 0.0) (part / whole * PERCENT).roundToInt() else 0
    return "${u.distanceOneDecimal(part)} ${u.distanceUnit} · $pct%"
}

/** "1.4 gal" / "5.3 L". */
private fun volumeText(
    gallons: Double,
    u: VoltUnits,
): String = "${oneDecimal(if (u.metric) gallons * VoltUnits.L_PER_GAL else gallons)} ${u.gasVolumeUnit}"

private const val EST = "est."
private const val MIN_RECEIPT_KWH = 0.05
private const val MIN_RECEIPT_MILES = 0.05
private const val MIN_SAVED = 0.01
private const val PERCENT = 100
private const val F_PER_C = 1.8
private const val F_OFFSET = 32.0
