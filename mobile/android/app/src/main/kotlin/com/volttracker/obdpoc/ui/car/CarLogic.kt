package com.volttracker.obdpoc.ui.car

import com.volttracker.obdpoc.GuidedTestStatus
import com.volttracker.obdpoc.ui.components.DASH
import com.volttracker.obdpoc.ui.components.NOT_REPORTED
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

/** "Odometer 59,448 mi" once the car has reported it; null before. */
fun odometerLine(drive: DriveUiState): String? = drive.odometerMiles?.let { "Odometer ${drive.units.odometerText(it)}" }

/** "Updated just now" / "Updated 4 min ago" / "Updated 2 hr ago" from the newest body reading. */
fun CarUiState.updatedLabel(): String? {
    val newest = seenAtMs.values.maxOrNull() ?: return null
    return "Updated ${ago(nowMs - newest)}"
}

/**
 * Why a body reading is missing. Heard and since gone stale: how long ago. Never heard, while no
 * body frame at all has been: "Needs OBDLink adapter", since the SW-CAN body bus needs one. Never
 * heard while other body frames were: the bus works and the car just hasn't sent this one. Tyre
 * sensors only transmit once the wheels roll; doors, locks and windows only when they change.
 * Never heard while the car isn't [connected]: nothing is wrong yet, it just isn't linked.
 */
fun CarUiState.missingLine(
    group: BodyGroup,
    connected: Boolean = true,
): String {
    val at = seenAtMs[group]
    return when {
        at != null -> "No reading for ${ago(nowMs - at).removeSuffix(" ago")}"
        !connected -> SHOWS_WHEN_CONNECTED
        group == BodyGroup.AUX12 -> NOT_REPORTED
        seenAtMs.isEmpty() -> NEEDS_OBDLINK
        group == BodyGroup.TIRES -> TIRES_AFTER_DRIVE
        group == BodyGroup.WINDOWS -> SENT_ON_MOVE
        group in SENT_STEADILY -> NOT_SENT_YET
        else -> SENT_ON_CHANGE
    }
}

/** Groups the car broadcasts every few seconds, rather than only when something changes. */
private val SENT_STEADILY = setOf(BodyGroup.CLIMATE, BodyGroup.OIL, BodyGroup.WARNINGS)

/** "As of 5 min ago" once a held reading is older than a broadcast stays fresh; null while fresh. */
private fun CarUiState.asOfLine(group: BodyGroup): String? {
    val age = nowMs - (seenAtMs[group] ?: return null)
    return if (age > FRESH_MS) "As of ${ago(age)}" else null
}

