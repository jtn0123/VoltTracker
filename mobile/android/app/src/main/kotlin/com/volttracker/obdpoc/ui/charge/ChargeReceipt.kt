package com.volttracker.obdpoc.ui.charge

import com.volttracker.obdpoc.ui.drive.clockLabel
import com.volttracker.obdpoc.ui.drive.durationLabel
import com.volttracker.obdpoc.ui.drive.oneDecimal
import com.volttracker.obdpoc.ui.receipt.Receipt
import com.volttracker.obdpoc.ui.receipt.ReceiptLine
import com.volttracker.obdpoc.ui.receipt.money
import com.volttracker.obdpoc.ui.trips.ASSUMED_MI_PER_KWH
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The logged charge with this [ChargeSession.id], if it is still in the list. */
fun ChargeUiState.session(id: String?): ChargeSession? = sessions.firstOrNull { it.id == id }

/**
 * [session] as a receipt: battery from → to, charger, time plugged in, energy and average
 * power, the range it added at the car's recent efficiency, then the cost at the rate that
 * applies and what the same miles would have cost on gas.
 *
 * An inferred charge says so: its energy, range and cost are marked estimates, its time span
 * is the observation window it happened in, and it has no average power (the car wasn't
 * plugged in for the whole window).
 */
fun ChargeUiState.receipt(
    session: ChargeSession,
    worth: ChargeWorth,
    zone: TimeZone = TimeZone.getDefault(),
    h24: Boolean = false,
): Receipt {
    val estimated = session.estimated
    val durationMs = session.endedAtMs?.minus(session.startedAtMs)?.takeIf { it > 0 }
    val kwh = session.energyKwh?.takeIf { it > 0.0 }
    val rangeMiles = kwh?.let { it * (worth.miPerKwh ?: ASSUMED_MI_PER_KWH) }
    val spanLabel = if (estimated) "Observation window" else "Plugged in"
    val details =
        buildList {
            socSpan(session)?.let { add(ReceiptLine("Battery", it)) }
            add(
                ReceiptLine(
                    "Charger",
                    listOfNotNull(
                        levelName(session.level),
                        "public".takeIf {
                            session.publicCharger
                        },
                    ).joinToString(" · ").ifEmpty { "Unknown" },
                ),
            )
            durationMs?.let { add(ReceiptLine(spanLabel, durationLabel(it))) }
            kwh?.let { add(ReceiptLine("Energy added", "${oneDecimal(it)} kWh", EST.takeIf { _ -> estimated })) }
            if (kwh != null && durationMs != null && !estimated) {
                add(ReceiptLine("Average power", "${oneDecimal(kwh / (durationMs.toDouble() / HOUR_MS))} kW"))
            }
            rangeMiles?.let {
                add(
                    ReceiptLine(
                        "Range added",
                        units.distanceText(it),
                        EST.takeIf { _ -> estimated || worth.miPerKwh == null },
                    ),
                )
            }
        }
    val rate = rateFor(session)
    val cost = kwh?.takeIf { rate > 0.0 }?.let { it * rate }
    val costs =
        buildList {
            if (cost != null) {
                add(
                    ReceiptLine(
                        "Rate",
                        "${money(rate)}/kWh",
                        if (session.publicCharger &&
                            publicRate > 0.0
                        ) {
                            "public"
                        } else {
                            "home"
                        },
                    ),
                )
                add(ReceiptLine("Total", money(cost), EST.takeIf { estimated }))
            }
        }
    val gasCost = rangeMiles?.let { gasCostFor(it, worth) }
    val saved =
        if (cost != null && gasCost != null && gasCost > cost) {
            val savedText = (if (estimated) "about " else "") + money(gasCost - cost)
            "Those miles would cost about ${money(gasCost)} on gas — this charge saved $savedText."
        } else {
            null
        }
    val note = ESTIMATE_NOTE.takeIf { estimated }
    return Receipt(chargeTitle(session), chargeWhen(session, zone, h24), details, costs, saved, note)
}

/** Why an inferred charge's figures are estimates: on its receipt and in the share text. */
const val ESTIMATE_NOTE = "The adapter didn't see this charge. Energy and cost are estimated from the battery rise."

private fun gasCostFor(
    miles: Double,
    worth: ChargeWorth,
): Double? {
    val mpg = worth.gasMpg?.takeIf { it > 0.0 } ?: return null
    return if (worth.gasPrice > 0.0) miles / mpg * worth.gasPrice else null
}

/** "41% → 71% (+30)", "to 88%", or null when neither end was read. */
private fun socSpan(session: ChargeSession): String? {
    val to = session.toSoc ?: return null
    val from = session.fromSoc ?: return "to $to%"
    return "$from% → $to% (+${to - from})"
}

/** "Level 2 charge", "Public charge", "Estimated charge", or "Charge". */
fun chargeTitle(session: ChargeSession): String =
    when {
        session.estimated -> "Estimated charge"
        session.publicCharger -> "Public charge"
        else -> levelName(session.level)?.let { "$it charge" } ?: "Charge"
    }

/**
 * "Wed, Apr 29 · 9:18 PM – 12:42 AM", or just the start while the end wasn't logged. An
 * inferred charge's times are its observation window: "Wed, Apr 29 · between 9:18 PM and 12:42 AM".
 */
fun chargeWhen(
    session: ChargeSession,
    zone: TimeZone = TimeZone.getDefault(),
    h24: Boolean = false,
): String {
    val day = SimpleDateFormat("EEE, MMM d", Locale.US).apply { timeZone = zone }.format(Date(session.startedAtMs))
    val from = clockLabel(session.startedAtMs, zone = zone, h24 = h24)
    val to = session.endedAtMs?.let { clockLabel(it, zone = zone, h24 = h24) }
    val span =
        when {
            session.estimated && to != null -> "between $from and $to"
            else -> listOfNotNull(from, to).joinToString(" – ")
        }
    return "$day · $span"
}

private const val HOUR_MS = 3_600_000.0
