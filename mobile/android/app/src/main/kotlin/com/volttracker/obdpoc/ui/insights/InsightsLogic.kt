package com.volttracker.obdpoc.ui.insights

import com.volttracker.obdpoc.ui.trips.TripSummary
import com.volttracker.obdpoc.ui.trips.avgMiPerKwh
import com.volttracker.obdpoc.ui.trips.electricShare
import com.volttracker.obdpoc.ui.trips.evMiles
import com.volttracker.obdpoc.ui.trips.gasMiles
import com.volttracker.obdpoc.ui.trips.loggedKwh
import com.volttracker.obdpoc.ui.trips.miles
import com.volttracker.obdpoc.ui.trips.savedVsGas
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * The calendar span a period covers — this week, this month, this year, or everything since
 * the first drive — split into the bars of the electric / gas chart, with the span before it to
 * compare against.
 */
data class PeriodWindow(
    val startMs: Long,
    val endMs: Long,
    /** "April 2026", "Apr 26 – May 2", "2026", "Since Mar 2025". */
    val title: String,
    /** Where each bar starts; the last one runs to [endMs]. */
    val bucketStarts: List<Long>,
    val bucketLabels: List<String>,
    /** The span before this one, and what to call it ("March", "last week", "2025"); null for All. */
    val previous: LongRange? = null,
    val previousName: String? = null,
)

/** Electric and gas miles in one bar of the chart. */
data class ModeBucket(
    val label: String,
    val evMiles: Double,
    val gasMiles: Double,
)

fun InsightsPeriod.window(
    nowMs: Long,
    firstTripMs: Long?,
    zone: TimeZone = TimeZone.getDefault(),
): PeriodWindow {
    val cal = calendar(nowMs, zone)
    return when (this) {
        InsightsPeriod.WEEK -> weekWindow(cal, zone)
        InsightsPeriod.MONTH -> monthWindow(cal, zone)
        InsightsPeriod.YEAR -> yearWindow(cal, zone)
        InsightsPeriod.ALL -> allWindow(nowMs, firstTripMs ?: nowMs, zone)
    }
}

private fun weekWindow(
    cal: Calendar,
    zone: TimeZone,
): PeriodWindow {
    startOfDay(cal)
    while (cal.get(Calendar.DAY_OF_WEEK) != cal.firstDayOfWeek) cal.add(Calendar.DAY_OF_MONTH, -1)
    val start = cal.timeInMillis
    val days =
        (0 until DAYS_PER_WEEK).map {
            cal.timeInMillis.also { cal.add(Calendar.DAY_OF_MONTH, 1) }
        }
    val end = cal.timeInMillis
    cal.timeInMillis = start
    cal.add(Calendar.DAY_OF_MONTH, -DAYS_PER_WEEK)
    val last = days.last()
    return PeriodWindow(
        startMs = start,
        endMs = end,
        title = "${format("MMM d", start, zone)} – ${format(sameMonthPattern(start, last, zone), last, zone)}",
        bucketStarts = days,
        bucketLabels = days.map { format("EEE", it, zone) },
        previous = cal.timeInMillis until start,
        previousName = "last week",
    )
}

private fun monthWindow(
    cal: Calendar,
    zone: TimeZone,
): PeriodWindow {
    startOfDay(cal)
    cal.set(Calendar.DAY_OF_MONTH, 1)
    val start = cal.timeInMillis
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val weeks =
        (1..daysInMonth step DAYS_PER_WEEK).map { day ->
            calendar(start, zone).apply { set(Calendar.DAY_OF_MONTH, day) }.timeInMillis
        }
    cal.add(Calendar.MONTH, 1)
    val end = cal.timeInMillis
    cal.add(Calendar.MONTH, -2)
    val prevStart = cal.timeInMillis
    return PeriodWindow(
        startMs = start,
        endMs = end,
        title = format("MMMM yyyy", start, zone),
        bucketStarts = weeks,
        bucketLabels = weeks.mapIndexed { i, at -> format(if (i == 0) "MMM d" else "d", at, zone) },
        previous = prevStart until start,
        previousName = format("MMMM", prevStart, zone),
    )
}

