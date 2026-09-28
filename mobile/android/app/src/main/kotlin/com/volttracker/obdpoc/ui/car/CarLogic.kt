package com.volttracker.obdpoc.ui.car

import com.volttracker.obdpoc.ui.components.PillTone
import com.volttracker.obdpoc.ui.drive.DrivePhase
import com.volttracker.obdpoc.ui.drive.DriveUiState
import com.volttracker.obdpoc.ui.drive.ToneText
import com.volttracker.obdpoc.ui.drive.aux12Status
import com.volttracker.obdpoc.ui.drive.oneDecimal
import com.volttracker.obdpoc.ui.drive.tireLow
import com.volttracker.obdpoc.ui.units.VoltUnits
import java.util.Locale
import kotlin.math.roundToInt

/** A Car tile's figure: the value, its unit, the lines under it, and its meaning. */
data class CarTile(
    val value: String,
    val unit: String = "",
    val lines: List<String> = emptyList(),
    val tone: PillTone = PillTone.NEUTRAL,
    /** 0..1 for a tile with a meter (the 12 V state of charge); null for none. */
    val meter: Float? = null,
    /** The figure itself reads as a warning (a low tyre). */
    val warnValue: Boolean = false,
)

/** A tyre pressure as a whole number in the chosen units (psi, or kPa for metric). */
fun pressureValue(
    psi: Double,
    metric: Boolean,
): String = VoltUnits.of(metric).pressure(psi).toString()

fun pressureUnit(metric: Boolean): String = VoltUnits.of(metric).pressureUnit

/** "70°F" / "21°C" from Celsius. */
fun tempText(
    celsius: Double,
    metric: Boolean,
): String = "${tempValue(celsius, metric)}${tempUnit(metric)}"

fun tempValue(
    celsius: Double,
    metric: Boolean,
): String = (if (metric) celsius else celsius * F_PER_C + F_OFFSET).roundToInt().toString()

fun tempUnit(metric: Boolean): String = VoltUnits.of(metric).tempUnit

/** "Updated just now" / "Updated 4 min ago" / "Updated 2 h ago" from the newest body reading. */
fun CarUiState.updatedLabel(): String? {
    val newest = seenAtMs.values.maxOrNull() ?: return null
    return "Updated ${ago(nowMs - newest)}"
}

/**
 * Why a body reading is missing: never heard this session ("Not reported · OBDLink only", since
 * the SW-CAN body bus needs an OBDLink adapter), or heard and since gone stale.
 */
fun CarUiState.missingLine(group: BodyGroup): String {
    val at = seenAtMs[group] ?: return if (group == BodyGroup.AUX12) NOT_REPORTED else "$NOT_REPORTED · OBDLink only"
    return "No reading for ${ago(nowMs - at).removeSuffix(" ago")}"
}

/** The status pill over the car: anything open or low first, else the lock and closures. */
fun carHeadline(
    drive: DriveUiState,
    car: CarUiState,
): ToneText {
    val open = car.openings?.open.orEmpty()
    val windowsOpen = car.windowsPct?.count { it > WINDOW_OPEN_PCT } ?: 0
    val lowTires = drive.tires?.all?.count { tireLow(it, car.placardPsi) } ?: 0
    val doorsClosed = car.openings != null && open.isEmpty()
    // "All closed" only when the windows reported too; doors alone say just that.
    val closedText =
        when {
            !doorsClosed || windowsOpen > 0 -> null
            car.windowsPct == null -> "Doors closed"
            else -> "All closed"
        }
    val lock =
        when (drive.locked) {
            true -> "Locked"
            false -> "Unlocked"
            null -> null
        }
    return when {
        open.isNotEmpty() -> ToneText(openText(open), PillTone.WARN)
        windowsOpen > 0 -> ToneText(if (windowsOpen == 1) "A window is open" else "Windows open", PillTone.WARN)
        lowTires > 0 -> ToneText("Check tire pressure", PillTone.WARN)
        lock == null && car.openings == null -> ToneText("Lock and doors not reported", PillTone.NEUTRAL)
        else ->
            ToneText(
                listOfNotNull(lock, closedText).joinToString(" · "),
                if (drive.locked == true) PillTone.EV else PillTone.NEUTRAL,
            )
    }
}

