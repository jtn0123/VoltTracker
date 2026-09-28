package com.volttracker.obdpoc.ui.diag

import com.volttracker.obdpoc.ui.components.PillTone
import com.volttracker.obdpoc.ui.drive.DriveUiState
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

/**
 * Whether a code is confined to the gas engine (range extender) — fuel, air, ignition and emissions
 * — so it can't affect driving on electricity.
 */
fun DtcCode.engineOnly(): Boolean =
    category == ENGINE_CATEGORY ||
        (code.length > 2 && code.startsWith("P0") && code[2] in '0'..'4')

private const val ENGINE_CATEGORY = "1.4L engine"

/** The worst of [codes]' severities, or null for none. */
fun worstSeverity(codes: List<DtcCode>): DtcSeverity? = codes.maxOfOrNull { it.severity }

/**
 * Is it safe to drive? From the worst code, as the classic dashboard words it, plus whether the
 * electric drive is out of it. Null when there are no codes.
 */
fun safeToDrive(codes: List<DtcCode>): HealthLine? {
    val worst = worstSeverity(codes) ?: return null
    val verdict =
        when (worst) {
            DtcSeverity.ALERT -> return HealthLine(
                "Stop safely and have the car checked before driving on.",
                PillTone.BAD,
            )
            DtcSeverity.WARNING -> "Safe to drive — have it serviced soon."
            DtcSeverity.INFO -> "Safe to drive — mention it at your next service."
        }
    val electric =
        when {
            !codes.all { it.engineOnly() } -> null
            codes.size == 1 -> "It doesn't affect the electric drive system."
            codes.size == 2 -> "Neither code affects the electric drive system."
            else -> "None of them affect the electric drive system."
        }
    return HealthLine(listOfNotNull(verdict, electric).joinToString(" "), PillTone.EV)
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
    codes
        ?.firstOrNull { it.status.equals(DtcCode.STATUS_FREEZE_FRAME, ignoreCase = true) }
        ?.let { "Captured with ${it.code}" }
        ?: "None stored"

/** The Live signals row: "78 reporting · 1 Hz", or why there are none. */
fun liveSignalsLine(drive: DriveUiState): String =
    when {
        !drive.connected -> "Not connected"
        drive.signalCount > 0 -> "${drive.signalCount} reporting · 1 Hz"
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
) {
    val reported: Boolean get() = sohPct != null || capacityAh != null || spreadMv != null
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
    )

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
    safeToDrive(diag.codes.orEmpty())?.let { lines += it.text }
    diag.earlierLine()?.let { lines += it }
    if (battery.reported) {
        lines += ""
        lines +=
            "HV battery: " +
            listOfNotNull(
                battery.sohPct?.let { "${it.roundToInt()}% capacity health" },
                battery.capacityAh?.let { String.format(Locale.US, "%.1f Ah of %.0f Ah new", it, PACK_NEW_AH) },
                battery.spreadMv?.let { "cell spread ${it.roundToInt()} mV" },
                battery.weakestLabel()?.let { "weakest cell $it" },
            ).joinToString(", ")
    }
    return lines.joinToString("\n")
}
