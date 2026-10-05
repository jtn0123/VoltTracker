package com.volttracker.obdpoc.ui.diag

import com.volttracker.obdpoc.ui.components.PillTone
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.drive.driveSubtitle
import java.util.Locale
import kotlin.math.roundToInt

/** A line of Health copy with the tone it's drawn in. */
data class HealthLine(
    val text: String,
    val tone: PillTone,
)

/** The hero's headline and the line under it. */
data class HealthHero(
    val title: String,
    val subtitle: String,
    val tone: PillTone,
)

private const val MINUTE_MS = 60_000L
private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24

/** Longest trailing "(…)" moved from a code's name to its detail line, e.g. "(Bank 1 Sensor 2)". */
private const val MAX_QUALIFIER = 24

/** A saved code counts as found by the last scan if it was seen within this span before it ended. */
const val SCAN_WINDOW_MS = 10 * MINUTE_MS

/** How many earlier codes the hero names before "+N more". */
private const val EARLIER_SHOWN = 3

/** "just now" / "4 min ago" / "2 h ago" / "3 days ago". */
fun agoText(
    atMs: Long,
    nowMs: Long,
): String {
    val minutes = ((nowMs - atMs).coerceAtLeast(0L) / MINUTE_MS).toInt()
    val hours = minutes / MINUTES_PER_HOUR
    val days = hours / HOURS_PER_DAY
    return when {
        minutes < 1 -> "just now"
        minutes < MINUTES_PER_HOUR -> "$minutes min ago"
        hours < HOURS_PER_DAY -> "$hours h ago"
        days == 1 -> "1 day ago"
        else -> "$days days ago"
    }
}

private val TRAILING_QUALIFIER = Regex("""^(.*\S)\s*\(([^()]+)\)$""")

/** The code's name in plain words: the table's wording, else its family, else "Unrecognized code". */
val DtcCode.title: String
    get() {
        val text = description ?: return DtcCatalog.familyName(code) ?: "Unrecognized code"
        val match = TRAILING_QUALIFIER.find(text) ?: return text
        return if (match.groupValues[2].length <= MAX_QUALIFIER) match.groupValues[1] else text
    }

/** A short trailing "(Bank 1)" from the table's wording, shown on the detail line instead. */
private val DtcCode.qualifier: String?
    get() {
        val match = description?.let(TRAILING_QUALIFIER::find) ?: return null
        return match.groupValues[2].takeIf { it.length <= MAX_QUALIFIER }
    }

val DtcCode.pending: Boolean get() = status.equals(DtcCode.STATUS_PENDING, ignoreCase = true)

/** "Bank 1 · seen 4× · first 3 days ago" / "Bank 1 · pending · seen once". */
fun DtcCode.detailLine(nowMs: Long): String {
    val statusWord =
        when (status.lowercase(Locale.US)) {
            DtcCode.STATUS_PENDING -> "pending"
            DtcCode.STATUS_PERMANENT -> "permanent"
            DtcCode.STATUS_FREEZE_FRAME -> "freeze frame"
            else -> null
        }
    val seen = if (seenCount <= 1) "seen once" else "seen $seenCount×"
    val first = if (seenCount > 1 && firstSeenMs > 0L) "first ${agoText(firstSeenMs, nowMs)}" else null
    return listOfNotNull(qualifier, statusWord, seen, first).joinToString(" · ")
}

/** The code's pill: what to do about it. A pending code hasn't lit the check-engine lamp yet. */
fun DtcCode.pill(): HealthLine =
    when (severity) {
        DtcSeverity.ALERT -> HealthLine("Stop safely", PillTone.BAD)
        DtcSeverity.WARNING -> HealthLine(if (pending) "Watch" else "Service soon", PillTone.WARN)
        DtcSeverity.INFO -> HealthLine("Monitor", PillTone.NEUTRAL)
    }

/** The worst code-based service priority; this does not establish vehicle driving safety. */
fun worstSeverity(codes: List<DtcCode>): DtcSeverity? = codes.maxOfOrNull { it.severity }