/** The status pill over the car: anything open or low first, else the lock and closures. */
fun carHeadline(
    drive: DriveUiState,
    car: CarUiState,
): ToneText {
    val open = car.openings?.open.orEmpty()
    val windowsOpen = car.windowsPct?.count { (it ?: 0) > WINDOW_OPEN_PCT } ?: 0
    val lowTires = drive.tires?.all?.count { tireLow(it, car.placardPsi) } ?: 0
    val warnings = car.dashWarnings.orEmpty()
    val doorsClosed = car.openings?.complete == true && open.isEmpty()
    // "All closed" only when every window reported too; doors alone say just that.
    val closedText =
        when {
            !doorsClosed || windowsOpen > 0 -> null
            car.windowsPct?.all { it != null } != true -> "Doors closed"
            else -> "All closed"
        }
    // The body bus carries the lock command, not the latches' positions: say what was sent.
    val lock =
        when (drive.locked) {
            true -> "Lock sent"
            false -> "Unlock sent"
            null -> null
        }
    return when {
        open.isNotEmpty() -> ToneText(openText(open), PillTone.WARN)
        windowsOpen > 0 -> ToneText(if (windowsOpen == 1) "A window is open" else "Windows open", PillTone.WARN)
        lowTires > 0 -> ToneText("Check tire pressure", PillTone.WARN)
        warnings.size == 1 -> ToneText(dashWarningLabel(warnings.first()), PillTone.WARN)
        warnings.isNotEmpty() -> ToneText("${warnings.size} dash warnings", PillTone.WARN)
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
    if (volts == null) return CarTile(DASH, " V", listOf(if (drive.connected) NOT_REPORTED else SHOWS_WHEN_CONNECTED))
    val soc = drive.aux12SocPercent.takeIf { monitored != null }
    val phase =
        when (drive.phase) {
            // A fresh gear reading means the car answered: it is on, just in Park, and 12.7 V is
            // GM's regulated-voltage mode with a full battery, not the car at rest.
            DrivePhase.PARKED -> if (drive.connected && drive.gear != NO_GEAR_TEXT) "car on" else "resting"
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

/**
 * The climate tile, led by the A/C, which the car sends every few seconds, then the fan, the cabin
 * and outside temperatures, and a running remote start. The A/C reading is the climate control's
 * request for the compressor, so it is only "on" when the compressor is seen drawing power;
 * requested with the compressor idle or not reported, it says so. The compressor drawing power
 * with no request (it also cools the battery) reads as the compressor running, not as the A/C off.
 * Before the A/C has reported, the cabin temperature leads instead.
 */
fun climateTile(
    drive: DriveUiState,
    car: CarUiState,
): CarTile {
    val metric = car.metricUnits
    val cabin = drive.cabinTempF?.let { (it - F_OFFSET) / F_PER_C }
    val ac = car.acOn
    val compressorKw = car.acKw?.takeIf { it > 0 }
    val temps =
        listOfNotNull(
            cabin?.takeIf { ac != null }?.let { "Cabin ${tempText(it, metric)}" },
            car.outsideTempC?.let { "Outside ${tempText(it, metric)}" },
        ).joinToString(" · ").ifEmpty { null }
    val lines =
        listOfNotNull(
            "Compressor idle".takeIf { ac == true && car.acKw?.let { it <= 0 } == true },
            "A/C not requested".takeIf { ac == false && compressorKw != null },
            car.fanPct?.takeIf { ac != null }?.let { if (it > 0) "Fan $it%" else "Fan off" },
            temps,
            if (car.remoteStartOn == true) "Remote start running" else null,
            if (ac == null && cabin == null) car.missingLine(BodyGroup.CLIMATE, drive.connected) else null,
        )
    val tone = if (car.remoteStartOn == true || ac == true) PillTone.EV else PillTone.NEUTRAL
    if (ac == null) {
        return CarTile(cabin?.let { tempValue(it, metric) } ?: DASH, "${tempUnit(metric)} cabin", lines, tone)
    }
    return when {
        compressorKw != null ->
            CarTile(
                if (ac) "A/C on" else "Compressor on",
                " ${oneDecimal(compressorKw)} kW",
                lines,
                tone,
            )
        !ac -> CarTile("A/C off", "", lines, tone)
        else -> CarTile("A/C", " requested", lines, tone)
    }
}

/**
 * The tyres tile: the average against the placard, or the lowest tyre when any is low. The car
 * sends the pressures about once a drive, so until it does this drive the last ones read show,
 * with when they were read, and without calling them normal: they may have changed since. A
 * sensor the car flags not valid blanks the tile and says which.
 */
fun tiresTile(
    drive: DriveUiState,
    car: CarUiState,
): CarTile {
    val metric = car.metricUnits
    val unit = pressureUnit(metric)
    val placard = "${pressureValue(car.placardPsi, metric)} $unit"
    if (car.tireSensorsInvalid.isNotEmpty()) {
        return CarTile(DASH, " $unit", listOf(tireFaultLine(car.tireSensorsInvalid)))
    }
    val remembered = car.memory.tires?.takeIf { drive.tires == null }
    val tires =
        drive.tires ?: remembered
            ?: return CarTile(DASH, " $unit", listOf(car.missingLine(BodyGroup.TIRES, drive.connected)))
    val low = tires.all.indices.filter { tireLow(tires.all[it], car.placardPsi) }
    val readAt = if (remembered != null) "Read ${ago(car.nowMs - car.memory.tiresAtMs)}" else car.tiresReadLine()
    if (low.isEmpty()) {
        // An earlier drive's pressures are not a verdict on today's tyres.
        val verdict = if (remembered == null) " · all normal" else ""
        return CarTile(
            value = pressureValue(tires.all.average(), metric),
            unit = " $unit avg",
            lines = listOfNotNull("Placard $placard$verdict", readAt),
            tone = if (remembered == null) PillTone.EV else PillTone.NEUTRAL,
        )
    }
    val worst = low.minBy { tires.all[it] }
    val under = pressureValue(car.placardPsi - tires.all[worst], metric)
    val more = if (low.size > 1) " · ${low.size} low" else ""
    return CarTile(
        value = pressureValue(tires.all[worst], metric),
        unit = " $unit ${TIRE_NAMES[worst]}",
        lines = listOfNotNull("$under $unit below placard (${pressureValue(car.placardPsi, metric)})$more", readAt),
        tone = PillTone.WARN,
        warnValue = true,
    )
}

/**
 * "Front left: no valid reading" / "Front left, rear right: no valid reading". The car's flag for a
 * tyre's reading is read as not valid; GM's signal list names the flag but not which way round it
 * is, so this says what the reading is, not that the sensor has failed.
 */
fun tireFaultLine(codes: List<String>): String {
    val names = codes.mapNotNull { TIRE_CODE_NAMES[it] }.ifEmpty { listOf("A tire") }
    val joined = names.mapIndexed { i, name -> if (i == 0) name.replaceFirstChar { it.uppercase() } else name }
    return joined.joinToString(", ") + ": no valid reading"
}

private val TIRE_CODE_NAMES =
    mapOf("fl" to "front left", "fr" to "front right", "rl" to "rear left", "rr" to "rear right")

/**
 * "Read 18 min ago" once the pressures are older than a broadcast stays fresh. The car sends them
 * about once a drive, so they hold until the next session instead of clearing.
 */
private fun CarUiState.tiresReadLine(): String? {
    val age = nowMs - (seenAtMs[BodyGroup.TIRES] ?: return null)
    return if (age > FRESH_MS) "Read ${ago(age)}" else null
}

/** Matches the Live store's broadcast staleness: younger readings read as current. */
private const val FRESH_MS = 120_000L

/**
 * The oil-life tile: what the car's oil-life monitor has left, from the live reading or, between
 * drives, the last one read. Amber from [OIL_CHANGE_SOON_PCT] down.
 */
fun oilTile(
    drive: DriveUiState,
    car: CarUiState,
): CarTile {
    val remembered = car.memory.oilLifePct?.takeIf { drive.oilLifePct == null }
    val pct =
        drive.oilLifePct ?: remembered
            ?: return CarTile(DASH, "%", listOf(car.missingLine(BodyGroup.OIL, drive.connected)))
    val low = pct <= OIL_CHANGE_SOON_PCT
    return CarTile(
        value = pct.toString(),
        unit = "%",
        lines =
            listOfNotNull(
                if (low) "Plan an oil change" else "Engine oil left",
                remembered?.let { "Read ${ago(car.nowMs - car.memory.oilAtMs)}" },
            ),
        tone = if (low) PillTone.WARN else PillTone.NEUTRAL,
        meter = (pct / PERCENT).coerceIn(0f, 1f),
        warnValue = low,
    )
}

/** GM's monitor asks for a change near 0 %; the tile turns amber well before. */
const val OIL_CHANGE_SOON_PCT = 15

/**
 * The windows tile: how many are down, then which and how far. The car reports a window when it
 * moves, so one that hasn't moved this session may not have reported yet.
 */
fun windowsTile(
    car: CarUiState,
    connected: Boolean = true,
): CarTile {
    val known =
        car.windowsPct
            ?.withIndex()
            ?.filter { it.value != null }
            .orEmpty()
    if (known.isEmpty()) return CarTile(DASH, lines = listOf(car.missingLine(BodyGroup.WINDOWS, connected)))
    val open = known.filter { (it.value ?: 0) > WINDOW_OPEN_PCT }
    val shut = known.filter { (it.value ?: 0) <= WINDOW_OPEN_PCT }
    val unknown = WINDOW_NAMES.size - known.size
    val value =
        when {
            open.isEmpty() -> "Closed"
            open.size == WINDOW_NAMES.size -> "All open"
            else -> "${open.size} open"
        }
    val shutLine =
        when {
            shut.isEmpty() -> null
            unknown == 0 && open.isEmpty() -> "All four up"
            unknown == 0 -> "Others up"
            else -> shut.joinToString(", ") { WINDOW_NAMES[it.index] } + " up"
        }
    val lines =
        open.map { "${WINDOW_NAMES[it.index]} ${downText(it.value ?: 0)}" } +
            listOfNotNull(
                shutLine,
                if (unknown > 0) "Others not reported" else null,
                car.asOfLine(BodyGroup.WINDOWS),
            )
    return CarTile(
        value = value,
        lines = lines,
        tone = if (open.isEmpty()) PillTone.NEUTRAL else PillTone.WARN,
        warnValue = open.isNotEmpty(),
    )
}

/**
 * The car reports a window in sixths (0 up, 6 fully down), but only up, part way and fully down
 * have been checked against a real window, so the in-between steps aren't shown as percentages.
 */
private fun downText(pct: Int): String = if (pct >= PERCENT_INT) "fully down" else "part way down"

/**
 * The doors tile: the four doors, the hood and the hatch. Each reports only when it opens or
 * closes, so until all six have, the tile says how many it knows.
 */
fun doorsTile(
    car: CarUiState,
    connected: Boolean = true,
): CarTile {
    val openings = car.openings ?: return CarTile(DASH, lines = listOf(car.missingLine(BodyGroup.DOORS, connected)))
    val open = openings.open
    val lines =
        listOfNotNull(
            when {
                open.isNotEmpty() -> openText(open)
                openings.complete -> "Doors, hood and hatch"
                else -> "${openings.states.size} of ${Opening.entries.size} reported"
            },
            car.asOfLine(BodyGroup.DOORS),
        )
    return CarTile(
        value = if (open.isEmpty()) "Closed" else "${open.size} open",
        lines = lines,
        tone = if (open.isEmpty()) PillTone.NEUTRAL else PillTone.WARN,
        warnValue = open.isNotEmpty(),
    )
}

/**
 * The dash-warnings row: the lights the car says are on, "None on", or why it isn't known. "None
 * on" needs every warning broadcast to have reported; before that, "None seen so far".
 */
fun dashWarningsLine(
    car: CarUiState,
    connected: Boolean = true,
): ToneText {
    val codes = car.dashWarnings ?: return ToneText(car.missingLine(BodyGroup.WARNINGS, connected), PillTone.NEUTRAL)
    if (codes.isEmpty()) {
        // Clear from only some of the broadcasts is not an all-clear.
        val text = if (car.dashWarningsComplete) "None on" else "None seen so far"
        val tone = if (car.dashWarningsComplete) PillTone.EV else PillTone.NEUTRAL
        return ToneText(listOfNotNull(text, car.asOfLine(BodyGroup.WARNINGS)).joinToString(" · "), tone)
    }
    return ToneText(codes.joinToString(" · ") { dashWarningLabel(it) }, PillTone.WARN)
}

/** A dash warning's name, from the code the SW-CAN decoder gives it. */
fun dashWarningLabel(code: String): String =
    DASH_WARNING_LABELS[code]
        ?: BULB_LABELS[code.removePrefix(BULB_PREFIX)]?.takeIf { code.startsWith(BULB_PREFIX) }?.let { "$it out" }
        ?: code.replace('_', ' ').replaceFirstChar { it.uppercase() }

private val DASH_WARNING_LABELS =
    mapOf(
        "abs" to "ABS warning",
        "tire_pressure_low" to "Tire pressure low",
        "oil_starvation" to "Oil starvation",
        "brake_fluid_low" to "Brake fluid low",
        "brake_pads" to "Brake pads worn",
        "brake_system" to "Service brake system",
        "oil_hot" to "Engine oil hot",
        "oil_change" to "Change engine oil soon",
        "oil_level_low" to "Oil level low",
        "oil_pressure_low" to "Oil pressure low",
        "reduced_power" to "Reduced engine power",
        "fuel_cap" to "Check fuel cap",
        "engine_hot" to "Engine overheating",
        "power_steering" to "Service power steering",
        "steering_assist_reduced" to "Steering assist reduced",
        "washer_fluid_low" to "Washer fluid low",
    )

private const val BULB_PREFIX = "bulb_"
private val BULB_LABELS =
    mapOf(
        "center_brake" to "Center brake light",
        "front_left_turn" to "Front left turn signal",
        "front_right_turn" to "Front right turn signal",
        "left_brake" to "Left brake light",
        "left_low_beam" to "Left low beam",
        "left_parking" to "Left parking light",
        "license_plate" to "License plate light",
        "rear_left_turn" to "Rear left turn signal",
        "rear_right_turn" to "Rear right turn signal",
        "right_brake" to "Right brake light",
        "right_low_beam" to "Right low beam",
        "right_parking" to "Right parking light",
        "rear_fog" to "Rear fog light",
        "reverse" to "Reverse light",
        "left_daytime" to "Left daytime light",
        "right_daytime" to "Right daytime light",
    )

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
    val days = minutes / MINUTES_PER_DAY
    return when {
        minutes < 1 -> "just now"
        minutes < MINUTES_PER_HOUR -> "$minutes min ago"
        days < 1 -> "${minutes / MINUTES_PER_HOUR} hr ago"
        days == 1 -> "1 day ago"
        else -> "$days days ago"
    }
}

const val GATE_READY = "ready"
const val GATE_BUSY = "busy"

private val TIRE_NAMES = listOf("front left", "front right", "rear left", "rear right")

/** FL, FR, RL, RR, the order [CarUiState.windowsPct] holds them. */
private val WINDOW_NAMES = listOf("Driver", "Passenger", "Rear left", "Rear right")

/** The Car tab's body-test row: what it does, or why it can't run yet. */
fun bodyTestLine(canTest: Boolean): String =
    if (canTest) "Listen 1 min while you open doors, lock and move windows" else "Connect to the car to run it"

/** The Car tab's tire-test row: what it does, or why it can't run yet. */
fun tireTestLine(canTest: Boolean): String =
    if (canTest) "Ask the car for tire pressures (about 30 s)" else "Connect to the car to run it"

const val TIRE_TEST_TITLE = "Tire test"

const val GUIDED_TEST_TITLE = "Guided car test"

/** Under the guided test's status: what running it costs the rest of the app. */
const val GUIDED_TEST_NOTE = "Live data updates between steps while the test runs."

/** The guided test's first step waits for this: the driver can hear the phone. */
const val GUIDED_CONFIRM_LABEL = "I can hear it"

/** The Car tab's guided-test row: what it is, how the last one ended, or why it can't run. */
fun guidedTestLine(
    canTest: Boolean,
    status: GuidedTestStatus,
): String =
    when {
        !canTest -> "Connect to the car to run it"
        status.ended == "Finished" -> "Finished · the results are in the session log"
        status.ended.isNotEmpty() -> status.ended
        else -> "Spoken steps round the car, off and on, then a drive (about 35 min)"
    }

/** "Step 6 of 38", or "Starting" before the first step. */
fun guidedStepLabel(status: GuidedTestStatus): String =
    if (status.step > 0) "Step ${status.step} of ${status.steps}" else "Starting"

private const val NO_GEAR_TEXT = "--"

/** A body reading never heard this session: the SW-CAN body bus needs an OBDLink adapter. */
const val NEEDS_OBDLINK = "Needs OBDLink adapter"
const val TIRES_AFTER_DRIVE = "Shows after a short drive"
const val SHOWS_WHEN_CONNECTED = "Shows when connected"
const val NOT_SENT_YET = "Not sent by the car yet"
const val SENT_ON_CHANGE = "Updates when one opens or locks"
const val SENT_ON_MOVE = "Updates when a window moves"

/** A window more than this far down reads as open (the broadcast rounds a closed window to 0–1). */
private const val WINDOW_OPEN_PCT = 2
private const val F_PER_C = 9.0 / 5.0
private const val F_OFFSET = 32.0
private const val PERCENT = 100f
private const val PERCENT_INT = 100
private const val MINUTE_MS = 60_000L
private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
