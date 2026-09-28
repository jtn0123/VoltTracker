package com.volttracker.obdpoc.ui.drive

import com.volttracker.obdpoc.ui.components.PillTone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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

/** EV + gas range, or null unless both are known — a half-known total would under-report. */
val DriveUiState.totalRangeMiles: Double?
    get() {
        val ev = evRangeMiles ?: return null
        val gas = gasRangeMiles ?: return null
        return ev + gas
    }

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

/** "22 min" under 100 minutes, else "1h 45m"; "--" when unknown. */
fun durationLabel(ms: Long?): String {
    if (ms == null || ms <= 0) return "--"
    val minutes = (ms / 60_000.0).roundToInt()
    return if (minutes < 100) "$minutes min" else "${minutes / 60}h ${"%02d".format(Locale.US, minutes % 60)}m"
}

/** Compact "1h 26m" / "48m" for the charging line. */
fun shortDurationLabel(ms: Long): String {
    val minutes = (ms / 60_000.0).roundToInt()
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}

/** Wall-clock time [offsetMs] after [atMs], e.g. "11:08 PM"; [short] drops the AM/PM for tight tile lines. */
fun clockLabel(
    atMs: Long,
    offsetMs: Long = 0L,
    zone: TimeZone = TimeZone.getDefault(),
    short: Boolean = false,
): String {
    // SimpleDateFormat, not java.time: minSdk 23 without core-library desugaring.
    val format = SimpleDateFormat(if (short) "h:mm" else "h:mm a", Locale.US)
    format.timeZone = zone
    return format.format(Date(atMs + offsetMs))
}

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

/** Whole number with no grouping: "293". */
fun wholeLabel(value: Double?): String = value?.roundToInt()?.toString() ?: "--"

/** "$0.52": [kwh] at the home electricity rate; null when no rate is set (Settings → Costs). */
fun costLabel(
    kwh: Double?,
    ratePerKwh: Double,
): String? = if (kwh == null || ratePerKwh <= 0.0) null else String.format(Locale.US, "$%.2f", kwh * ratePerKwh)

/** One decimal: "18.4". */
fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)
