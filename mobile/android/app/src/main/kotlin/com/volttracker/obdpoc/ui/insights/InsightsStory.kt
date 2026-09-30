package com.volttracker.obdpoc.ui.insights

import com.volttracker.obdpoc.ui.trips.gasMiles
import com.volttracker.obdpoc.ui.trips.title
import com.volttracker.obdpoc.ui.units.VoltUnits
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * The period in a few plain sentences, for someone who doesn't read charts: how far and how
 * electric, how that compares with the span before, where the engine ran, and what it saved.
 * Null when the period has no drives (the hero already says so).
 */
fun PeriodSummary.story(
    units: VoltUnits = VoltUnits.Imperial,
    zone: TimeZone = TimeZone.getDefault(),
): List<String>? {
    if (trips.isEmpty()) return null
    val drives = if (trips.size == 1) "1 drive" else "${trips.size} drives"
    val distance = "${wholeMiles(totalMiles, units)} ${units.distanceUnit}"
    return listOfNotNull(
        electricPct?.let { "You drove $distance over $drives, $it% of it on electricity." }
            ?: "You drove $distance over $drives.",
        comparison(),
        engineLine(units, zone),
        savingsLine(units),
    )
}

/** "That's 6 points more electric than March." */
private fun PeriodSummary.comparison(): String? {
    val delta = deltaPts ?: return null
    val name = window.previousName ?: return null
    return when {
        delta > 0 -> "That's $delta ${points(delta)} more electric than $name."
        delta < 0 -> "That's ${-delta} ${points(-delta)} less electric than $name."
        else -> "That's the same electric share as $name."
    }
}

private fun points(n: Int): String = if (n == 1) "point" else "points"

/** Where the engine ran: never, or how far, naming the one drive that did most of it. */
private fun PeriodSummary.engineLine(
    units: VoltUnits,
    zone: TimeZone,
): String? {
    if (electricPct == null) return null
    if (gasMiles < MIN_GAS_MILES) return "The engine never had to drive the car."
    val gas = "${wholeMiles(gasMiles, units)} ${units.distanceUnit}"
    val biggest = trips.maxByOrNull { it.gasMiles }
    return if (biggest != null && biggest.gasMiles >= gasMiles * MOSTLY) {
        "The engine covered $gas, mostly on one drive (${biggest.title(zone)})."
    } else {
        "The engine covered $gas across a few drives."
    }
}

/** "Electricity saved you about $41 over gas, at 4.1 mi/kWh." */
private fun PeriodSummary.savingsLine(units: VoltUnits): String? {
    val efficiency = miPerKwh?.let(units::efficiencyText)
    val saved = saved?.takeIf { it >= 1.0 }?.let { "$${it.roundToInt()}" }
    return when {
        saved != null && efficiency != null -> "Driving on electricity saved about $saved over gas, at $efficiency."
        saved != null -> "Driving on electricity saved about $saved over gas."
        efficiency != null -> "Your electric driving averaged $efficiency."
        else -> null
    }
}

private const val MIN_GAS_MILES = 1.0
private const val MOSTLY = 0.5