/** The 12 V tile: the SW-CAN battery monitor, else the adapter's reading at the OBD port. */
fun aux12Tile(drive: DriveUiState): CarTile {
    val monitored = drive.aux12Volts
    val volts = monitored ?: drive.auxVolts?.takeIf { drive.connected && it > 0 }
    val status = aux12Status(volts, drive.phase)
    if (volts == null) return CarTile("--", " V", listOf(NOT_REPORTED))
    val soc = drive.aux12SocPercent.takeIf { monitored != null }
    val phase =
        when (drive.phase) {
            DrivePhase.PARKED -> "resting"
            DrivePhase.DRIVE -> "car on"
            DrivePhase.CHARGING -> "plugged in"
        }
    val lines =
        listOfNotNull(soc?.let { "$it%" }, phase, status.text.lowercase(Locale.US))
            .distinct()
            .joinToString(" · ")
    return CarTile(
        value = oneDecimal(volts),
        unit = " V",
        lines = listOfNotNull(lines, if (monitored == null) "At the OBD port" else null),
        tone = status.tone,
        meter = soc?.let { (it / PERCENT).coerceIn(0f, 1f) },
    )
}

/** The climate tile: the estimated cabin temperature, outside, the A/C, and a running remote start. */
fun climateTile(
    drive: DriveUiState,
    car: CarUiState,
): CarTile {
    val metric = car.metricUnits
    val cabin = drive.cabinTempF?.let { (it - F_OFFSET) / F_PER_C }
    val outside =
        listOfNotNull(
            car.outsideTempC?.let { "Outside ${tempText(it, metric)}" },
            car.acOn?.let { if (it) "A/C on" else "A/C off" },
        ).joinToString(" · ").ifEmpty { null }
    val lines =
        listOfNotNull(
            outside,
            if (car.remoteStartOn == true) "Remote start running" else null,
            if (cabin == null) car.missingLine(BodyGroup.CLIMATE) else null,
        )
    return CarTile(
        value = cabin?.let { tempValue(it, metric) } ?: "--",
        unit = "${tempUnit(metric)} cabin",
        lines = lines,
        tone = if (car.remoteStartOn == true) PillTone.EV else PillTone.NEUTRAL,
    )
}

/** The tyres tile: the average against the placard, or the lowest tyre when any is low. */
fun tiresTile(
    drive: DriveUiState,
    car: CarUiState,
): CarTile {
    val metric = car.metricUnits
    val unit = pressureUnit(metric)
    val placard = "${pressureValue(car.placardPsi, metric)} $unit"
    val tires = drive.tires ?: return CarTile("--", " $unit", listOf(car.missingLine(BodyGroup.TIRES)))
    val low = tires.all.indices.filter { tireLow(tires.all[it], car.placardPsi) }
    if (low.isEmpty()) {
        return CarTile(
            value = pressureValue(tires.all.average(), metric),
            unit = " $unit avg",
            lines = listOf("Placard $placard · all normal"),
            tone = PillTone.EV,
        )
    }
    val worst = low.minBy { tires.all[it] }
    val under = pressureValue(car.placardPsi - tires.all[worst], metric)
    val more = if (low.size > 1) " · ${low.size} low" else ""
    return CarTile(
        value = pressureValue(tires.all[worst], metric),
        unit = " $unit ${TIRE_NAMES[worst]}",
        lines = listOf("$under $unit below placard (${pressureValue(car.placardPsi, metric)})$more"),
        tone = PillTone.WARN,
        warnValue = true,
    )
}

/** The windows tile, with the doors, hood and hatch under it. */
fun windowsTile(car: CarUiState): CarTile {
    val windows = car.windowsPct
    val openCount = windows?.count { it > WINDOW_OPEN_PCT }
    val value =
        when {
            openCount == null -> "--"
            openCount == 0 -> "Closed"
            openCount == windows.size -> "All open"
            else -> "$openCount open"
        }
    val openings = car.openings
    val doors =
        when {
            openings == null -> null
            openings.open.isEmpty() -> "Doors, hood, hatch closed"
            else -> openText(openings.open)
        }
    val lines =
        listOfNotNull(
            if (openCount == null) car.missingLine(BodyGroup.WINDOWS) else null,
            doors,
        ).ifEmpty { listOf(car.missingLine(BodyGroup.DOORS)) }
    val warn = (openCount ?: 0) > 0 || openings?.open.orEmpty().isNotEmpty()
    return CarTile(value = value, lines = lines.distinct(), tone = if (warn) PillTone.WARN else PillTone.NEUTRAL)
}

/** "Driver door open" / "Driver door, hood open". */
fun openText(open: List<String>): String =
    open
        .mapIndexed { i, name -> if (i == 0) name else name.lowercase(Locale.US) }
        .joinToString(", ") + " open"

