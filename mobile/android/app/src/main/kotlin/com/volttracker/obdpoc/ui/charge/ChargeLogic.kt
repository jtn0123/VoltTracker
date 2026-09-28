package com.volttracker.obdpoc.ui.charge

import com.volttracker.obdpoc.ui.drive.ChargeEta
import com.volttracker.obdpoc.ui.drive.chargeEta
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.pow
import kotlin.math.roundToInt

/** The SOC the driver knows: the cluster's figure when the car reports it, else the raw pack SOC. */
val ChargeUiState.shownSocPercent: Double get() = displayedSocPercent ?: socPercent

/** Time to the charge limit at the current charger power; null while not charging or already there. */
val ChargeUiState.eta: ChargeEta?
    get() = if (charging) chargeEta(shownSocPercent, chargeKw, sohPct, targetSoc.toDouble()) else null

/** "Plugged in · Level 2" while charging; otherwise the connection label. */
val ChargeUiState.subtitle: String
    get() =
        if (charging) {
            listOfNotNull("Plugged in", levelName(level)).joinToString(" · ")
        } else {
            statusLabel
        }

/** "Level 2" for "L2"; other levels read as reported. */
fun levelName(level: String?): String? =
    when (level) {
        null -> null
        "L1" -> "Level 1"
        "L2" -> "Level 2"
        else -> level
    }

/** $/kWh for a session: the public rate for a public charger when one is set, else the home rate. */
fun ChargeUiState.rateFor(session: ChargeSession): Double =
    if (session.publicCharger && publicRate > 0.0) publicRate else homeRate

/** Rows for the Recent sessions card, newest first: the live charge (if any) then logged ones. */
data class SessionRow(
    val title: String,
    val live: Boolean,
    val detail: String,
    val fromSoc: Int?,
    val toSoc: Int?,
    val energyKwh: Double?,
    val cost: String?,
)

/**
 * The live charge as a row, followed by the logged charges. A logged row still open (no end
 * time) is the same charge the live row shows, so it is dropped while charging.
 */
fun ChargeUiState.sessionRows(
    limit: Int = RECENT_ROWS,
    zone: TimeZone = TimeZone.getDefault(),
    h24: Boolean = false,
): List<SessionRow> {
    val live =
        if (charging) {
            listOf(
                SessionRow(
                    title = partOfDay(sampleAtMs, zone),
                    live = true,
                    detail = sessionDetail(level, fromSoc?.roundToInt(), shownSocPercent.roundToInt()),
                    fromSoc = fromSoc?.roundToInt(),
                    toSoc = shownSocPercent.roundToInt(),
                    energyKwh = addedKwh,
                    cost = costText(addedKwh, homeRate),
                ),
            )
        } else {
            emptyList()
        }
    val logged =
        sessions
            .filter { !charging || it.endedAtMs != null }
            .map { s ->
                SessionRow(
                    title = sessionWhen(s.startedAtMs, zone, h24),
                    live = false,
                    detail = sessionDetail(s.level, s.fromSoc, s.toSoc),
                    fromSoc = s.fromSoc,
                    toSoc = s.toSoc,
                    energyKwh = s.energyKwh,
                    cost = costText(s.energyKwh, rateFor(s)),
                )
            }
    return (live + logged).take(limit)
}

/** "April · 37.0 kWh · $4.43": this month's charges (the live one included); null when none. */
fun ChargeUiState.monthSummary(
    nowMs: Long,
    zone: TimeZone = TimeZone.getDefault(),
): String? {
    val cal = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
    val month = cal.get(Calendar.MONTH)
    val year = cal.get(Calendar.YEAR)
    val inMonth =
        sessions.filter { s ->
            (!charging || s.endedAtMs != null) &&
                Calendar.getInstance(zone).apply { timeInMillis = s.startedAtMs }.let {
                    it.get(Calendar.MONTH) == month && it.get(Calendar.YEAR) == year
                }
        }
    if (inMonth.isEmpty() && !charging) return null
    val liveKwh = if (charging) addedKwh else 0.0
    val kwh = inMonth.sumOf { it.energyKwh ?: 0.0 } + liveKwh
    val dollars = inMonth.sumOf { (it.energyKwh ?: 0.0) * rateFor(it) } + liveKwh * homeRate
    val monthName = SimpleDateFormat("MMMM", Locale.US).apply { timeZone = zone }.format(Date(nowMs))
    return listOfNotNull(
        monthName,
        String.format(Locale.US, "%.1f kWh", kwh),
        if (homeRate > 0.0 || publicRate > 0.0) String.format(Locale.US, "$%.2f", dollars) else null,
    ).joinToString(" · ")
}

/** "$0.52", or null when no rate is set or the energy wasn't recorded. */
fun costText(
    kwh: Double?,
    rate: Double,
): String? = if (kwh == null || rate <= 0.0) null else String.format(Locale.US, "$%.2f", kwh * rate)

/** "Level 2 · 41% → 71%", leaving out whatever wasn't recorded. */
fun sessionDetail(
    level: String?,
    fromSoc: Int?,
    toSoc: Int?,
): String {
    val span =
        when {
            fromSoc != null && toSoc != null -> "$fromSoc% → $toSoc%"
            toSoc != null -> "to $toSoc%"
            else -> null
        }
    return listOfNotNull(levelName(level), span).joinToString(" · ").ifEmpty { "Charge" }
}

