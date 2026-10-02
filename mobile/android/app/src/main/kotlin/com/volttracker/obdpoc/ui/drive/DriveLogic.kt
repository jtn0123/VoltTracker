package com.volttracker.obdpoc.ui.drive

import com.volttracker.obdpoc.ui.components.DASH
import com.volttracker.obdpoc.ui.components.NOT_REPORTED
import com.volttracker.obdpoc.ui.components.PillTone
import com.volttracker.obdpoc.ui.units.VoltUnits
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

/** Time-to-full while charging (mirrors the WebView's `renderLiveCharge`). */
sealed interface ChargeEta {
    /** Estimated time left, in ms. */
    data class Finish(
        val remainingMs: Long,
    ) : ChargeEta

    /** SOC is within a point of the target; the charger is balancing the last cells. */
    data object NearlyFull : ChargeEta

    /** Too slow/uncertain to commit to a number. */
    data object Estimating : ChargeEta
}

/**
 * Geometry of the Arc ring (mockups `drive.js`): a 260° sweep from −130° to +130° (0° = 12
 * o'clock, clockwise). Regen 0…−60 kW takes the first 50°, drive 0…120 kW the remaining 210°.
 * Parked/charging, the same sweep is 0…100 % state of charge.
 */
object ArcGeometry {
    const val START_DEG = -130f
    const val ZERO_DEG = -80f
    const val END_DEG = 130f
    const val MAX_DRIVE_KW = 120.0
    const val MAX_REGEN_KW = 60.0
    const val MAX_RPM = 4800.0

    fun kwToDeg(kw: Double): Float =
        if (kw >= 0) {
            ZERO_DEG + (kw.coerceIn(0.0, MAX_DRIVE_KW) / MAX_DRIVE_KW).toFloat() * (END_DEG - ZERO_DEG)
        } else {
            ZERO_DEG - ((-kw).coerceIn(0.0, MAX_REGEN_KW) / MAX_REGEN_KW).toFloat() * (ZERO_DEG - START_DEG)
        }

    fun socToDeg(percent: Double): Float =
        START_DEG + (percent.coerceIn(0.0, 100.0) / 100.0).toFloat() * (END_DEG - START_DEG)

    /** Degrees of the thin engine ring filled at [rpm]. */
    fun rpmSweep(rpm: Int): Float = (rpm.toDouble().coerceIn(0.0, MAX_RPM) / MAX_RPM).toFloat() * (END_DEG - START_DEG)
}

/** Below this pack power (kW) the car is regenerating. */
private const val REGEN_KW = -0.3

/** The SOC the driver knows: the cluster's figure when the car reports it, else the raw pack SOC. */
val DriveUiState.shownSocPercent: Double get() = displayedSocPercent ?: socPercent

val DriveUiState.regenerating: Boolean get() = phase == DrivePhase.DRIVE && powerKw < REGEN_KW

val DriveUiState.gasDriving: Boolean get() = phase == DrivePhase.DRIVE && mode == DriveMode.GAS

/** The engine started because the battery is spent (vs. cold weather, Hold, or high demand). */
val DriveUiState.atReserve: Boolean
    get() = mode == DriveMode.GAS && ((evRangeMiles ?: 1.0) < 0.5 || shownSocPercent < 1.0)

/**
 * EV + gas range in whole [units], or null unless both are known (a half-known total would
 * under-report). It is the sum of the two rounded figures shown beside it, so "32" and "293"
 * total "325" rather than a separately rounded "324".
 */
fun DriveUiState.totalRangeWhole(units: VoltUnits): Int? {
    val ev = evRangeMiles ?: return null
    val gas = gasRangeMiles ?: return null
    return units.distance(ev).roundToInt() + units.distance(gas).roundToInt()
}

/**
 * Below this distance a drive's efficiency is noise (a quarter-mile coast reads 10+ mi/kWh), so
 * the screens show "—" until the drive has covered it.
 */
const val MIN_EFFICIENCY_MILES = 1.0

/** This drive's mi/kWh once it is long enough to mean something (see [MIN_EFFICIENCY_MILES]). */
val DriveUiState.shownTripMiPerKwh: Double?
    get() = tripMiPerKwh?.takeIf { tripMiles >= MIN_EFFICIENCY_MILES }

/** The ring/power color role for the moment: regen is EV green, gas amber, drive Volt teal. */
enum class PowerRole { DRIVE, REGEN, GAS, IDLE }

val DriveUiState.powerRole: PowerRole
    get() =
        when {
            phase != DrivePhase.DRIVE -> PowerRole.IDLE
            regenerating -> PowerRole.REGEN
            mode == DriveMode.GAS -> PowerRole.GAS
            else -> PowerRole.DRIVE
        }

/** A status line with its meaning (tile sub-lines, cockpit caps). */
data class ToneText(
    val text: String,
    val tone: PillTone,
)

/**
 * The default tyre-pressure placard: 38 psi cold, the 2016–2019 Volt door-jamb figure for the
 * stock 17" tyres. It is an assumption, not a car reading — Settings → Units & vehicle changes it,
 * and every tyre label that depends on it names it. A tyre more than [TIRE_LOW_MARGIN_PSI] under
 * the placard reads "low".
 */