/** The car-control buttons, in the order the Car tab shows them. */
enum class CarControl(
    val label: String,
    val wireName: String,
) {
    LOCK("Lock", "lock"),
    UNLOCK("Unlock", "unlock"),
    START("Start", "remote_start"),

    /** There is no horn-only command: "locate" sounds the horn and flashes the lights. */
    HORN("Horn", "locate"),
    LIGHTS("Lights", "flash"),
}

/** The command a button sends: Start stops a remote start that is already running. */
fun CarControl.command(car: CarUiState): String =
    if (this == CarControl.START && car.remoteStartOn == true) "remote_stop" else wireName

fun CarControl.label(car: CarUiState): String =
    if (this == CarControl.START && car.remoteStartOn == true) "Stop" else label

/** The button that shows the car's current state (Lock while locked, Unlock while unlocked, …). */
fun CarControl.isOn(
    drive: DriveUiState,
    car: CarUiState,
): Boolean =
    when (this) {
        CarControl.LOCK -> drive.locked == true
        CarControl.UNLOCK -> drive.locked == false
        CarControl.START -> car.remoteStartOn == true
        else -> false
    }

/**
 * Whether the buttons are worth offering. The host and the engine still check everything again
 * (opt-in, PIN, live session, OBDLink, parked) before a frame is sent; this only avoids offering a
 * button that can only be refused. The demo simulates commands, so it always offers them.
 */
fun CarControlsUi.open(
    connected: Boolean,
    demo: Boolean,
): Boolean = demo || (enabled && connected && !pinLockedOut && gate == GATE_READY)

/** The line under the buttons: why they are off, or that they are ready. */
fun CarControlsUi.statusLine(
    connected: Boolean,
    demo: Boolean,
): ToneText =
    when {
        demo -> ToneText("Demo: commands are simulated. Nothing is sent to a car.", PillTone.NEUTRAL)
        !enabled -> ToneText("Car controls are off. Turn them on to lock, start or find the car.", PillTone.NEUTRAL)
        pinLockedOut -> ToneText("PIN locked after wrong attempts. Try again in a few minutes.", PillTone.WARN)
        !connected || gate == null -> ToneText("Connect to the car to use controls.", PillTone.WARN)
        gate == GATE_READY -> ToneText("Ready. Each command asks you to confirm.", PillTone.EV)
        gate == GATE_BUSY -> ToneText("Sending a command…", PillTone.NEUTRAL)
        else ->
            ToneText(
                gateDetail?.takeIf { it.isNotBlank() } ?: "Controls are not available right now.",
                PillTone.WARN,
            )
    }

/** "Lock the doors: confirmed by the car." for the last command, or null before any. */
fun CarControlsUi.lastResultLine(): ToneText? {
    val command = lastCommand ?: return null
    val outcome = lastOutcome ?: return null
    val label =
        com.volttracker.obdpoc.CarCommand
            .fromWireName(command)
            ?.label ?: command
    val (text, tone) =
        when (outcome) {
            "confirmed" -> "confirmed by the car" to PillTone.EV
            "sent_unconfirmed" -> "sent, not confirmed" to PillTone.NEUTRAL
            "failed" -> "failed" to PillTone.WARN
            "refused" -> "not sent" to PillTone.WARN
            "simulated" -> "simulated in the demo, nothing was sent" to PillTone.NEUTRAL
            else -> outcome to PillTone.NEUTRAL
        }
    val detail = lastDetail?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
    return ToneText("$label: $text.$detail", tone)
}

private fun ago(ms: Long): String {
    val minutes = (ms.coerceAtLeast(0L) / MINUTE_MS).toInt()
    return when {
        minutes < 1 -> "just now"
        minutes < MINUTES_PER_HOUR -> "$minutes min ago"
        else -> "${minutes / MINUTES_PER_HOUR} h ago"
    }
}

const val GATE_READY = "ready"
const val GATE_BUSY = "busy"

private val TIRE_NAMES = listOf("front left", "front right", "rear left", "rear right")
private const val NOT_REPORTED = "Not reported"

/** A window more than this far down reads as open (the broadcast rounds a closed window to 0–1). */
private const val WINDOW_OPEN_PCT = 2
private const val F_PER_C = 9.0 / 5.0
private const val F_OFFSET = 32.0
private const val PERCENT = 100f
private const val MINUTE_MS = 60_000L
private const val MINUTES_PER_HOUR = 60
