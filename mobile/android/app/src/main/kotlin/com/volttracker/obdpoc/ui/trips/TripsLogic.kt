package com.volttracker.obdpoc.ui.trips

import com.volttracker.obdpoc.ui.HistoryLoad
import com.volttracker.obdpoc.ui.drive.clockLabel
import com.volttracker.obdpoc.ui.drive.durationLabel
import com.volttracker.obdpoc.ui.units.VoltUnits
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** How a drive was powered: all electric, part gas, or (almost) all gas. */
enum class TripMode { EV, MIXED, GAS }

const val METERS_PER_MILE = 1609.344

val TripSummary.miles: Double get() = distanceMeters / METERS_PER_MILE

/** Unclassified drives count as electric: that is how the Volt drives unless the engine runs. */
val TripSummary.mode: TripMode
    get() {
        val share = evShare ?: return TripMode.EV
        return when {
            share >= ALL_EV -> TripMode.EV
            share <= ALL_GAS -> TripMode.GAS
            else -> TripMode.MIXED
        }
    }

val TripSummary.evMiles: Double get() = miles * (evShare ?: 1.0)

val TripSummary.gasMiles: Double get() = (miles - evMiles).coerceAtLeast(0.0)

/** Electric miles per kWh; null when energy wasn't logged or the ratio is implausible. */
val TripSummary.miPerKwh: Double?
    get() {
        val kwh = energyKwh?.takeIf { it >= MIN_KWH } ?: return null
        return (evMiles / kwh).takeIf { it > 0.0 && it <= MAX_MI_PER_KWH }
    }

/** The user's label, else "Morning drive" / "Evening drive" from the start hour (as the WebView names drives). */
fun TripSummary.title(zone: TimeZone = TimeZone.getDefault()): String =
    label.ifBlank { daypartTitle(startedAtMs, zone) }

fun daypartTitle(
    atMs: Long,
    zone: TimeZone = TimeZone.getDefault(),
): String {
    val hour = Calendar.getInstance(zone).apply { timeInMillis = atMs }.get(Calendar.HOUR_OF_DAY)
    val part =
        when {
            hour < DAWN -> "Night"
            hour < NOON -> "Morning"
            hour < EVENING -> "Afternoon"
            hour < NIGHT -> "Evening"
            else -> "Night"
        }
    return "$part drive"
}

/** "8:14 AM · 28 min". */
fun TripSummary.whenLine(zone: TimeZone = TimeZone.getDefault()): String =
    "${clockLabel(startedAtMs, zone = zone)} · ${durationLabel(endedAtMs - startedAtMs)}"

/** "4.1 mi/kWh" for an electric drive, "64% electric" for a mixed one, "Gas" when the engine drove it all. */
fun TripSummary.efficiencyText(units: VoltUnits = VoltUnits.Imperial): String? =
    when (mode) {
        TripMode.EV -> miPerKwh?.let(units::efficiencyText)
        TripMode.MIXED -> "${percent(evShare ?: 0.0)}% electric"
        TripMode.GAS -> "Gas"
    }

/** Map chip line: "Apr 28 · 184.2 mi · 21% electric". */
fun TripSummary.chipDetail(
    zone: TimeZone = TimeZone.getDefault(),
    units: VoltUnits = VoltUnits.Imperial,
): String =
    listOfNotNull(
        dayLabel(startedAtMs, zone),
        "${units.distanceOneDecimal(miles)} ${units.distanceUnit}",
        efficiencyText(units),
    ).joinToString(" · ")

/** "Today", "Yesterday", else "Apr 28" (with the year when it isn't this year's). */
fun dayGroupLabel(
    atMs: Long,
    nowMs: Long,
    zone: TimeZone = TimeZone.getDefault(),
): String {
    val day = Calendar.getInstance(zone).apply { timeInMillis = atMs }
    val today = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
    val yesterday = Calendar.getInstance(zone).apply { timeInMillis = nowMs - DAY_MS }
    return when {
        sameDay(day, today) -> "Today"
        sameDay(day, yesterday) -> "Yesterday"
        day.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> dayLabel(atMs, zone)
        else -> SimpleDateFormat("MMM d, yyyy", Locale.US).apply { timeZone = zone }.format(Date(atMs))
    }
}

/** A day heading and its drives, newest first. */
data class TripGroup(
    val label: String,
    val trips: List<TripSummary>,
)