/** Service priority and catalog confidence, never a guarantee about driving or other systems. */
fun serviceGuidance(codes: List<DtcCode>): HealthLine? {
    val worst = worstSeverity(codes) ?: return null
    val unknown = codes.any { it.description.isNullOrBlank() }
    val priority =
        when {
            unknown -> "Review these codes — some are not in the catalog."
            worst == DtcSeverity.ALERT -> "Urgent service — follow the vehicle's warning messages."
            worst == DtcSeverity.WARNING -> "Service soon — have these faults checked."
            else -> "Monitor these codes at your next service."
        }
    val tone =
        when {
            worst == DtcSeverity.ALERT -> PillTone.BAD
            unknown || worst == DtcSeverity.WARNING -> PillTone.WARN
            else -> PillTone.NEUTRAL
        }
    return HealthLine("$priority Driving safety cannot be determined from these codes alone.", tone)
}

/** "1 stored · 1 pending" — pending codes haven't lit the check-engine lamp, so they're counted apart. */
fun statusCounts(codes: List<DtcCode>): String {
    val byStatus = codes.groupingBy { it.status.lowercase(Locale.US) }.eachCount()
    val pending = byStatus[DtcCode.STATUS_PENDING] ?: 0
    val permanent = byStatus[DtcCode.STATUS_PERMANENT] ?: 0
    val stored = codes.size - pending - permanent
    return listOfNotNull(
        stored.takeIf { it > 0 }?.let { "$it stored" },
        pending.takeIf { it > 0 }?.let { "$it pending" },
        permanent.takeIf { it > 0 }?.let { "$it permanent" },
    ).joinToString(" · ")
}

/** The Health hero: busy, not scanned yet, clean (or just cleared), or the codes found. */
fun DiagUiState.hero(): HealthHero {
    busyLabel?.let { return HealthHero("Checking the car…", it, PillTone.NEUTRAL) }
    val found = codes ?: return HealthHero("Not scanned yet", "Scan to read the car's trouble codes.", PillTone.NEUTRAL)
    if (found.isEmpty()) {
        val cleared = clearedAtMs
        val subtitle =
            when {
                cleared != null -> "Codes cleared · ${agoText(cleared, nowMs)}"
                scannedAtMs != null -> "The last scan found none · ${agoText(scannedAtMs, nowMs)}"
                else -> "None saved"
            }
        return HealthHero("No trouble codes", subtitle, PillTone.EV)
    }
    val title = if (found.size == 1) "1 trouble code" else "${found.size} trouble codes"
    val scanned = scannedAtMs
    val latest = found.maxOf { it.lastSeenMs }
    val whenText =
        when {
            scanned != null -> agoText(scanned, nowMs)
            latest > 0L -> "last seen ${agoText(latest, nowMs)}"
            else -> null
        }
    val tone = if (worstSeverity(found) == DtcSeverity.ALERT) PillTone.BAD else PillTone.WARN
    return HealthHero(title, listOfNotNull(statusCounts(found), whenText).joinToString(" · "), tone)
}

/** "Earlier: P0171, P0300 — not found on the last scan", for saved codes the car has since dropped. */
fun DiagUiState.earlierLine(): String? {
    if (earlierCodes.isEmpty()) return null
    val shown = earlierCodes.take(EARLIER_SHOWN).joinToString(", ")
    val more = earlierCodes.size - EARLIER_SHOWN
    val list = if (more > 0) "$shown +$more more" else shown
    val since = if (clearedAtMs != null) "cleared since" else "not found on the last scan"
    return "Earlier: $list — $since"
}

/** The Car tab's Vehicle health row: "2 trouble codes · scanned 2 h ago". */
fun DiagUiState.summary(): String {
    val found = codes ?: return "Not scanned yet"
    val count =
        when (found.size) {
            0 -> "No trouble codes"
            1 -> "1 trouble code"
            else -> "${found.size} trouble codes"
        }
    return listOfNotNull(count, scannedAtMs?.let { "scanned ${agoText(it, nowMs)}" }).joinToString(" · ")
}

/** The Freeze frame row: which code the car captured one with. */
fun DiagUiState.freezeFrameLine(): String =
    freezeFrameDtc()?.let { "Captured with $it" } ?: if (codes == null) "Not scanned yet" else "None stored"

/** Whether the car has a freeze frame to show (the row is only tappable then). */
fun DiagUiState.hasFreezeFrame(): Boolean = freezeFrameDtc() != null

/** The code the freeze frame was captured with: from its read readings, else the saved code row. */
fun DiagUiState.freezeFrameDtc(): String? =
    freezeFrame?.dtc?.takeIf { it.isNotBlank() }
        ?: codes?.firstOrNull { it.status.equals(DtcCode.STATUS_FREEZE_FRAME, ignoreCase = true) }?.code