/** "Apr 30 · 9:18 PM", or "Apr 30 · 21:18" on a 24-hour phone. */
fun sessionWhen(
    atMs: Long,
    zone: TimeZone = TimeZone.getDefault(),
    h24: Boolean = false,
): String =
    SimpleDateFormat(if (h24) "MMM d · H:mm" else "MMM d · h:mm a", Locale.US)
        .apply { timeZone = zone }
        .format(Date(atMs))

/** "This morning" / "This afternoon" / "Tonight" for the live row. */
fun partOfDay(
    atMs: Long,
    zone: TimeZone = TimeZone.getDefault(),
): String {
    val hour = Calendar.getInstance(zone).apply { timeInMillis = atMs }.get(Calendar.HOUR_OF_DAY)
    return when {
        hour < NOON -> "This morning"
        hour < EVENING -> "This afternoon"
        else -> "Tonight"
    }
}

/** The session chart's time axis and the projected finish, in ms. */
data class ChargeChartSpan(
    val startMs: Long,
    val nowMs: Long,
    val endMs: Long,
    /** The projected finish, or null when there is no estimate to draw. */
    val finishMs: Long?,
)

/** Time span of the session chart: charge start → projected finish (or "now" with no estimate). */
fun ChargeUiState.chartSpan(): ChargeChartSpan? {
    val now = sampleAtMs.takeIf { it > 0 } ?: return null
    val start = (startedAtMs ?: socPoints.firstOrNull()?.atMs)?.coerceAtMost(now) ?: return null
    val finish = (eta as? ChargeEta.Finish)?.let { now + it.remainingMs }
    val end = finish ?: (now + (now - start) / 2).coerceAtLeast(now + 1)
    return ChargeChartSpan(start, now, end.coerceAtLeast(now + 1), finish)
}

/** The measured SOC line: the recorded points plus the newest reading at "now". */
fun ChargeUiState.measuredPoints(): List<SocPoint> {
    val span = chartSpan() ?: return emptyList()
    val head = listOfNotNull(fromSoc?.let { SocPoint(span.startMs, it.toFloat()) })
    val recorded = socPoints.filter { it.atMs in (span.startMs + 1) until span.nowMs }
    return head + recorded + SocPoint(span.nowMs, shownSocPercent.toFloat())
}

/**
 * The dashed projection from now to the charge limit at [ChargeChartSpan.finishMs]: it eases in
 * toward the end as a Li-ion charge tapers (the mockup's `1 - (1 - u)^1.25`).
 */
fun ChargeUiState.projectedPoints(steps: Int = PROJECTION_STEPS): List<SocPoint> {
    val span = chartSpan() ?: return emptyList()
    val finish = span.finishMs ?: return emptyList()
    val from = shownSocPercent
    val to = targetSoc.toDouble()
    return (0..steps).map { i ->
        val u = i.toDouble() / steps
        val soc = from + (to - from) * (1 - (1 - u).pow(PROJECTION_EASE))
        SocPoint(span.nowMs + ((finish - span.nowMs) * u).toLong(), soc.toFloat())
    }
}

/** Parses the store's charge rows (`chargeSessionsForExport`) into sessions, newest first. */
object ChargeHistory {
    fun parse(rows: JSONArray): List<ChargeSession> =
        (0 until rows.length())
            .mapNotNull { rows.optJSONObject(it) }
            .mapNotNull(::parseRow)
            .sortedByDescending { it.startedAtMs }

    private fun parseRow(row: JSONObject): ChargeSession? {
        val started = row.optLong("startedAtMs", 0L).takeIf { it > 0 } ?: return null
        val type = if (row.isNull("chargerType")) null else row.optString("chargerType", "")
        return ChargeSession(
            startedAtMs = started,
            endedAtMs = optNumber(row, "endedAtMs")?.toLong()?.takeIf { it > 0 },
            level = levelFromChargerType(type),
            fromSoc = optNumber(row, "startSoc")?.roundToInt(),
            toSoc = optNumber(row, "endSoc")?.roundToInt(),
            energyKwh = optNumber(row, "energyKwh")?.takeIf { it >= 0.0 },
            publicCharger = isPublicCharger(type),
        )
    }

    private fun optNumber(
        row: JSONObject,
        key: String,
    ): Double? = if (row.has(key) && !row.isNull(key)) row.optDouble(key).takeIf { it.isFinite() } else null

    /** The classic dashboard's charger labels (charge-history.ts `chargerLabel`), shortened. */
    fun levelFromChargerType(type: String?): String? {
        val key = normalized(type)
        return when {
            key in setOf("level1", "level_1", "l1", "ac_1") -> "L1"
            key in setOf("level2", "level_2", "l2", "ac_2") -> "L2"
            isPublicCharger(type) -> "DC fast".takeIf { "dc" in key || "ccs" in key || "chademo" in key }
            else -> null
        }
    }

    /** Mirrors cost-model.ts `isPublicChargerType`. */
    fun isPublicCharger(type: String?): Boolean {
        val key = normalized(type)
        return PUBLIC_MARKERS.any { it in key }
    }

    private fun normalized(type: String?): String =
        type
            .orEmpty()
            .trim()
            .lowercase(Locale.US)
            .replace(Regex("[\\s-]+"), "_")

    private val PUBLIC_MARKERS = listOf("dc_fast", "dcfast", "dcfc", "ccs", "chademo", "supercharger", "public")
}

/** Rows shown in Recent sessions. */
const val RECENT_ROWS = 6

/** Logged charges read for the list and the month total. */
const val CHARGE_HISTORY_LIMIT = 120

private const val NOON = 12
private const val EVENING = 17
private const val PROJECTION_STEPS = 24
private const val PROJECTION_EASE = 1.25