const val TIRE_PLACARD_PSI = 38.0
const val TIRE_LOW_MARGIN_PSI = 4.0

private val TIRE_NAMES = listOf("FL", "FR", "RL", "RR")

fun tireLow(
    psi: Double,
    placardPsi: Double = TIRE_PLACARD_PSI,
): Boolean = psi < placardPsi - TIRE_LOW_MARGIN_PSI

fun tireStatus(
    tires: TirePressures?,
    placardPsi: Double = TIRE_PLACARD_PSI,
): ToneText {
    if (tires == null) return ToneText("Not reported", PillTone.NEUTRAL)
    val low =
        tires.all.indices
            .filter { tireLow(tires.all[it], placardPsi) }
            .map { TIRE_NAMES[it] }
    return when {
        low.isEmpty() -> ToneText("All normal", PillTone.EV)
        low.size == 1 -> ToneText("${low.first()} low", PillTone.WARN)
        else -> ToneText("${low.size} tyres low", PillTone.WARN)
    }
}

/**
 * Plain-language 12 V state. While the car is on or plugged in, its DC-DC converter holds the
 * rail near 14 V, so only a resting (parked, not charging) reading says anything about health.
 */
fun aux12Status(
    volts: Double?,
    phase: DrivePhase,
): ToneText =
    when {
        volts == null -> ToneText("Not reported", PillTone.NEUTRAL)
        phase != DrivePhase.PARKED || volts >= AUX_CHARGING_V -> ToneText("Charging", PillTone.EV)
        volts >= AUX_HEALTHY_V -> ToneText("Healthy", PillTone.EV)
        volts >= AUX_LOW_V -> ToneText("Low", PillTone.WARN)
        else -> ToneText("Weak", PillTone.BAD)
    }

private const val AUX_CHARGING_V = 13.2
private const val AUX_HEALTHY_V = 12.4
private const val AUX_LOW_V = 12.0

/** 2016+ Volt usable pack energy between the buffered SOC limits (WebView `VOLT_USABLE_KWH`). */
private const val VOLT_USABLE_KWH = 14.0
private const val MIN_CHARGE_KW = 0.5
private const val MAX_ETA_MS = 24L * 3_600_000L
private const val NEARLY_FULL_KWH = 0.05
private const val NEARLY_FULL_SOC_GAP = 1.0

/** Usable pack energy (kWh) scaled by state-of-health when the car reports a plausible one. */
fun usableKwh(sohPct: Double?): Double =
    if (sohPct != null && sohPct > 0 && sohPct <= 100) VOLT_USABLE_KWH * sohPct / 100 else VOLT_USABLE_KWH

/**
 * Time to [targetSoc] at the current charger power — the same arithmetic and gates as the
 * WebView Charge card: usable energy scaled by state-of-health, no estimate under a real
 * Level-1 draw, "nearly full" within a point, "estimating" past a day.
 */
fun chargeEta(
    socPercent: Double?,
    chargerKw: Double?,
    sohPct: Double?,
    targetSoc: Double = 100.0,
): ChargeEta? {
    if (socPercent == null || chargerKw == null || chargerKw < MIN_CHARGE_KW) return null
    if (socPercent < 0 || socPercent >= targetSoc) return null
    val usable = usableKwh(sohPct)
    val remainingKwh = (usable * (targetSoc - socPercent) / 100).coerceAtLeast(0.0)
    if (remainingKwh < NEARLY_FULL_KWH || targetSoc - socPercent <= NEARLY_FULL_SOC_GAP) return ChargeEta.NearlyFull
    val ms = (remainingKwh / chargerKw * 3_600_000).toLong()
    return if (ms > MAX_ETA_MS) ChargeEta.Estimating else ChargeEta.Finish(ms)
}

/** One duration style app-wide: "47 min", "1 hr 5 min", "2 hr"; a dash when unknown. */
fun durationLabel(ms: Long?): String {
    if (ms == null || ms <= 0) return DASH
    val minutes = (ms / 60_000.0).roundToInt()
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "$minutes min"
        rest == 0 -> "$hours hr"
        else -> "$hours hr $rest min"
    }
}

/**
 * Wall-clock time [offsetMs] after [atMs]: "11:08 PM", or "23:08" when the phone uses a 24-hour
 * clock ([h24]). [short] drops the AM/PM for tight tile lines.
 */
fun clockLabel(
    atMs: Long,
    offsetMs: Long = 0L,
    zone: TimeZone = TimeZone.getDefault(),
    short: Boolean = false,
    h24: Boolean = false,
): String {
    // SimpleDateFormat, not java.time: minSdk 23 without core-library desugaring.
    val pattern =
        when {
            h24 -> "H:mm"
            short -> "h:mm"
            else -> "h:mm a"
        }
    val format = SimpleDateFormat(pattern, Locale.US)
    format.timeZone = zone
    return format.format(Date(atMs + offsetMs))
}

/**
 * The spread across the pack's cell groups in plain words: "Cells balanced (19 mV)" within the
 * Health "watch" band, else "Cells 62 mV apart".
 */
