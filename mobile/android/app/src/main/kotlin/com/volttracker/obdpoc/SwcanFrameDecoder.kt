package com.volttracker.obdpoc

import java.util.Locale
import kotlin.math.roundToInt

/** One CAN frame as printed by an OBDLink (STN) adapter in `STM` monitor mode. */
class SwcanFrame(
    /** CAN identifier: 11-bit when [extended] is false, 29-bit when true. */
    val id: Int,
    val extended: Boolean,
    /** Data bytes, 0..8 of them, each 0..255. */
    val data: IntArray,
) {
    /** GMLAN 13-bit parameter (arbitration) id: bits 13..25 of a 29-bit identifier. */
    val gmlanPid: Int get() = (id ushr GMLAN_PID_SHIFT) and GMLAN_PID_MASK

    val dlc: Int get() = data.size

    private companion object {
        const val GMLAN_PID_SHIFT = 13
        const val GMLAN_PID_MASK = 0x1FFF
    }
}

/** A value decoded from one SW-CAN broadcast frame, keyed by [field]. */
class SwcanReading(
    val field: SwcanField,
    /** A [Double] for numeric fields, a [String] for state fields, or [INVALID]. */
    val value: Any,
) {
    companion object {
        /**
         * The car sent this field but flagged it not valid (a tyre sensor it has lost, say). It
         * replaces whatever was known, so an old value can't stand in for a reading the car disowned.
         */
        @JvmField val INVALID: Any =
            object {
                override fun toString(): String = "invalid"
            }
    }
}

/**
 * Every value the listen-only SW-CAN (GMLAN, OBD pin 1, 33.3 kbit/s) path can surface.
 *
 * Grouped by [group]; each group gets one `...StaleMs` age in the live sample.
 */
enum class SwcanField(
    val group: SwcanGroup,
) {
    AUX12V_VOLTAGE(SwcanGroup.AUX_12V),
    AUX12V_SOC(SwcanGroup.AUX_12V),
    AUX12V_CURRENT(SwcanGroup.AUX_12V),
    TIRE_FL(SwcanGroup.TIRES),
    TIRE_FR(SwcanGroup.TIRES),
    TIRE_RL(SwcanGroup.TIRES),
    TIRE_RR(SwcanGroup.TIRES),
    LOCK_STATE(SwcanGroup.LOCKS),
    LOCK_SOURCE(SwcanGroup.LOCKS),
    DOOR_FL(SwcanGroup.DOORS),
    DOOR_FR(SwcanGroup.DOORS),
    DOOR_RL(SwcanGroup.DOORS),
    DOOR_RR(SwcanGroup.DOORS),
    HOOD(SwcanGroup.DOORS),
    TRUNK(SwcanGroup.DOORS),
    ALARM(SwcanGroup.ALARM),
    WINDOW_FL(SwcanGroup.WINDOWS),
    WINDOW_FR(SwcanGroup.WINDOWS),
    WINDOW_RL(SwcanGroup.WINDOWS),
    WINDOW_RR(SwcanGroup.WINDOWS),
    CABIN_TEMP(SwcanGroup.CLIMATE),
    BLOWER(SwcanGroup.CLIMATE),
    AC_STATE(SwcanGroup.CLIMATE),
    AC_COMPRESSOR_RPM(SwcanGroup.CLIMATE),
    AC_EVAP_TEMP(SwcanGroup.CLIMATE),
    AC_COMPRESSOR_KW(SwcanGroup.CLIMATE),
    HEATER_CORE_TEMP(SwcanGroup.CLIMATE),
    COOLANT_HEATER_KW(SwcanGroup.CLIMATE),
    REMOTE_START(SwcanGroup.CLIMATE),
    PE_COOLANT_TEMP(SwcanGroup.POWER_ELECTRONICS),
    CHARGE_LIMIT(SwcanGroup.CHARGE_LIMIT),
    CLUSTER_EV_RANGE(SwcanGroup.RANGE),
    FUEL_RANGE(SwcanGroup.RANGE),
    CYCLE_ENERGY_USED(SwcanGroup.DRIVE_CYCLE),
    CYCLE_EV_DISTANCE(SwcanGroup.DRIVE_CYCLE),
    CYCLE_FUEL_DISTANCE(SwcanGroup.DRIVE_CYCLE),
    CYCLE_FUEL_USED(SwcanGroup.DRIVE_CYCLE),
    CYCLE_DRIVING_ENERGY(SwcanGroup.ENERGY),
    CYCLE_CLIMATE_ENERGY(SwcanGroup.ENERGY),
    CYCLE_CONDITIONING_ENERGY(SwcanGroup.ENERGY),
    BATTERY_ENERGY_LEFT(SwcanGroup.ENERGY),
    WHEEL_FL(SwcanGroup.WHEELS),
    WHEEL_FR(SwcanGroup.WHEELS),
    WHEEL_RL(SwcanGroup.WHEELS),
    WHEEL_RR(SwcanGroup.WHEELS),
    TRIP_A(SwcanGroup.TRIPS),
    TRIP_B(SwcanGroup.TRIPS),
    TRANS_OIL_TEMP(SwcanGroup.DRIVETRAIN),
    OIL_LIFE(SwcanGroup.MAINTENANCE),
    POWER_MODE(SwcanGroup.POWER_MODE),

    // Seat heat: how many of a seat's level lamps are lit (0 = off), from the seat-heat control
    // modules' indicator broadcasts, not the button presses.
    SEAT_HEAT_FL(SwcanGroup.SEAT_HEAT),
    SEAT_HEAT_FR(SwcanGroup.SEAT_HEAT),
    SEAT_HEAT_RL(SwcanGroup.SEAT_HEAT),
    SEAT_HEAT_RR(SwcanGroup.SEAT_HEAT),

    // Dash warning lights, one field per broadcast that carries them; each value is the comma-joined
    // codes of the ones lit, "" when none are (see [SwcanReadings] for how they merge).
    WARNINGS_FAST(SwcanGroup.WARNINGS),
    WARNINGS_SLOW(SwcanGroup.WARNINGS),
    WARNINGS_SUPER_SLOW(SwcanGroup.WARNINGS),
    WARNING_WASHER(SwcanGroup.WARNINGS),
    WARNING_BULBS(SwcanGroup.WARNINGS),
}

