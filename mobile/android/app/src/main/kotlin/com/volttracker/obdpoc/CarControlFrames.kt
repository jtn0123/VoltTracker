package com.volttracker.obdpoc

import java.util.Locale

/** Which physical bus / identifier width a [ControlFrame] goes out on. */
enum class ControlBus(
    /** OBDLink `STP` preset: raw ISO 11898, variable DLC, no ISO-TP framing. */
    val stnProtocol: String,
    val extendedId: Boolean,
) {
    /** Single-wire CAN (GMLAN, OBD pin 1, 33.3 kbit/s), 11-bit identifier. */
    SWCAN_11BIT("61", false),

    /** Single-wire CAN, 29-bit identifier. */
    SWCAN_29BIT("62", true),

    /** High-speed CAN (OBD pins 6/14, 500 kbit/s), 11-bit identifier. */
    HSCAN_11BIT("31", false),
}

/** One exact CAN frame the car-controls path may transmit. Equality is by value. */
data class ControlFrame(
    val bus: ControlBus,
    val id: Int,
    val data: List<Int>,
) {
    /** The OBDLink `STPX` command that transmits exactly this frame without waiting for a reply. */
    fun toStpx(): String {
        val header =
            if (bus.extendedId) {
                "%08X".format(Locale.US, id)
            } else {
                "%03X".format(Locale.US, id)
            }
        val payload = data.joinToString("") { "%02X".format(Locale.US, it) }
        return "STPX h:$header, d:$payload, r:0"
    }
}

/** A step of a [CarCommand] sequence. */
sealed class ControlStep {
    /** Transmit one allowlisted frame. */
    class Send(
        val frame: ControlFrame,
    ) : ControlStep()

    /** Wait; when [listen] is true the wait is spent monitoring SW-CAN for the readback. */
    class Pause(
        val ms: Long,
        val listen: Boolean = false,
    ) : ControlStep()

    /** OBDLink `STCSWM 2`: SW-CAN transceiver to high-voltage wakeup for the next frame. */
    object HighVoltageWakeup : ControlStep()

    /** OBDLink `STCSWM 3`: SW-CAN transceiver back to normal (ACKing) mode. */
    object NormalTransceiver : ControlStep()
}

/** What the car should broadcast afterwards if the command landed. */
enum class ControlReadback {
    LOCKED,
    UNLOCKED,
    REMOTE_START_ON,
    REMOTE_START_OFF,
    WINDOWS_OPENING,
    WINDOWS_CLOSING,

    /** Horn / lights have no broadcast we can read: always "sent, not confirmed". */
    NONE,
}

/**
 * The user-facing car commands. Each is a fixed sequence of [ControlStep]s built only from
 * [CarControlFrames]; nothing is parameterized by user input except which command runs.
 */
