package com.volttracker.obdpoc.ui.diag

import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** One logged battery read: capacity health in %, from the car's figure or its Ah against new. */
data class SohPoint(
    val atMs: Long,
    val pct: Double,
)

/**
 * The battery's capacity health over time: one point per day (that day's median, since single
 * reads bounce with temperature and charge), oldest first, and a plain sentence on where it
 * has gone since the first day.
 */
data class SohTrend(
    val days: List<SohPoint>,
    val sentence: String,
) {
    /** Enough days to draw a line. */
    val drawable: Boolean get() = days.size >= 2
}

fun sohTrend(
    points: List<SohPoint>,
    zone: TimeZone = TimeZone.getDefault(),
): SohTrend {
    val days =
        points
            .groupBy { dayKey(it.atMs, zone) }
            .values
            .map { day -> SohPoint(day.minOf { it.atMs }, median(day.map { it.pct })) }
            .sortedBy { it.atMs }
    if (days.size < 2) return SohTrend(days, "The trend appears once the car has reported on a second day.")
    val first = days.first()
    val change = days.last().pct - first.pct
    val since = SimpleDateFormat("MMM yyyy", Locale.US).apply { timeZone = zone }.format(Date(first.atMs))
    val spanYears = (days.last().atMs - first.atMs) / YEAR_MS
    val sentence =
        when {
            abs(change) < STEADY_PTS -> "Steady since $since."
            change < 0 && spanYears >= MIN_RATE_YEARS ->
                "Down ${points(-change)} since $since, about ${points(-change / spanYears)} a year."
            change < 0 -> "Down ${points(-change)} since $since."
            else -> "Up ${points(change)} since $since — reads vary a little with temperature."
        }
    return SohTrend(days, sentence)
}

/** "1 point", "2.5 points", "5 points" (a whole figure drops its ".0"). */
private fun points(value: Double): String {
    val text = String.format(Locale.US, "%.1f", value).removeSuffix(".0")
    return if (text == "1") "1 point" else "$text points"
}

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}

private fun dayKey(
    atMs: Long,
    zone: TimeZone,
): Int {
    val cal = Calendar.getInstance(zone).apply { timeInMillis = atMs }
    return cal.get(Calendar.YEAR) * DAYS_KEY + cal.get(Calendar.DAY_OF_YEAR)
}

/** Parses `getBatterySohHistoryJson` rows (oldest first) into capacity-health points. */
object SohHistory {
    fun parse(rows: JSONArray): List<SohPoint> =
        (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val at = row.optLong("capturedAtMs", 0L).takeIf { it > 0L } ?: return@mapNotNull null
            val soh = row.optDouble("sohPct", Double.NaN).takeIf { it.isFinite() && it in PLAUSIBLE }
            val ah = row.optDouble("capacityAh", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
            val pct = soh ?: ah?.let { it / PACK_NEW_AH * PERCENT }?.takeIf { it in PLAUSIBLE }
            pct?.let { SohPoint(at, it) }
        }

    /** The demo's pack: about 18 months of reads, drifting from 96 % to the 91 % Health shows. */
    fun demo(nowMs: Long): List<SohPoint> =
        (0 until DEMO_MONTHS).map { i ->
            val left = DEMO_MONTHS - 1 - i
            val wobble = if (i % 3 == 1) DEMO_WOBBLE else 0.0
            SohPoint(nowMs - left * MONTH_MS, DEMO_START - (DEMO_START - DEMO_END) * i / (DEMO_MONTHS - 1) + wobble)
        }
}

private val PLAUSIBLE = 40.0..105.0
private const val PERCENT = 100.0
private const val STEADY_PTS = 0.5
private const val MIN_RATE_YEARS = 0.5
private const val DAYS_KEY = 1000
private const val YEAR_MS = 365.25 * 86_400_000.0
private const val MONTH_MS = 30L * 86_400_000L
private const val DEMO_MONTHS = 18
private const val DEMO_START = 96.0
private const val DEMO_END = 91.0
private const val DEMO_WOBBLE = 0.4