private fun yearWindow(
    cal: Calendar,
    zone: TimeZone,
): PeriodWindow {
    startOfDay(cal)
    cal.set(Calendar.DAY_OF_YEAR, 1)
    val start = cal.timeInMillis
    val months =
        (0 until MONTHS_PER_YEAR).map {
            cal.timeInMillis.also { cal.add(Calendar.MONTH, 1) }
        }
    val end = cal.timeInMillis
    cal.timeInMillis = start
    cal.add(Calendar.YEAR, -1)
    return PeriodWindow(
        startMs = start,
        endMs = end,
        title = format("yyyy", start, zone),
        bucketStarts = months,
        bucketLabels = months.map { format("MMM", it, zone).take(1) },
        previous = cal.timeInMillis until start,
        previousName = format("yyyy", cal.timeInMillis, zone),
    )
}

/** Everything since the first drive: a bar a month for up to a year, else a bar a year. */
private fun allWindow(
    nowMs: Long,
    firstMs: Long,
    zone: TimeZone,
): PeriodWindow {
    val cal = calendar(minOf(firstMs, nowMs), zone)
    startOfDay(cal)
    cal.set(Calendar.DAY_OF_MONTH, 1)
    val now = calendar(nowMs, zone)
    val months =
        (now.get(Calendar.YEAR) - cal.get(Calendar.YEAR)) * MONTHS_PER_YEAR +
            now.get(Calendar.MONTH) - cal.get(Calendar.MONTH) + 1
    val byYear = months > MONTHS_PER_YEAR
    if (byYear) cal.set(Calendar.DAY_OF_YEAR, 1)
    val start = cal.timeInMillis
    val starts = mutableListOf<Long>()
    while (cal.timeInMillis <= nowMs) {
        starts += cal.timeInMillis
        cal.add(if (byYear) Calendar.YEAR else Calendar.MONTH, 1)
    }
    return PeriodWindow(
        startMs = start,
        endMs = cal.timeInMillis,
        title = "Since ${format("MMM yyyy", firstMs, zone)}",
        bucketStarts = starts,
        bucketLabels = starts.map { format(if (byYear) "yyyy" else "MMM", it, zone) },
    )
}

/** The drives that started inside [range]. */
fun List<TripSummary>.within(range: LongRange): List<TripSummary> = filter { it.startedAtMs in range }

val PeriodWindow.range: LongRange get() = startMs until endMs

/** Electric and gas miles per bar. */
fun PeriodWindow.buckets(trips: List<TripSummary>): List<ModeBucket> =
    bucketStarts.mapIndexed { i, start ->
        val end = bucketStarts.getOrNull(i + 1) ?: endMs
        val inBar = trips.within(start until end)
        ModeBucket(bucketLabels[i], inBar.sumOf { it.evMiles }, inBar.sumOf { it.gasMiles })
    }

/** The selected period's figures, worked out from the drives. */
data class PeriodSummary(
    val window: PeriodWindow,
    val trips: List<TripSummary>,
    /** 0..100 of the classified miles; null when none were classified. */
    val electricPct: Int?,
    /** Percentage points up (+) or down (−) on the previous span; null when either has no share. */
    val deltaPts: Int?,
    val evMiles: Double,
    val gasMiles: Double,
    val totalMiles: Double,
    val buckets: List<ModeBucket>,
    val saved: Double?,
    val kwh: Double,
    val miPerKwh: Double?,
)