enum class SwcanGroup {
    AUX_12V,
    TIRES,
    LOCKS,
    DOORS,
    ALARM,
    WINDOWS,
    CLIMATE,
    POWER_ELECTRONICS,
    CHARGE_LIMIT,
    RANGE,
    DRIVE_CYCLE,
    ENERGY,
    WHEELS,
    TRIPS,
    DRIVETRAIN,
    MAINTENANCE,
    WARNINGS,
    POWER_MODE,
    SEAT_HEAT,
}

/**
 * Pure parser + decoder for the gen-2 Volt's single-wire CAN broadcast traffic, as captured by an
 * OBDLink adapter in listen-only monitor mode (`STP 61`, `STCMM 0`, `STM`).
 *
 * Frame layouts and scalings come from GM's own low-speed GMLAN signal list (opendbc
 * `gm_global_a_lowspeed_1818125.dbc`, MIT) where it covers a frame, else from the OVMS
 * `vehicle_voltampera` module (`IncomingFrameCan4`, `IncomingDriveCycleSWCAN`,
 * `ClimateControlIncomingSWCAN`). `docs/swcan-signal-map.csv` says which ones a real capture has
 * matched to the car; the rest are UNCONFIRMED-ON-CAR. Everything is read-only; nothing in this file
 * builds a frame to send.
 */
object SwcanFrameDecoder {
    // ---- Exact 29-bit identifiers (priority + arbitration id + source, as OVMS matches them) ----
    const val ID_DOOR_LOCK = 0x0C414040
    const val ID_BATTERY_12V = 0x10248040
    const val ID_TPMS = 0x103D4040
    const val ID_THEFT_ALARM = 0x10260040
    const val ID_DOOR_FR = 0x0C2F6040
    const val ID_DOOR_RL = 0x0C2F8040
    const val ID_DOOR_RR = 0x0C2FA040
    const val ID_DOOR_FL = 0x0C630040
    const val ID_HOOD = 0x10728040
    const val ID_TRUNK = 0x0C6AA040
    const val ID_AC_COMPRESSOR = 0x102700CB
    const val ID_COOLANT_HEATER = 0x10624099
    const val ID_PE_COOLANT = 0x106340CB
    const val ID_HEATER_CORE = 0x106D4099
    const val ID_CHARGE_LIMIT = 0x1086C0CB
    const val ID_CLIMATE_GENERAL = 0x10734099
    const val ID_CLIMATE_BASIC = 0x10814099
    const val ID_CABIN_TEMP = 0x10440099
    const val ID_REMOTE_START = 0x10390040
    const val ID_WHEEL_SPEED = 0x106B8040
    const val ID_TRIP_ODOMETER = 0x103D6060
    const val ID_ANALOG_SLOW = 0x102E0040
    const val ID_CLIMATE_POWER = 0x102740CB
    const val ID_ENERGY_SPLIT = 0x1042C0CB

    // ---- 13-bit GMLAN parameter ids, matched regardless of priority/source bits ----
    const val PID_ENERGY_STORAGE = 0x0141
    const val PID_EV_RANGE = 0x0176
    const val PID_DRIVE_CYCLE_ENERGY = 0x0223
    const val PID_FUEL_RANGE = 0x0224
    const val PID_DRIVE_CYCLE_DISTANCE = 0x0225
    const val PID_WINDOWS = 0x0325
    const val PID_WARNINGS_FAST = 0x0132
    const val PID_ENGINE_INFO_4 = 0x0168
    const val PID_WASHER_LEVEL = 0x01DE
    const val PID_BULB_OUTAGE = 0x0319
    const val PID_WARNINGS_SLOW = 0x03C0
    const val PID_WARNINGS_SUPER_SLOW = 0x03C4
    const val PID_POWER_MODE = 0x0121
    const val PID_FRONT_SEAT_HEAT = 0x0391
    const val PID_REAR_SEAT_HEAT = 0x03B4

    private const val MAX_29_BIT_ID = 0x1FFFFFFF
    private const val MAX_DATA_BYTES = 8
    private const val HEX_RADIX = 16
    private const val ID_11_BIT_CHARS = 3
    private const val ID_29_BIT_CHARS = 8
    private const val HEADER_29_BIT_BYTES = 4
    private const val BYTE_MASK = 0xFF
    private const val SIGNED_BYTE_OFFSET = 256
    private const val SIGNED_BYTE_MAX = 127