fun cellBalanceText(spreadMv: Double): String {
    val mv = spreadMv.roundToInt()
    return if (spreadMv < CELL_WATCH_MV) "Cells balanced ($mv mV)" else "Cells $mv mV apart"
}

/** Cell spread at which the weakest group is called out (matches the Health "watch" band). */
const val CELL_WATCH_MV = 50.0

/**
 * The charging level the car reports (`AC_1` / `AC_2`), else one read off the AC supply
 * voltage (≥ 180 V is a 240 V Level-2 circuit), else null — never a guess from power alone.
 */
fun chargeLevelLabel(
    reported: String?,
    acVolts: Double?,
): String? =
    when {
        reported == "AC_2" -> "L2"
        reported == "AC_1" -> "L1"
        acVolts == null || acVolts <= 0 -> null
        acVolts >= L2_MIN_AC_V -> "L2"
        else -> "L1"
    }

private const val L2_MIN_AC_V = 180.0

/** One decimal: "18.4". */
fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)

/** TalkBack's reading of the cockpit's power strip: where power is now, its peak and its deepest regen. */
fun powerTraceDescription(trace: List<Float>): String {
    val now = trace.lastOrNull() ?: return "Pack power over the last minute: no readings yet"
    val current = if (now < 0f) "now ${(-now).roundToInt()} kilowatts regen" else "now ${now.roundToInt()} kilowatts"
    val parts = mutableListOf(current)
    val peak = trace.max()
    val deepest = trace.min()
    if (peak >= TRACE_NOTABLE_KW) parts += "peak ${peak.roundToInt()} kilowatts"
    if (deepest <= -TRACE_NOTABLE_KW) parts += "regen up to ${(-deepest).roundToInt()} kilowatts"
    return "Pack power over the last minute: ${parts.joinToString(", ")}"
}

private const val TRACE_NOTABLE_KW = 1f

/** TalkBack's reading of the cockpit's cell histogram: the group count, the voltage span and the lowest group. */
fun cellsDescription(state: DriveUiState): String {
    val count = state.cellVoltages.count { it != null }
    val span =
        if (state.minCellVolts != null && state.maxCellVolts != null) {
            String.format(Locale.US, " from %.3f to %.3f volts", state.minCellVolts, state.maxCellVolts)
        } else {
            ""
        }
    val lowest = state.minCellNumber?.let { ", lowest is group $it" }.orEmpty()
    return "$count cell groups$span$lowest"
}

/** "Full by " or "80% by " ahead of the finish time, for the charge limit in Settings. */
fun etaLead(targetPct: Int): String = if (targetPct >= FULL_PCT) "Full by " else "$targetPct% by "

/** "Estimating time to full…", or to the charge limit when one is set below 100 %. */
fun estimatingText(targetPct: Int): String =
    if (targetPct >= FULL_PCT) "Estimating time to full…" else "Estimating time to $targetPct%…"

const val NEARLY_FULL_TEXT = "Topping off — nearly there"

private const val FULL_PCT = 100

/**
 * What TalkBack reads for the ring's centre, in words rather than the glyphs on screen: the
 * speed and power while driving, the battery and range parked, the battery and finish time
 * while charging.
 */
fun gaugeDescription(
    state: DriveUiState,
    h24: Boolean = false,
): String {
    val units = state.units
    if (!state.connected) return "Battery $NOT_REPORTED. Connect to see your Volt live"
    val battery = "Battery ${state.shownSocPercent.toInt()} percent"
    return when (state.phase) {
        DrivePhase.DRIVE -> {
            val perHour = if (units.metric) "kilometres per hour" else "miles per hour"
            val speed = "${units.speed(state.speedMph.toDouble())} $perHour"
            val kw = oneDecimal(abs(state.powerKw))
            val power = if (state.regenerating) "$kw kilowatts regen" else "$kw kilowatts power"
            val engine = if (state.mode == DriveMode.GAS && state.rpm > 0) ", engine ${state.rpm} rpm" else ""
            "$speed, $power$engine"
        }
        DrivePhase.PARKED -> {
            val range =
                state.evRangeMiles?.let { "${units.distanceText(it)} electric range" } ?: "electric range $NOT_REPORTED"
            "$battery, $range"
        }
        DrivePhase.CHARGING -> {
            val eta =
                when (val e = state.chargeEta) {
                    is ChargeEta.Finish ->
                        etaLead(state.chargeTargetPct) + clockLabel(state.sampleAtMs, e.remainingMs, h24 = h24) +
                            ", ${durationLabel(e.remainingMs)}"
                    ChargeEta.NearlyFull -> NEARLY_FULL_TEXT
                    ChargeEta.Estimating, null -> estimatingText(state.chargeTargetPct)
                }
            "$battery, $eta, charging at ${oneDecimal(state.chargeKw)} kilowatts"
        }
    }
}

/** A real PRNDL position (the store reports a placeholder before the car does). */
val DriveUiState.gearKnown: Boolean get() = gear.length == 1 && gear in GEARS

private const val GEARS = "PRNDL"