fun InsightsUiState.summary(zone: TimeZone = TimeZone.getDefault()): PeriodSummary {
    val window = period.window(nowMs, trips.minOfOrNull { it.startedAtMs }, zone)
    val inPeriod = trips.within(window.range)
    val share = inPeriod.electricShare()
    val previousShare = window.previous?.let { trips.within(it).electricShare() }
    return PeriodSummary(
        window = window,
        trips = inPeriod,
        electricPct = share?.let(::pct),
        deltaPts = if (share != null && previousShare != null) pct(share) - pct(previousShare) else null,
        evMiles = inPeriod.sumOf { it.evMiles },
        gasMiles = inPeriod.sumOf { it.gasMiles },
        totalMiles = inPeriod.sumOf { it.miles },
        buckets = window.buckets(trips),
        saved = inPeriod.savedVsGas(gasMpg, gasPrice, homeRate),
        kwh = inPeriod.loggedKwh(),
        miPerKwh = inPeriod.avgMiPerKwh(),
    )
}

/** "▲ 14 pts vs March", "▼ 3 pts vs last week", "Same as 2025"; null with nothing to compare. */
fun PeriodSummary.deltaText(): String? {
    val delta = deltaPts ?: return null
    val name = window.previousName ?: return null
    return when {
        delta > 0 -> "▲ $delta pts vs $name"
        delta < 0 -> "▼ ${-delta} pts vs $name"
        else -> "Same as $name"
    }
}

/** The band with the best efficiency (ties go to the slower band). */
fun List<SpeedEfficiency>.best(): SpeedEfficiency? = maxByOrNull { it.miPerKwh }

/** A band's label: its middle speed ("45" for 40–50 mph). */
val SpeedEfficiency.midMph: Int get() = mph + BAND_HALF_MPH

/** "1,041". */
fun wholeMiles(miles: Double): String = String.format(Locale.US, "%,d", miles.roundToInt())

/** "$41" and ".20" — dollars and the cents set small; a minus sign leads a loss. */
fun dollarsAndCents(value: Double): Pair<String, String> {
    val cents = (value * CENTS).roundToInt()
    val sign = if (cents < 0) "-" else ""
    val abs = kotlin.math.abs(cents)
    return "$sign$${String.format(Locale.US, "%,d", abs / CENTS)}" to String.format(Locale.US, ".%02d", abs % CENTS)
}

/** Parses the store's efficiency-by-speed rows (bands with a finite, positive ratio). */
object InsightsHistory {
    fun speeds(rows: JSONArray): List<SpeedEfficiency> =
        (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val mph = row.optInt("mph", -1)
            val value = row.optDouble("miPerKwh", Double.NaN)
            if (mph < 0 || value.isNaN() || value.isInfinite() || value <= 0.0) null else SpeedEfficiency(mph, value)
        }

    fun cellDrift(row: JSONObject): CellDrift? {
        if (!row.has("cell")) return null
        return CellDrift(
            cell = row.optInt("cell"),
            belowMeanMv = row.optInt("belowMeanMv"),
            driftMv = row.optInt("driftMv"),
            days = row.optInt("days"),
        )
    }
}

/** How many drives the Insights tab reads, so a year (and most histories) is covered in one read. */
const val INSIGHTS_TRIP_LIMIT = 3000

private fun pct(share: Double): Int = (share * PERCENT).roundToInt()

private fun calendar(
    atMs: Long,
    zone: TimeZone,
): Calendar = Calendar.getInstance(zone, Locale.US).apply { timeInMillis = atMs }

private fun startOfDay(cal: Calendar) {
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
}

private fun format(
    pattern: String,
    atMs: Long,
    zone: TimeZone,
): String = SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone }.format(Date(atMs))

/** "May 2" when the week crosses into a new month, else just "2". */
private fun sameMonthPattern(
    start: Long,
    end: Long,
    zone: TimeZone,
): String = if (calendar(start, zone).get(Calendar.MONTH) == calendar(end, zone).get(Calendar.MONTH)) "d" else "MMM d"

private const val DAYS_PER_WEEK = 7
private const val MONTHS_PER_YEAR = 12
private const val PERCENT = 100
private const val CENTS = 100
private const val BAND_HALF_MPH = 5