enum class CarCommand(
    val wireName: String,
    val label: String,
    val readback: ControlReadback,
) {
    LOCK("lock", "Lock the doors", ControlReadback.LOCKED),
    UNLOCK("unlock", "Unlock the doors", ControlReadback.UNLOCKED),
    FLASH_LIGHTS("flash", "Flash the lights", ControlReadback.NONE),
    LOCATE("locate", "Sound the horn and flash the lights", ControlReadback.NONE),
    REMOTE_START("remote_start", "Remote start (cabin climate)", ControlReadback.REMOTE_START_ON),
    REMOTE_STOP("remote_stop", "Stop the remote start", ControlReadback.REMOTE_START_OFF),
    WINDOWS_DOWN("windows_down", "Open all four windows", ControlReadback.WINDOWS_OPENING),
    WINDOWS_UP("windows_up", "Close all four windows", ControlReadback.WINDOWS_CLOSING),
    ;

    val steps: List<ControlStep> get() = CarControlFrames.sequenceFor(this)

    companion object {
        @JvmStatic
        fun fromWireName(name: String?): CarCommand? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * THE allowlist: every CAN frame VoltTracker is able to transmit to the car, and the only place
 * frame bytes are written down. The car-controls transmit path (`engine/CarControlRunner`) can only
 * render `STPX` commands from [ControlFrame]s that are members of [ALLOWED]; anything else throws.
 * `CarControlFramesTest` pins the exact set.
 *
 * Every frame and every delay is ported from the OVMS `vehicle_voltampera` module (MY2017-tested),
 * openvehicles/Open-Vehicle-Monitoring-System-3 @ b092263, file
 * `vehicle/OVMS.V3/components/vehicle_voltampera/src/vehicle_voltampera.cpp` (`vva.cpp` below) and
 * `va_ac_preheat.cpp`. NONE of it has been sent from VoltTracker to a real car yet.
 *
 * Deliberate differences from OVMS, all in the direction of sending less:
 * - OVMS `CommandWakeup` (vva.cpp L3227-3297) ends with `FlashLights(Interior_lamp)`, an HS-CAN
 *   `$AE` device control to the BCM. OVMS itself documents (vva.cpp L2709-2715) that the telematics
 *   frame only needs the SW-CAN bus awake, so the SW-CAN-only commands here send just the SW-CAN part
 *   of the wakeup. The window command, whose OVMS notes say the lamp tail IS required (vva.cpp
 *   L2521-2535), keeps it.
 * - The optional "extended wakeup" (`xva/extended_wakeup`, default off) is not ported.
 * - The 15 s window keep-awake ticker (vva.cpp L2958-2981) is not ported: no periodic or background
 *   transmission exists at all.
 * - Engine override, trunk release, charge-limit/charge-mode/charge-current and clock writes are
 *   out of scope and have no frames here.
 */
object CarControlFrames {
    /** GMLAN Telematics_Contol_LS (PID 0x127), vva.cpp L2693. */
    const val TELEMATICS_ID = 0x1024E097

    /** BCM diagnostic node on HS-CAN (`VA_BCM`, vva.cpp L92). */
    const val BCM_ID = 0x241

    // ---- SW-CAN wakeup (CommandWakeup, vva.cpp L3227-3244) ----

    /** vva.cpp L3237: 0x100, DLC 0, sent in high-voltage wakeup mode. */
    @JvmField val WAKEUP = ControlFrame(ControlBus.SWCAN_11BIT, 0x100, emptyList())

    /** vva.cpp L3244: BCM wake, 0x621 `00 FF FF FF FF FF 00 00`. */
    @JvmField val WAKEUP_BCM =
        ControlFrame(ControlBus.SWCAN_11BIT, 0x621, listOf(0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x00))

    // ---- OnStar telematics emulation on 0x1024E097, 3 bytes (vva.cpp L2693-2703) ----

    /** CommandLock, vva.cpp L3325: lock request 1. */
    @JvmField val TELEMATICS_LOCK = telematics(0x00, 0x01)

    /** CommandUnlock, vva.cpp L3362: lock request 3 (unlock). */
    @JvmField val TELEMATICS_UNLOCK = telematics(0x00, 0x03)

    /** vva.cpp L3327 / L3364 / L2727: visual alert (flash lights). */
    @JvmField val TELEMATICS_FLASH = telematics(0x0C, 0x00)

    /** vva.cpp L2727: horn (0x30) | lights (0x0C), the OnStar "vehicle locate". */
    @JvmField val TELEMATICS_LOCATE = telematics(0x3C, 0x00)

    /** va_ac_preheat.cpp L293: remote start request 2 (on) with lock request 1. */
    @JvmField val TELEMATICS_REMOTE_START = telematics(0x80, 0x01)

    /** va_ac_preheat.cpp L327: remote start request 1 (off) with lock request 1. */
    @JvmField val TELEMATICS_REMOTE_STOP = telematics(0x40, 0x01)

    /** vva.cpp L3329 / L2736 / va_ac_preheat.cpp L295: every telematics request ends with a release. */
    @JvmField val TELEMATICS_RELEASE = telematics(0x00, 0x00)

    // ---- BCM $AE device control on HS-CAN 0x241 (vva.cpp L2290-2306, L3384-3416, L2481-2556) ----

    /** vva.cpp L3533 / L2304: TesterPresent `01 3E` zero-padded. */
    @JvmField val BCM_TESTER_PRESENT = bcm(0x01, 0x3E, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

    /** vva.cpp L3411: interior lamp on. */
    @JvmField val BCM_INTERIOR_LAMP_ON = bcm(0x07, 0xAE, 0x08, 0x01, 0x7F, 0xFF, 0x00, 0x00)

    /** vva.cpp L3413: interior lamp off (releases the override). */
    @JvmField val BCM_INTERIOR_LAMP_OFF = bcm(0x07, 0xAE, 0x08, 0x01, 0x00, 0x00, 0x00, 0x00)

    /** vva.cpp L2510/L2543: CPID 0x3B, all four windows, 0x02 = up/close. */
    @JvmField val BCM_WINDOWS_UP = bcm(0x07, 0xAE, 0x3B, 0xFF, 0x02, 0x02, 0x02, 0x02)

    /** vva.cpp L2510/L2543: CPID 0x3B, all four windows, 0x01 = down/open. */
    @JvmField val BCM_WINDOWS_DOWN = bcm(0x07, 0xAE, 0x3B, 0xFF, 0x01, 0x01, 0x01, 0x01)

    /** Every frame the app can ever transmit. */
    @JvmField val ALLOWED: Set<ControlFrame> =
        setOf(
            WAKEUP,
            WAKEUP_BCM,
            TELEMATICS_LOCK,
            TELEMATICS_UNLOCK,
            TELEMATICS_FLASH,
            TELEMATICS_LOCATE,
            TELEMATICS_REMOTE_START,
            TELEMATICS_REMOTE_STOP,
            TELEMATICS_RELEASE,
            BCM_TESTER_PRESENT,
            BCM_INTERIOR_LAMP_ON,
            BCM_INTERIOR_LAMP_OFF,
            BCM_WINDOWS_UP,
            BCM_WINDOWS_DOWN,
        )

    /** The exact `STPX` strings for [ALLOWED]; the transmit path checks membership before sending. */
    @JvmField val ALLOWED_STPX: Set<String> = ALLOWED.map { it.toStpx() }.toSet()

    /** True only for an `STPX` command that transmits one allowlisted frame. */
    @JvmStatic
    fun isAllowedTransmit(command: String): Boolean = command in ALLOWED_STPX

    /**
     * Renders the `STPX` for [frame], refusing anything outside [ALLOWED]. This is the single
     * choke point between a command sequence and the adapter.
     */
    @JvmStatic
    fun stpxFor(frame: ControlFrame): String {
        require(frame in ALLOWED) { "Frame is not on the car-controls allowlist: $frame" }
        return frame.toStpx()
    }

    // ---- Sequences ----

    /** OVMS WAKEUP_DELAY_1 (vva.cpp L3222, L3241): between the 0x100 wake and the BCM wake. */
    const val WAKEUP_SETTLE_MS = 220L

    /** vva.cpp L3324 / L3361 / L2733 / va_ac_preheat.cpp L489: after the wakeup, before commanding. */
    const val AFTER_WAKEUP_MS = 720L

    /** vva.cpp L3326 / L3363: between the lock request and the visual confirmation. */
    const val LOCK_TO_FLASH_MS = 180L

    /** vva.cpp L3328 / L3365 / L2735: hold before the release frame. */
    const val HOLD_BEFORE_RELEASE_MS = 1_250L

    /** va_ac_preheat.cpp L294 / L328: remote start hold before release. */
    const val REMOTE_START_HOLD_MS = 360L

    /** vva.cpp L3388: CommandLights waits after its TesterPresent. */
    const val LIGHTS_AFTER_TESTER_PRESENT_MS = 200L

    /** vehicle_voltampera.h L88: FlashLights default interval. */
    const val LAMP_FLASH_MS = 500L

    /** vva.cpp L1834 / L2541: VA_CHGSEQ_WAKE_SECS settle before the window command. */
    const val WINDOW_WAKE_SETTLE_MS = 2_000L

    /** vva.cpp L2305: GmlanDeviceControl waits after its TesterPresent. */
    const val DEVICE_CONTROL_AFTER_TESTER_PRESENT_MS = 50L

    /** vva.cpp L57 VA_WINDOW_CMD_REPEATS: extra window sends, 1 s apart (vva.cpp L2951-2956). */
    const val WINDOW_REPEATS = 4
    const val WINDOW_REPEAT_INTERVAL_MS = 1_000L

    @JvmStatic
    fun sequenceFor(command: CarCommand): List<ControlStep> =
        when (command) {
            CarCommand.LOCK -> swcanWakeup() + lockLike(TELEMATICS_LOCK)
            CarCommand.UNLOCK -> swcanWakeup() + lockLike(TELEMATICS_UNLOCK)
            CarCommand.FLASH_LIGHTS -> swcanWakeup() + alert(TELEMATICS_FLASH)
            CarCommand.LOCATE -> swcanWakeup() + alert(TELEMATICS_LOCATE)
            // CommandClimateControl(true) wakes first (va_ac_preheat.cpp L488-489); stopping does not.
            CarCommand.REMOTE_START -> swcanWakeup() + remoteStart(TELEMATICS_REMOTE_START)
            CarCommand.REMOTE_STOP -> remoteStart(TELEMATICS_REMOTE_STOP)
            CarCommand.WINDOWS_DOWN -> windows(BCM_WINDOWS_DOWN)
            CarCommand.WINDOWS_UP -> windows(BCM_WINDOWS_UP)
        }

    private fun swcanWakeup(): List<ControlStep> =
        listOf(
            ControlStep.HighVoltageWakeup,
            ControlStep.Send(WAKEUP),
            // CommandWakeupComplete (vva.cpp L3209-3220) returns the transceiver to normal once sent.
            ControlStep.NormalTransceiver,
            ControlStep.Pause(WAKEUP_SETTLE_MS),
            ControlStep.Send(WAKEUP_BCM),
            ControlStep.Pause(AFTER_WAKEUP_MS),
        )

    private fun lockLike(request: ControlFrame): List<ControlStep> =
        listOf(
            ControlStep.Send(request),
            ControlStep.Pause(LOCK_TO_FLASH_MS),
            ControlStep.Send(TELEMATICS_FLASH),
            ControlStep.Pause(HOLD_BEFORE_RELEASE_MS, listen = true),
            ControlStep.Send(TELEMATICS_RELEASE),
        )

    private fun alert(request: ControlFrame): List<ControlStep> =
        listOf(
            ControlStep.Send(request),
            ControlStep.Pause(HOLD_BEFORE_RELEASE_MS, listen = true),
            ControlStep.Send(TELEMATICS_RELEASE),
        )

    private fun remoteStart(request: ControlFrame): List<ControlStep> =
        listOf(
            ControlStep.Send(request),
            ControlStep.Pause(REMOTE_START_HOLD_MS),
            ControlStep.Send(TELEMATICS_RELEASE),
        )

    /** CommandWindows (vva.cpp L2486-2575) with the full CommandWakeup it requires. */
    private fun windows(request: ControlFrame): List<ControlStep> {
        val steps = swcanWakeup().dropLast(1).toMutableList()
        // FlashLights(Interior_lamp), vva.cpp L3294 → L3467-3476 → CommandLights L3384-3416.
        steps += ControlStep.Send(BCM_TESTER_PRESENT)
        steps += ControlStep.Pause(LIGHTS_AFTER_TESTER_PRESENT_MS)
        steps += ControlStep.Send(BCM_INTERIOR_LAMP_ON)
        steps += ControlStep.Pause(LAMP_FLASH_MS)
        steps += ControlStep.Send(BCM_TESTER_PRESENT)
        steps += ControlStep.Pause(LIGHTS_AFTER_TESTER_PRESENT_MS)
        steps += ControlStep.Send(BCM_INTERIOR_LAMP_OFF)
        steps += ControlStep.Pause(WINDOW_WAKE_SETTLE_MS)
        // GmlanDeviceControl (vva.cpp L2298-2306): TesterPresent, 50 ms, the $AE request.
        steps += ControlStep.Send(BCM_TESTER_PRESENT)
        steps += ControlStep.Pause(DEVICE_CONTROL_AFTER_TESTER_PRESENT_MS)
        steps += ControlStep.Send(request)
        // Driver's express window needs the request sustained (vva.cpp L2559-2567).
        repeat(WINDOW_REPEATS) {
            steps += ControlStep.Pause(WINDOW_REPEAT_INTERVAL_MS)
            steps += ControlStep.Send(request)
        }
        return steps
    }

    private fun telematics(
        byte0: Int,
        byte1: Int,
    ): ControlFrame = ControlFrame(ControlBus.SWCAN_29BIT, TELEMATICS_ID, listOf(byte0, byte1, 0xFF))

    private fun bcm(vararg bytes: Int): ControlFrame = ControlFrame(ControlBus.HSCAN_11BIT, BCM_ID, bytes.toList())
}