fun TripsUiState.groups(zone: TimeZone = TimeZone.getDefault()): List<TripGroup> {
    val groups = mutableListOf<TripGroup>()
    trips.forEach { trip ->
        val label = dayGroupLabel(trip.startedAtMs, nowMs, zone)
        val last = groups.lastOrNull()
        if (last?.label ==
            label
        ) {
            groups[groups.lastIndex] = last.copy(trips = last.trips + trip)
        } else {
            groups +=
                TripGroup(label, listOf(trip))
        }
    }
    return groups
}

/** This month's drives (the header figures summarise them). */
fun TripsUiState.monthTrips(zone: TimeZone = TimeZone.getDefault()): List<TripSummary> {
    val now = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
    return trips.filter { t ->
        Calendar.getInstance(zone).apply { timeInMillis = t.startedAtMs }.let {
            it.get(Calendar.MONTH) == now.get(Calendar.MONTH) && it.get(Calendar.YEAR) == now.get(Calendar.YEAR)
        }
    }
}

/** "6 drives · 416 mi · April", or "No drives yet in April"; no month until the drives are read. */
fun TripsUiState.subtitle(zone: TimeZone = TimeZone.getDefault()): String {
    when (history) {
        HistoryLoad.LOADING -> return "Loading drives…"
        HistoryLoad.FAILED -> return "Drives couldn't be read"
        HistoryLoad.LOADED -> Unit
    }
    val month = SimpleDateFormat("MMMM", Locale.US).apply { timeZone = zone }.format(Date(nowMs))
    val inMonth = monthTrips(zone)
    if (inMonth.isEmpty()) return "No drives yet in $month"
    val drives = if (inMonth.size == 1) "1 drive" else "${inMonth.size} drives"
    return "$drives · ${units.distanceWhole(inMonth.sumOf { it.miles })} ${units.distanceUnit} · $month"
}

/** This month's share of classified driving done on electric, distance-weighted; null when none is classified. */
fun TripsUiState.electricPct(zone: TimeZone = TimeZone.getDefault()): Int? = monthTrips(zone).electricPct()

/** This month's electric efficiency: EV miles over the logged kWh of the drives that logged energy. */
fun TripsUiState.avgMiPerKwh(zone: TimeZone = TimeZone.getDefault()): Double? = monthTrips(zone).avgMiPerKwh()

/** What this month's electric miles saved against gas (see [savedVsGas] on a list of drives). */
fun TripsUiState.savedVsGas(zone: TimeZone = TimeZone.getDefault()): Double? =
    monthTrips(zone).savedVsGas(gasMpg, gasPrice, homeRate)

/** The share (0..1) of these drives' classified miles driven on electric; null when none is classified. */
fun List<TripSummary>.electricShare(): Double? {
    val classified = filter { it.evShare != null }
    val miles = classified.sumOf { it.miles }
    return if (miles > 0.0) classified.sumOf { it.evMiles } / miles else null
}

fun List<TripSummary>.electricPct(): Int? = electricShare()?.let(::percent)

/** EV miles over the logged kWh of the drives that logged a plausible amount. */
fun List<TripSummary>.avgMiPerKwh(): Double? {
    val logged = filter { it.miPerKwh != null }
    val kwh = logged.sumOf { it.energyKwh ?: 0.0 }
    return if (kwh > 0.0) logged.sumOf { it.evMiles } / kwh else null
}

/** The kWh these drives logged (drives without an energy reading add nothing). */
fun List<TripSummary>.loggedKwh(): Double = sumOf { it.energyKwh?.takeIf { kwh -> kwh >= MIN_KWH } ?: 0.0 }

/**
 * What these drives' electric miles would have cost in gas, less the electricity they used
 * (cost-model.ts's formula, applied to the EV miles only: gas miles saved nothing). Energy that
 * wasn't logged is estimated at 3.5 mi/kWh, as the WebView does. Null until the home rate, gas
 * price and MPG are all set.
 */
fun List<TripSummary>.savedVsGas(
    gasMpg: Double?,
    gasPrice: Double,
    homeRate: Double,
): Double? {
    val mpg = gasMpg?.takeIf { it > 0.0 } ?: return null
    if (gasPrice <= 0.0 || homeRate <= 0.0) return null
    return sumOf { t ->
        val kwh = t.energyKwh?.takeIf { it >= MIN_KWH } ?: (t.evMiles / ASSUMED_MI_PER_KWH)
        t.evMiles / mpg * gasPrice - kwh * homeRate
    }
}

/** "$38", "-$4". */
fun wholeDollars(value: Double): String {
    val whole = value.roundToInt()
    return if (whole < 0) "-$${-whole}" else "$$whole"
}

/**
 * Where the engine first came on after electric driving: the point, and how far into the track
 * (0..1 of its length). Scale [fraction] by the drive's logged distance for miles — the drawn
 * track is simplified, so its own length runs short.
 */
