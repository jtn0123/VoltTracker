package com.volttracker.obdpoc.ui.car

import com.volttracker.obdpoc.ui.drive.TIRE_PLACARD_PSI
import com.volttracker.obdpoc.ui.drive.TirePressures

/**
 * One body reading group from the SW-CAN broadcasts (OBDLink adapters only; the decodes are
 * not yet confirmed on a car). [atMs] is when it was last fresh; null = never heard this session.
 */
enum class BodyGroup { TIRES, LOCK, DOORS, WINDOWS, CLIMATE, AUX12, OIL, WARNINGS }

/** The car's six openings, in the order the Car tab names them. FL is the driver's door on a US car. */
enum class Opening(
    val label: String,
) {
    DRIVER_DOOR("Driver door"),
    PASSENGER_DOOR("Passenger door"),
    REAR_LEFT_DOOR("Rear left door"),
    REAR_RIGHT_DOOR("Rear right door"),
    HOOD("Hood"),
    HATCH("Hatch"),
}

/**
 * The openings the body module has reported, each open (true) or closed. The car sends each one
 * only when it changes, so some may not have reported yet.
 */
data class Openings(
    val states: Map<Opening, Boolean>,
) {
    /** The names of the reported-open ones ("Driver door", "Hood"), in [Opening] order. */
    val open: List<String> get() = Opening.entries.filter { states[it] == true }.map { it.label }

    /** Every opening has reported. */
    val complete: Boolean get() = states.size == Opening.entries.size

    companion object {
        /** All six reported closed. */
        val allClosed: Openings get() = Openings(Opening.entries.associateWith { false })
    }
}

/**
 * Readings the car sends rarely, remembered between drives (and app restarts) with when they were
 * read: the tires come about once a drive, so a new drive would otherwise start with none.
 */
data class CarMemory(
    val tires: TirePressures? = null,
    val tiresAtMs: Long = 0L,
    val oilLifePct: Int? = null,
    val oilAtMs: Long = 0L,
)

/**
 * Car controls as the host and the engine report them. The host owns every safety check; this only
 * says whether the buttons are worth offering, and why not.
 */
data class CarControlsUi(
    /** The user turned car controls on (Settings opt-in with a PIN). */
    val enabled: Boolean = false,
    /** Too many wrong PINs: every command is refused for a few minutes. */
    val pinLockedOut: Boolean = false,
    /** The engine's gate verdict ("ready", "busy", or the reason it would refuse); null = not reported. */
    val gate: String? = null,
    val gateDetail: String? = null,
    /** The last command's wire name and outcome (confirmed / sent_unconfirmed / failed / refused). */
    val lastCommand: String? = null,
    val lastOutcome: String? = null,
    val lastDetail: String? = null,
)

/**
 * Everything the Car tab adds to the Drive state it shares (tyres, lock, 12 V, cabin): the other
 * body signals, when each group was last heard, the tyre placard and units, and car controls.
 * Pure data — previewable with no service running. Null readings are "not reported / stale".
 */
data class CarUiState(
    val metricUnits: Boolean = false,
    /** Settings → Units & vehicle: the door-jamb placard pressure (cold), in psi. */
    val placardPsi: Double = TIRE_PLACARD_PSI,
    val openings: Openings? = null,
    /**
     * Window openings, 0 (closed) to 100 (open), FL / FR / RL / RR; a window that hasn't reported
     * is null, and the list is null until one has.
     */
    val windowsPct: List<Int?>? = null,
    val acOn: Boolean? = null,
    /** The front blower, 0..100 %. */
    val fanPct: Int? = null,
    /** What the A/C compressor draws, kW. */
    val acKw: Double? = null,
    /** Codes of the dash warning lights on ([dashWarningLabel]); empty = none on, null = not reported. */
    val dashWarnings: List<String>? = null,
    /** Every warning broadcast has reported, so an empty [dashWarnings] covers every light read. */
    val dashWarningsComplete: Boolean = false,
    /** Tyres whose sensor the car flagged not valid ("fl", "fr", "rl", "rr"); their pressure is unknown. */
    val tireSensorsInvalid: List<String> = emptyList(),
    val memory: CarMemory = CarMemory(),
    val remoteStartOn: Boolean? = null,
    val outsideTempC: Double? = null,
    /** When each [BodyGroup] was last fresh (ms); a missing group was never heard this session. */
    val seenAtMs: Map<BodyGroup, Long> = emptyMap(),
    /** The clock "Updated N min ago" is measured against: the newest sample or status. */
    val nowMs: Long = 0L,
    val controls: CarControlsUi = CarControlsUi(),
) {
    companion object {
        /** The demo's parked Volt: locked, all closed, one tyre a little under. */
        val demo: CarUiState
            get() =
                CarUiState(
                    openings = Openings.allClosed,
                    windowsPct = listOf(0, 0, 0, 0),
                    acOn = false,
                    fanPct = 0,
                    dashWarnings = emptyList(),
                    dashWarningsComplete = true,
                    remoteStartOn = false,
                    outsideTempC = 17.8,
                    seenAtMs = BodyGroup.entries.associateWith { DEMO_NOW_MS - 60_000L },
                    nowMs = DEMO_NOW_MS,
                )

        const val DEMO_NOW_MS = 1_777_585_320_000L
    }
}

/** What the Car tab can ask the host to do. Defaults are no-ops (previews, tests). */
data class CarActions(
    /** Ask for a car command by wire name; the host confirms it (PIN) and checks every gate. */
    val onControl: (String) -> Unit = {},
    /** Open the native opt-in (warning + PIN setup) for car controls. */
    val onEnableControls: () -> Unit = {},
    /** Turn car controls off (the host erases the PIN). */
    val onDisableControls: () -> Unit = {},
    /** Listen to the body bus for a minute while the driver opens, locks and moves things. */
    val onBodyTest: () -> Unit = {},
    /** Ask the car's body computer for tire pressures (a short read-only probe session). */
    val onTireTest: () -> Unit = {},
)