/**
 * Health's header: the same connection line as Drive ("Live · OBDLink MX+", "Connected · parked"),
 * and "Scanning…" / "Not connected" off the link.
 */
fun DiagUiState.statusLine(drive: DriveUiState): String =
    if (connected && drive.connected) driveSubtitle(drive) else statusLabel

/** The Live signals row: "78 readings coming in", or why there are none. */
fun liveSignalsLine(drive: DriveUiState): String =
    when {
        !drive.connected -> "Not connected"
        drive.signalCount > 0 -> "${drive.signalCount} readings coming in"
        else -> "Waiting for data"
    }

/** The Adapter row: "OBDLink MX+ · connected". */
fun adapterLine(
    adapterLabel: String,
    connected: Boolean,
): String {
    val name = adapterLabel.takeIf { it.isNotBlank() && it != "--" } ?: return "No adapter remembered"
    return "$name · ${if (connected) "connected" else "not connected"}"
}

/** Everything the HV battery card shows, from the pack's last reads. */
data class HvBattery(
    val sohPct: Double?,
    val capacityAh: Double?,
    val spreadMv: Double?,
    val cells: List<Double?>,
    val groups: Int,
    val weakestCell: Int?,
    val weakestVolts: Double?,
    val resistanceMohm: Double? = null,
) {
    val reported: Boolean get() = sohPct != null || capacityAh != null || spreadMv != null || resistanceMohm != null
}

/** The Gen 2 Volt pack's rated capacity: the "of 52 Ah new" the Ah figure is read against. */
const val PACK_NEW_AH = 52.0

/** The Gen 2 Volt pack's series cell groups. */
const val PACK_CELL_GROUPS = 96

fun hvBattery(
    drive: DriveUiState,
    sohPct: Double?,
    capacityAh: Double?,
): HvBattery =
    HvBattery(
        sohPct = sohPct,
        capacityAh = capacityAh,
        spreadMv = drive.cellSpreadMv,
        cells = drive.cellVoltages,
        groups = drive.cellVoltages.size.takeIf { it > 0 } ?: PACK_CELL_GROUPS,
        weakestCell = drive.minCellNumber,
        weakestVolts = drive.minCellVolts,
        resistanceMohm = drive.packResistanceMohm,
    )

/** "Internal resistance 293 mΩ", or null until the pack reports it. */
fun HvBattery.resistanceLine(): String? = resistanceMohm?.let { "Internal resistance ${it.roundToInt()} mΩ" }

/** "#47 · 3.893 V" for the weakest cell group. */
fun HvBattery.weakestLabel(): String? {
    val cell = weakestCell ?: return null
    return listOfNotNull("#$cell", weakestVolts?.let { String.format(Locale.US, "%.3f V", it) }).joinToString(" · ")
}

/** A plain-text report of the Health screen, for Share report. */
fun healthReport(
    diag: DiagUiState,
    battery: HvBattery,
    demo: Boolean,
): String {
    val hero = diag.hero()
    val lines = mutableListOf("Volt Tracker health report", "")
    if (demo) lines += listOf("Demo data — not from a real car.", "")
    lines += "${hero.title} (${hero.subtitle})"
    diag.codes.orEmpty().forEach { code ->
        lines += "- ${code.code} ${code.title} — ${code.pill().text} (${code.detailLine(diag.nowMs)})"
    }
    serviceGuidance(diag.codes.orEmpty())?.let { lines += it.text }
    diag.earlierLine()?.let { lines += it }
    if (battery.reported) {
        lines += ""
        lines +=
            "HV battery: " +
            listOfNotNull(
                battery.sohPct?.let { "${it.roundToInt()}% capacity health" },
                battery.capacityAh?.let { String.format(Locale.US, "%.1f Ah of %.0f Ah new", it, PACK_NEW_AH) },
                battery.spreadMv?.let { "cell spread ${it.roundToInt()} mV" },
                battery.resistanceMohm?.let { "internal resistance ${it.roundToInt()} mΩ" },
                battery.weakestLabel()?.let { "weakest cell $it" },
            ).joinToString(", ")
    }
    if (diag.sohHistory.isNotEmpty()) lines += "Battery health trend: ${sohTrend(diag.sohHistory).sentence}"
    return lines.joinToString("\n")
}