    // Window positions run 0 (shut) to 6 (fully down); 5 is a window's "no reading" filler.
    private const val WINDOW_OPEN_MAX = 6
    private const val WINDOW_FILLER = 5
    private const val WINDOW_BITS = 0x07
    private const val WINDOW_REAR_SHIFT = 3
    private const val HOOD_STATE_BITS = 0x03
    private const val SEAT_LEVEL_BITS = 0x1F
    private const val POWER_MODE_BITS = 0x03
    private const val NOT_AVAILABLE_12_BIT = 0xFFF
    private const val NOT_AVAILABLE_BYTE = 0xFF
    private const val GMLAN_RANGE_SCALE = 0.015625
    private const val PERCENT_PER_COUNT = 0.392157
    private const val TPMS_KPA_PER_COUNT = 4
    private const val TPMS_INVALID_RAW = 0xFE
    private const val KPH_PER_WHEEL_COUNT = 0.03125
    private const val KWH_PER_ENERGY_COUNT = 0.1
    private const val ENERGY_NOT_AVAILABLE = 0x3FFF
    private const val WHEEL_SPEED_BITS = 14
    private const val TRIP_BITS = 23
    private const val ENERGY_BITS = 14

    /**
     * Splits raw `STM` output (lines separated by CR/LF, possibly still carrying the `STOPPED`
     * marker and the `>` prompt) into frames. Anything that is not a well-formed frame line —
     * status text, `BUFFER FULL`, `CAN ERROR`, a torn partial line — is skipped, never guessed at.
     */
    @JvmStatic
    fun parseMonitorOutput(raw: String?): List<SwcanFrame> =
        (raw ?: "")
            .replace(">", "\n")
            .split('\r', '\n')
            .mapNotNull(::parseLine)