data class EngineOnMark(
    val index: Int,
    val fraction: Double,
)

fun TripRoute.engineOn(): EngineOnMark? {
    val legs = (1 until points.size).map { distanceMiles(points[it - 1], points[it]) }
    val total = legs.sum()
    var along = 0.0
    for (i in 1 until points.size) {
        along += legs[i - 1]
        if (points[i].gas && !points[i - 1].gas) {
            return EngineOnMark(i, if (total > 0.0) along / total else i.toDouble() / (points.size - 1))
        }
    }
    return null
}

/** Great-circle distance between two route points, in miles. */
fun distanceMiles(
    a: TripPoint,
    b: TripPoint,
): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h =
        sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_MILES * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/** Parses the store's trip rows and route payloads into the Trips tab's state. */
object TripHistory {
    /** `tripsPage` rows, newest first; rows without a key or start time are skipped. */
    fun parse(rows: JSONArray): List<TripSummary> =
        (0 until rows.length())
            .mapNotNull { rows.optJSONObject(it)?.let(::trip) }
            .sortedByDescending { it.startedAtMs }

    private fun trip(row: JSONObject): TripSummary? {
        val key = row.optString("id", "").ifBlank { return null }
        val started = row.optLong("startedAtMs", 0L).takeIf { it > 0L } ?: return null
        val ended = row.optLong("endedAtMs", 0L).takeIf { it >= started } ?: (started + row.optLong("durationMs", 0L))
        return TripSummary(
            routeKey = key,
            startedAtMs = started,
            endedAtMs = ended,
            distanceMeters = row.optDouble("distanceMeters", 0.0).takeIf { it.isFinite() } ?: 0.0,
            energyKwh = finiteOrNull(row, "energyKwh"),
            evShare = finiteOrNull(row, "evShare")?.coerceIn(0.0, 1.0),
            label = row.optString("label", "").trim(),
        )
    }

    /**
     * A `getTripRouteJson` payload joined with the trip's `getTripDriveModesJson` changes: each
     * point takes the mode in force at its time. With no classified samples, every point takes
     * [fallbackGas] (the trip's overall mode).
     */
    fun route(
        routeKey: String,
        route: JSONObject,
        modes: JSONArray,
        fallbackGas: Boolean,
    ): TripRoute {
        val changes =
            (0 until modes.length())
                .mapNotNull { modes.optJSONObject(it) }
                .map { it.optLong("atMs", 0L) to it.optBoolean("gas", false) }
                .sortedBy { it.first }
        val raw = route.optJSONArray("points") ?: JSONArray()
        val points =
            (0 until raw.length()).mapNotNull { i ->
                val p = raw.optJSONObject(i) ?: return@mapNotNull null
                val lat = p.optDouble("lat", Double.NaN)
                val lon = p.optDouble("lng", Double.NaN)
                if (!lat.isFinite() || !lon.isFinite()) return@mapNotNull null
                val atMs = p.optLong("atMs", 0L)
                TripPoint(lat, lon, atMs, gasAt(changes, atMs) ?: fallbackGas)
            }
        return TripRoute(routeKey, points)
    }

    /** The mode in force at [atMs]: the last change at or before it, else the first one. */
    private fun gasAt(
        changes: List<Pair<Long, Boolean>>,
        atMs: Long,
    ): Boolean? = (changes.lastOrNull { it.first <= atMs } ?: changes.firstOrNull())?.second

    private fun finiteOrNull(
        row: JSONObject,
        key: String,
    ): Double? = if (row.isNull(key)) null else row.optDouble(key, Double.NaN).takeIf { it.isFinite() }
}

private fun dayLabel(
    atMs: Long,
    zone: TimeZone,
): String = SimpleDateFormat("MMM d", Locale.US).apply { timeZone = zone }.format(Date(atMs))

private fun sameDay(
    a: Calendar,
    b: Calendar,
): Boolean = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

private fun percent(share: Double): Int = (share * PERCENT).roundToInt()

/** How many trip rows the Trips tab reads (the WebView's trip-list window). */
const val TRIP_HISTORY_LIMIT = 120

private const val ALL_EV = 0.98
private const val ALL_GAS = 0.02
private const val MIN_KWH = 0.05
private const val MAX_MI_PER_KWH = 10.0
private const val ASSUMED_MI_PER_KWH = 3.5
private const val PERCENT = 100
private const val DAWN = 5
private const val NOON = 12
private const val EVENING = 17
private const val NIGHT = 21
private const val DAY_MS = 86_400_000L
private const val EARTH_RADIUS_MILES = 3958.8