    /**
     * Parses one monitor line. Handles both header layouts the adapter can print:
     * - spaces on (`ATS1`): `10 24 80 40 C8 7B …` (29-bit, four header bytes) or `141 C8 …` (11-bit),
     *   optionally followed by a one-digit DLC when `ATD1` is on;
     * - spaces off (`ATS0`): `10248040C87B…` / `141C8…`, told apart by length parity (an 8-digit
     *   29-bit id plus whole bytes is even; a 3-digit 11-bit id plus whole bytes is odd).
     */
    @JvmStatic
    fun parseLine(line: String): SwcanFrame? {
        val tokens =
            line
                .trim()
                .uppercase(Locale.US)
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.any { token -> !token.all(::isHex) }) return null
        if (tokens.size == 1) return parseCompact(tokens[0])
        val first = tokens[0]
        return when {
            first.length == ID_11_BIT_CHARS -> frameOf(first, false, tokens.drop(1))
            first.length == ID_29_BIT_CHARS -> frameOf(first, true, tokens.drop(1))
            first.length == 2 &&
                tokens.size >= HEADER_29_BIT_BYTES &&
                tokens.take(HEADER_29_BIT_BYTES).all { it.length == 2 } ->
                frameOf(tokens.take(HEADER_29_BIT_BYTES).joinToString(""), true, tokens.drop(HEADER_29_BIT_BYTES))
            else -> null
        }
    }

    private fun parseCompact(token: String): SwcanFrame? {
        val idChars = if (token.length % 2 == 1) ID_11_BIT_CHARS else ID_29_BIT_CHARS
        if (token.length < idChars) return null
        return frameOf(token.substring(0, idChars), idChars == ID_29_BIT_CHARS, token.substring(idChars).chunked(2))
    }

    private fun frameOf(
        idHex: String,
        extended: Boolean,
        rest: List<String>,
    ): SwcanFrame? {
        val id = idHex.toLongOrNull(HEX_RADIX) ?: return null
        if (id > MAX_29_BIT_ID) return null
        // A single hex digit right after the id is the DLC printed by ATD1; skip it.
        val bytes = if (rest.firstOrNull()?.length == 1) rest.drop(1) else rest
        if (bytes.size > MAX_DATA_BYTES || bytes.any { it.length != 2 }) return null
        return SwcanFrame(id.toInt(), extended, IntArray(bytes.size) { bytes[it].toInt(HEX_RADIX) })
    }

    private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'A'..'F'

    /** Decodes every recognised frame in [frames]; later frames of the same field win. */
    @JvmStatic
    fun decodeAll(frames: List<SwcanFrame>): List<SwcanReading> = frames.flatMap(::decode)

    /** Decodes one frame. Unknown ids, 11-bit frames and short frames decode to nothing. */
    @JvmStatic
    fun decode(frame: SwcanFrame): List<SwcanReading> {
        if (!frame.extended) return emptyList()
        return decodeExact(frame) ?: decodeByGmlanPid(frame)
    }

    private fun decodeExact(frame: SwcanFrame): List<SwcanReading>? {
        val d = frame.data
        return when (frame.id) {
            ID_BATTERY_12V -> battery12v(d)
            ID_TPMS -> tpms(d)
            ID_DOOR_LOCK -> doorLock(d)
            ID_THEFT_ALARM -> alarm(d)
            ID_DOOR_FL -> bit(d, 0, 0, SwcanField.DOOR_FL, "open", "closed")
            ID_DOOR_FR -> bit(d, 0, 0, SwcanField.DOOR_FR, "open", "closed")
            ID_DOOR_RL -> bit(d, 0, 0, SwcanField.DOOR_RL, "open", "closed")
            ID_DOOR_RR -> bit(d, 0, 0, SwcanField.DOOR_RR, "open", "closed")
            ID_HOOD -> hood(d)
            ID_TRUNK -> bit(d, 0, 0, SwcanField.TRUNK, "open", "closed")
            ID_AC_COMPRESSOR -> acCompressor(d)
            ID_COOLANT_HEATER -> if (d.size >= 3) listOf(num(SwcanField.COOLANT_HEATER_KW, d[1] * 0.04, 2)) else none()
            ID_PE_COOLANT -> if (d.size >= 2) listOf(num(SwcanField.PE_COOLANT_TEMP, d[1] - 40.0, 0)) else none()
            ID_HEATER_CORE ->
                if (d.size >= 3 && d[2] != 0) listOf(num(SwcanField.HEATER_CORE_TEMP, d[2] - 40.0, 0)) else none()
            ID_CHARGE_LIMIT -> chargeLimit(d)
            ID_CLIMATE_GENERAL -> acState(d)
            ID_CLIMATE_BASIC -> blower(d)
            ID_CABIN_TEMP -> cabinTemp(d)
            ID_REMOTE_START -> remoteStart(d)
            ID_WHEEL_SPEED -> wheelSpeeds(d)
            ID_TRIP_ODOMETER -> tripOdometers(d)
            ID_ANALOG_SLOW -> transOilTemp(d)
            ID_CLIMATE_POWER -> if (d.size >= 5) listOf(num(SwcanField.AC_COMPRESSOR_KW, d[4] * 0.04, 2)) else none()
            ID_ENERGY_SPLIT -> energySplit(d)
            else -> null
        }
    }

    private fun decodeByGmlanPid(frame: SwcanFrame): List<SwcanReading> {
        val d = frame.data
        return when (frame.gmlanPid) {
            PID_ENERGY_STORAGE -> energyStorage(d)
            PID_EV_RANGE -> evRange(d)
            PID_FUEL_RANGE -> fuelRange(d)
            PID_DRIVE_CYCLE_DISTANCE -> driveCycleDistance(d)
            PID_DRIVE_CYCLE_ENERGY -> driveCycleFuel(d)
            PID_WINDOWS -> windows(d)
            PID_ENGINE_INFO_4 -> oilLife(d)
            PID_WARNINGS_FAST -> warnings(d, SwcanField.WARNINGS_FAST, FAST_WARNINGS)
            PID_WARNINGS_SLOW -> slowWarnings(d)
            PID_WARNINGS_SUPER_SLOW -> warnings(d, SwcanField.WARNINGS_SUPER_SLOW, SUPER_SLOW_WARNINGS)
            PID_WASHER_LEVEL -> warnings(d, SwcanField.WARNING_WASHER, WASHER_WARNINGS)
            PID_BULB_OUTAGE -> warnings(d, SwcanField.WARNING_BULBS, BULB_WARNINGS)
            PID_POWER_MODE -> powerMode(d)
            PID_FRONT_SEAT_HEAT -> seatHeat(d, SwcanField.SEAT_HEAT_FL, SwcanField.SEAT_HEAT_FR)
            PID_REAR_SEAT_HEAT -> seatHeat(d, SwcanField.SEAT_HEAT_RL, SwcanField.SEAT_HEAT_RR)
            else -> none()
        }
    }

    private fun none(): List<SwcanReading> = emptyList()

    private fun num(
        field: SwcanField,
        value: Double,
        decimals: Int,
    ): SwcanReading {
        var scale = 1.0
        repeat(decimals) { scale *= 10.0 }
        return SwcanReading(field, Math.round(value * scale) / scale)
    }

    private fun text(
        field: SwcanField,
        value: String,
    ): SwcanReading = SwcanReading(field, value)

    private fun bit(
        d: IntArray,
        byteIndex: Int,
        bitIndex: Int,
        field: SwcanField,
        whenSet: String,
        whenClear: String,
    ): List<SwcanReading> {
        if (d.size <= byteIndex) return none()
        val set = (d[byteIndex] shr bitIndex) and 1 == 1
        return listOf(text(field, if (set) whenSet else whenClear))
    }

    /** GM validity bits read 1 when the signal next to them is NOT valid. */
    private fun invalid(
        d: IntArray,
        byteIndex: Int,
        bitIndex: Int,
    ): Boolean = (d[byteIndex] shr bitIndex) and 1 == 1

    private fun signedByte(value: Int): Int {
        val b = value and BYTE_MASK
        return if (b > SIGNED_BYTE_MAX) b - SIGNED_BYTE_OFFSET else b
    }

    // Battery_Voltage (arb 0x124), intelligent battery sensor: BatVlt 0.1 V + 3 V, BatSOC 0.392 %,
    // BattCrntFltrd signed 0.5 A (charge-positive). A 2017 Volt sends BatSOC as 0xFF (not available)
    // in every frame, which would otherwise read as a steady 100 %, so that byte is dropped.
    private fun battery12v(d: IntArray): List<SwcanReading> {
        if (d.size < 6) return none()
        return listOfNotNull(
            num(SwcanField.AUX12V_VOLTAGE, d[2] * 0.1 + 3.0, 1),
            if (d[3] == NOT_AVAILABLE_BYTE) null else num(SwcanField.AUX12V_SOC, d[3] * PERCENT_PER_COUNT, 0),
            num(SwcanField.AUX12V_CURRENT, signedByte(d[5]) * 0.5, 1),
        )
    }

    /**
     * Tire_Pressure_Sensors_LS (arb 0x1EA): 4 kPa per count, bytes 2..5 = FL, RL, FR, RR. Each
     * pressure has a validity bit (TireLFPrsV byte 0 bit 0, RF bit 1, LR byte 1 bit 0, RR bit 1),
     * set when the reading is NOT valid: the car's own frames read `24 24` there with all four
     * good. A flagged wheel, or the 0xFE/0xFF "not available" codes, reads [SwcanReading.INVALID].
     * A valid 0 is a real 0 kPa (a flat tyre), not a missing sensor.
     */
    private fun tpms(d: IntArray): List<SwcanReading> {
        if (d.size < 6) return none()
        val wheels =
            listOf(
                Triple(SwcanField.TIRE_FL, 0, 0),
                Triple(SwcanField.TIRE_RL, 1, 0),
                Triple(SwcanField.TIRE_FR, 0, 1),
                Triple(SwcanField.TIRE_RR, 1, 1),
            )
        return wheels.mapIndexed { index, (field, validityByte, validityBit) ->
            val raw = d[index + 2]
            if (invalid(d, validityByte, validityBit) || raw >= TPMS_INVALID_RAW) {
                SwcanReading(field, SwcanReading.INVALID)
            } else {
                num(field, (raw * TPMS_KPA_PER_COUNT).toDouble(), 0)
            }
        }
    }

    // Door lock command event: byte 1 bit 0 = locked, byte 3 = who asked.
    private fun doorLock(d: IntArray): List<SwcanReading> {
        if (d.size < 4) return none()
        val source =
            when (d[3]) {
                0x01 -> "panel"
                0x05 -> "fob"
                0x06 -> "keyless"
                0x07 -> "OnStar"
                0x0A -> "auto"
                else -> "unknown"
            }
        val state = if (d[1] and 1 == 1) "locked" else "unlocked"
        return listOf(text(SwcanField.LOCK_STATE, state), text(SwcanField.LOCK_SOURCE, source))
    }

    // Content theft sensor: byte 2 bits 0..2. 4 is the ~30 s arming phase right after locking.
    private fun alarm(d: IntArray): List<SwcanReading> {
        if (d.size < 3) return none()
        val state =
            when (d[2] and WINDOW_BITS) {
                1 -> "armed"
                2 -> "disarmed"
                3 -> "sounding"
                4 -> "arming"
                else -> return none()
            }
        return listOf(text(SwcanField.ALARM, state))
    }

    // Thrml_Ref_Compressor_Status_LS (arb 0x138): evaporator outlet air 0.5 °C - 40, compressor rpm.
    private fun acCompressor(d: IntArray): List<SwcanReading> {
        if (d.size < 4) return none()
        val rpm = ((d[2] and 0x3F) shl 8) or d[3]
        return listOf(
            num(SwcanField.AC_EVAP_TEMP, d[1] * 0.5 - 40.0, 1),
            num(SwcanField.AC_COMPRESSOR_RPM, rpm.toDouble(), 0),
        )
    }

    // Remote start status (va_ac_preheat.cpp ClimateControlIncomingSWCAN, case 0x10390040): byte 0
    // is 0 when remote start is off; bit 1 set is a remote start request / active preheat.
    private fun remoteStart(d: IntArray): List<SwcanReading> {
        if (d.isEmpty()) return none()
        return when {
            d[0] == 0 -> listOf(text(SwcanField.REMOTE_START, "off"))
            (d[0] shr 1) and 1 == 1 -> listOf(text(SwcanField.REMOTE_START, "on"))
            else -> none()
        }
    }

    // Climate control general status: byte 0 bits 4..5, 1 = A/C off, 2 = A/C on.
    private fun acState(d: IntArray): List<SwcanReading> {
        if (d.isEmpty()) return none()
        return when ((d[0] shr 4) and 3) {
            1 -> listOf(text(SwcanField.AC_STATE, "off"))
            2 -> listOf(text(SwcanField.AC_STATE, "on"))
            else -> none()
        }
    }

    // Climate_Control_Basic_Status_LS (arb 0x40A): byte 1 is the front blower (ClmCntFrBlwFnSp),
    // 0.392 % per count. Byte 2 is the A/C compressor load estimate, which an earlier decoder read as
    // the blower: it is non-zero whenever the A/C runs, even before the fan spins up. On the car the
    // fan read 30-51 % on a 2026-10-04 A/C drive and ramped 0 -> 27 % -> 0 in the 2026-09-29 capture.
    private fun blower(d: IntArray): List<SwcanReading> {
        if (d.size < 2) return none()
        return listOf(num(SwcanField.BLOWER, d[1] * PERCENT_PER_COUNT, 0))
    }

    // Alarm_2_Request_LS (arb 0x220): byte 4 is the cabin air estimate (EstBulkIntAirTmp, 0.5 °C - 40),
    // valid unless byte 0 bit 2 is set. Byte 5 is the roof surface and byte 6 the dash surface.
    private fun cabinTemp(d: IntArray): List<SwcanReading> {
        if (d.size < 5 || invalid(d, 0, 2)) return none()
        return listOf(num(SwcanField.CABIN_TEMP, d[4] / 2.0 - 40.0, 1))
    }

    // Wheel_Grnd_Velocity_LS (arb 0x35C): four 14-bit speeds, 1/32 km/h, left driven (front), left
    // non-driven, right driven, right non-driven, each with a validity bit just above it.
    private fun wheelSpeeds(d: IntArray): List<SwcanReading> {
        if (d.size < MAX_DATA_BYTES) return none()
        val wheels = listOf(SwcanField.WHEEL_FL, SwcanField.WHEEL_RL, SwcanField.WHEEL_FR, SwcanField.WHEEL_RR)
        return wheels.mapIndexedNotNull { index, field ->
            val byteIndex = index * 2
            if (invalid(d, byteIndex, 6)) return@mapIndexedNotNull null
            val raw = dbcBigEndian(d, byteIndex * 8 + 5, WHEEL_SPEED_BITS) ?: return@mapIndexedNotNull null
            num(field, raw * KPH_PER_WHEEL_COUNT, 2)
        }
    }

    // VehInfoTripComputer_LS (arb 0x1EB, from the cluster): the two trip odometers, 23 bits at
    // 1/64 km. Trip A is valid unless byte 0 bit 7 is set, trip B unless bit 6 is.
    private fun tripOdometers(d: IntArray): List<SwcanReading> {
        if (d.size < 7) return none()
        return listOfNotNull(
            dbcBigEndian(d, 14, TRIP_BITS)
                ?.takeUnless { invalid(d, 0, 7) }
                ?.let { num(SwcanField.TRIP_A, it * GMLAN_RANGE_SCALE, 2) },
            dbcBigEndian(d, 38, TRIP_BITS)
                ?.takeUnless { invalid(d, 0, 6) }
                ?.let { num(SwcanField.TRIP_B, it * GMLAN_RANGE_SCALE, 2) },
        )
    }

    // Analog_Values_Slow_LS (arb 0x170): byte 3 is the transmission (drive unit) oil temperature,
    // 1 °C - 40, valid unless byte 0 bit 5 is set. It climbed 29 -> 84 °C over a 2026-10-04 highway drive.
    private fun transOilTemp(d: IntArray): List<SwcanReading> {
        if (d.size < 4 || invalid(d, 0, 5)) return none()
        return listOf(num(SwcanField.TRANS_OIL_TEMP, d[3] - 40.0, 0))
    }

    // Drv_Cycl_Elec_Enrgy_States_LS (arb 0x216): the car's energy screen. Four 14-bit counts at
    // 0.36 MJ (0.1 kWh): used driving, used by climate, used conditioning the battery, and usable
    // energy left. On 2026-10-04 "left" fell 12.2 -> 8.5 kWh while driving + climate rose by 3.8.
    private fun energySplit(d: IntArray): List<SwcanReading> {
        if (d.size < MAX_DATA_BYTES) return none()
        val fields =
            listOf(
                SwcanField.CYCLE_DRIVING_ENERGY,
                SwcanField.CYCLE_CLIMATE_ENERGY,
                SwcanField.CYCLE_CONDITIONING_ENERGY,
                SwcanField.BATTERY_ENERGY_LEFT,
            )
        return fields.mapIndexedNotNull { index, field ->
            val raw = dbcBigEndian(d, index * 16 + 5, ENERGY_BITS) ?: return@mapIndexedNotNull null
            if (raw == ENERGY_NOT_AVAILABLE.toLong()) null else num(field, raw * KWH_PER_ENERGY_COUNT, 1)
        }
    }

    // High Volt Time Based Charge: up to four selectable charge-current levels plus the index of
    // the one in force. Levels after the first zero are not offered.
    private fun chargeLimit(d: IntArray): List<SwcanReading> {
        if (d.size < 6) return none()
        val setLevel = (d[3] shr 4) and WINDOW_BITS
        val levels =
            intArrayOf(
                (((d[3] shl 8) or d[4]) shr 7) and 0x1F,
                (d[4] shr 2) and 0x1F,
                (((d[4] shl 8) or d[5]) shr 5) and 0x1F,
                d[5] and 0x1F,
            )
        var offered = 1
        while (offered < levels.size && levels[offered] > 0) offered++
        if (setLevel >= offered) return none()
        return listOf(num(SwcanField.CHARGE_LIMIT, levels[setLevel].toDouble(), 0))
    }

    // Energy_Storage_System_LS: drive-cycle energy used, 14 bits from bit 21 at 0.1 kWh.
    private fun energyStorage(d: IntArray): List<SwcanReading> {
        if (d.size < MAX_DATA_BYTES) return none()
        val used = dbcBigEndian(d, 21, 14) ?: return none()
        return listOf(num(SwcanField.CYCLE_ENERGY_USED, used * 0.1, 1))
    }

    // HMI_Hybrid_Vehicle_Status_LS: the EV range the cluster shows, 16 bits from bit 0, km.
    private fun evRange(d: IntArray): List<SwcanReading> {
        val raw = dbcBigEndian(d, 0, 16) ?: return none()
        return listOf(num(SwcanField.CLUSTER_EV_RANGE, (raw * GMLAN_RANGE_SCALE).roundToInt().toDouble(), 0))
    }

    // Fuel_Level_Status_LS: gasoline range, 17 bits from bit 8, km. 0 = car not on yet, no figure.
    private fun fuelRange(d: IntArray): List<SwcanReading> {
        if (d.size < 4) return none()
        val raw = dbcBigEndian(d, 8, 17) ?: return none()
        if (raw == 0L) return none()
        return listOf(num(SwcanField.FUEL_RANGE, (raw * GMLAN_RANGE_SCALE).roundToInt().toDouble(), 0))
    }

    // Drive_Cycle_Efficiency_LS: km on battery and on fuel since the last full charge.
    private fun driveCycleDistance(d: IntArray): List<SwcanReading> {
        if (d.size < MAX_DATA_BYTES) return none()
        val battery = dbcBigEndian(d, 7, 17) ?: return none()
        val fuel = dbcBigEndian(d, 22, 17) ?: return none()
        return listOf(
            num(SwcanField.CYCLE_EV_DISTANCE, battery * GMLAN_RANGE_SCALE, 1),
            num(SwcanField.CYCLE_FUEL_DISTANCE, fuel * GMLAN_RANGE_SCALE, 1),
        )
    }

    // Drive_Cycle_Energy_Efficiency_LS: fuel burned this drive cycle, 12 bits from bit 51 at
    // 0.125 L; 0xFFF means not available.
    private fun driveCycleFuel(d: IntArray): List<SwcanReading> {
        if (d.size < MAX_DATA_BYTES) return none()
        val raw = dbcBigEndian(d, 51, 12) ?: return none()
        if (raw == NOT_AVAILABLE_12_BIT.toLong()) return none()
        return listOf(num(SwcanField.CYCLE_FUEL_USED, raw * 0.125, 2))
    }

    /**
     * Window_Position_Status_LS (arb 0x325): a 3-bit position per window, driver, left rear,
     * passenger, right rear (GM's DrvWndPosStat / LRWndPosStat / PsWndPosStat / RRWndPosStat).
     * Checked on the car 2026-10-06: 0 is up, 6 fully down, 3 part way (the driver window read 6,
     * 3, then 0 as it came up), and 5 is a window not known since the car woke: each read 5 until it
     * first moved, then 0. 5 and the undefined 7 read [SwcanReading.INVALID], each window on its own:
     * the window's position is not known, so an earlier one must not stand in for it (an earlier
     * decoder that dropped a frame with any 5 in it never showed the driver's window moving).
     */
    private fun windows(d: IntArray): List<SwcanReading> {
        if (d.size < 2) return none()
        val raw =
            listOf(
                SwcanField.WINDOW_FL to (d[0] and WINDOW_BITS),
                SwcanField.WINDOW_RL to ((d[0] shr WINDOW_REAR_SHIFT) and WINDOW_BITS),
                SwcanField.WINDOW_FR to (d[1] and WINDOW_BITS),
                SwcanField.WINDOW_RR to ((d[1] shr WINDOW_REAR_SHIFT) and WINDOW_BITS),
            )
        return raw.map { (field, pos) ->
            if (pos == WINDOW_FILLER || pos > WINDOW_OPEN_MAX) {
                SwcanReading(field, SwcanReading.INVALID)
            } else {
                num(field, pos * 100.0 / WINDOW_OPEN_MAX, 0)
            }
        }
    }

    // Hood_Status_LS (arb 0x394): HdSt is byte 0 bits 0..1, valid unless bit 2 is set. 0 read on the
    // car with the hood shut (2026-10-06); any other state is treated as open. A report flagged not
    // valid reads INVALID, so an earlier "closed" can't stand in for it.
    private fun hood(d: IntArray): List<SwcanReading> {
        if (d.isEmpty()) return none()
        if (invalid(d, 0, 2)) return listOf(SwcanReading(SwcanField.HOOD, SwcanReading.INVALID))
        return listOf(text(SwcanField.HOOD, if (d[0] and HOOD_STATE_BITS == 0) "closed" else "open"))
    }

    /**
     * Front_Seat_Heat_Cool_Control_LS (arb 0x391) and Rear_Seat_Heat_Cool_Control_LS (arb 0x3B4):
     * the seat-heat modules' level lamps, five per seat, bits 0..4 of byte 2 (driver, rear left) and
     * byte 3 (passenger, rear right), per GM's signal list (DrvHCSLSeatLev1-5, PassHCSLSeatLev1-5).
     * The level is how many are lit. Not the switch frames (0x392, 0x3B6), which only say a button is
     * down. UNCONFIRMED-ON-CAR: the guided car test presses each button to check it.
     */
    private fun seatHeat(
        d: IntArray,
        left: SwcanField,
        right: SwcanField,
    ): List<SwcanReading> {
        if (d.size < 4) return none()
        return listOf(
            num(left, Integer.bitCount(d[2] and SEAT_LEVEL_BITS).toDouble(), 0),
            num(right, Integer.bitCount(d[3] and SEAT_LEVEL_BITS).toDouble(), 0),
        )
    }

    // System_Power_Mode_LS (arb 0x121): SysPwrMd in byte 0 bits 0-1, its validity flag in bit 2.
    // On the car it read run while on and off as it shut down (2026-10-06), about every 5 s.
    private fun powerMode(d: IntArray): List<SwcanReading> {
        if (d.isEmpty() || invalid(d, 0, 2)) return none()
        return listOf(text(SwcanField.POWER_MODE, POWER_MODES[d[0] and POWER_MODE_BITS]))
    }

    // Engine_Information_4_LS (arb 0x168): byte 4 is the engine oil life left, 0.392 % per count
    // (EngOilRmnLf). It read about 70 % on 2026-10-04; the car refuses the polled oil-life PID.
    private fun oilLife(d: IntArray): List<SwcanReading> {
        if (d.size < 5) return none()
        return listOf(num(SwcanField.OIL_LIFE, d[4] * PERCENT_PER_COUNT, 0))
    }

    // HS_Indications_Slow_LS (arb 0x3C0): the flag lights, plus brake fluid low (byte 0 bit 7),
    // which only counts while its validity bit (byte 0 bit 4) is clear.
    private fun slowWarnings(d: IntArray): List<SwcanReading> {
        if (d.size < 2) return none()
        val fluid = (d[0] shr 7) and 1 == 1 && !invalid(d, 0, 4)
        val codes = litCodes(d, SLOW_WARNINGS) + listOfNotNull(if (fluid) "brake_fluid_low" else null)
        return listOf(text(SwcanField.WARNINGS_SLOW, codes.joinToString(",")))
    }

    /** One dash-warning broadcast: the codes of the lights it says are on, "" for none. */
    private fun warnings(
        d: IntArray,
        field: SwcanField,
        flags: List<WarningFlag>,
    ): List<SwcanReading> {
        if (d.size <= flags.maxOf { it.byteIndex }) return none()
        return listOf(text(field, litCodes(d, flags).joinToString(",")))
    }

    private fun litCodes(
        d: IntArray,
        flags: List<WarningFlag>,
    ): List<String> = flags.filter { (d[it.byteIndex] shr it.bit) and 1 == 1 }.map { it.code }

    /** A one-bit warning light in a GM indication frame (DBC start bit = byte * 8 + bit). */
    private class WarningFlag(
        val byteIndex: Int,
        val bit: Int,
        val code: String,
    )

    // GM low-speed DBC layouts. The car sent all of these with no light on, in every capture so far.

    /** GMLAN system power modes by SysPwrMd value. */
    private val POWER_MODES = listOf("off", "accessory", "run", "crank")

    private val FAST_WARNINGS = listOf(WarningFlag(0, 0, "abs"))
    private val SLOW_WARNINGS =
        listOf(
            WarningFlag(0, 2, "tire_pressure_low"),
            WarningFlag(0, 5, "oil_starvation"),
            WarningFlag(1, 5, "brake_pads"),
            WarningFlag(1, 7, "brake_system"),
        )
    private val SUPER_SLOW_WARNINGS =
        listOf(
            WarningFlag(0, 5, "oil_hot"),
            WarningFlag(1, 1, "oil_change"),
            WarningFlag(1, 2, "oil_level_low"),
            WarningFlag(1, 3, "oil_pressure_low"),
            WarningFlag(1, 5, "reduced_power"),
            WarningFlag(1, 6, "fuel_cap"),
            WarningFlag(1, 7, "engine_hot"),
            WarningFlag(2, 1, "power_steering"),
            WarningFlag(2, 6, "steering_assist_reduced"),
        )
    private val WASHER_WARNINGS = listOf(WarningFlag(0, 0, "washer_fluid_low"))

    // BulbOutage_LS (arb 0x319): one bit per failed lamp, 16 lamps over two bytes.
    private val BULB_WARNINGS =
        listOf(
            "bulb_center_brake",
            "bulb_front_left_turn",
            "bulb_front_right_turn",
            "bulb_left_brake",
            "bulb_left_low_beam",
            "bulb_left_parking",
            "bulb_license_plate",
            "bulb_rear_left_turn",
            "bulb_rear_right_turn",
            "bulb_right_brake",
            "bulb_right_low_beam",
            "bulb_right_parking",
            "bulb_rear_fog",
            "bulb_reverse",
            "bulb_left_daytime",
            "bulb_right_daytime",
        ).mapIndexed { i, code -> WarningFlag(i / 8, i % 8, code) }

    /**
     * DBC Motorola (`@0+`) signal extraction: [start] is the MSB's bit number (byte = n/8, bit =
     * n%8 from the LSB); the signal walks down within a byte and jumps to bit 7 of the next byte.
     * Returns null when the signal runs past the frame, instead of inventing zeros.
     */
    @JvmStatic
    fun dbcBigEndian(
        d: IntArray,
        start: Int,
        length: Int,
    ): Long? {
        var value = 0L
        var bit = start
        repeat(length) {
            val byteIndex = bit shr 3
            if (byteIndex !in d.indices) return null
            value = (value shl 1) or ((d[byteIndex] shr (bit and 7)) and 1).toLong()
            bit = if (bit and 7 == 0) bit + 15 else bit - 1
        }
        return value
    }
}
