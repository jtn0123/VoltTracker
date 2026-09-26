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
    /** A [Double] for numeric fields, a [String] for state fields. */
    val value: Any,
)

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
    HEATER_CORE_TEMP(SwcanGroup.CLIMATE),
    COOLANT_HEATER_KW(SwcanGroup.CLIMATE),
    PE_COOLANT_TEMP(SwcanGroup.POWER_ELECTRONICS),
    CHARGE_LIMIT(SwcanGroup.CHARGE_LIMIT),
    CLUSTER_EV_RANGE(SwcanGroup.RANGE),
    FUEL_RANGE(SwcanGroup.RANGE),
    CYCLE_ENERGY_USED(SwcanGroup.DRIVE_CYCLE),
    CYCLE_EV_DISTANCE(SwcanGroup.DRIVE_CYCLE),
    CYCLE_FUEL_DISTANCE(SwcanGroup.DRIVE_CYCLE),
    CYCLE_FUEL_USED(SwcanGroup.DRIVE_CYCLE),
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
}

/**
 * Pure parser + decoder for the gen-2 Volt's single-wire CAN broadcast traffic, as captured by an
 * OBDLink adapter in listen-only monitor mode (`STP 61`, `STCMM 0`, `STM`).
 *
 * Frame layouts and scalings are ported from the OVMS `vehicle_voltampera` module
 * (`IncomingFrameCan4`, `IncomingDriveCycleSWCAN`, `ClimateControlIncomingSWCAN`), which its authors
 * tested on a MY2017 Volt. NONE of these decodes has been confirmed by VoltTracker on the target car
 * yet: every value here is UNCONFIRMED-ON-CAR until a real SW-CAN capture has been compared against
 * the car's own displays. Everything is read-only; nothing in this file builds a frame to send.
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

    // ---- 13-bit GMLAN parameter ids, matched regardless of priority/source bits ----
    const val PID_ENERGY_STORAGE = 0x0141
    const val PID_EV_RANGE = 0x0176
    const val PID_DRIVE_CYCLE_ENERGY = 0x0223
    const val PID_FUEL_RANGE = 0x0224
    const val PID_DRIVE_CYCLE_DISTANCE = 0x0225
    const val PID_WINDOWS = 0x0325

    private const val MAX_29_BIT_ID = 0x1FFFFFFF
    private const val MAX_DATA_BYTES = 8
    private const val HEX_RADIX = 16
    private const val ID_11_BIT_CHARS = 3
    private const val ID_29_BIT_CHARS = 8
    private const val HEADER_29_BIT_BYTES = 4
    private const val BYTE_MASK = 0xFF
    private const val SIGNED_BYTE_OFFSET = 256
    private const val SIGNED_BYTE_MAX = 127

    // Window positions run 0 (shut) to 6 (fully down); 5 is the BCM's "no reading" filler.
    private const val WINDOW_OPEN_MAX = 6
    private const val WINDOW_FILLER = 5
    private const val WINDOW_BITS = 0x07
    private const val WINDOW_REAR_SHIFT = 3
    private const val NOT_AVAILABLE_12_BIT = 0xFFF
    private const val GMLAN_RANGE_SCALE = 0.015625
    private const val PERCENT_PER_COUNT = 0.392157
    private const val TPMS_KPA_PER_COUNT = 4
    private const val TPMS_INVALID_RAW = 0xFE

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
            ID_HOOD -> bit(d, 0, 1, SwcanField.HOOD, "open", "closed")
            ID_TRUNK -> bit(d, 0, 0, SwcanField.TRUNK, "open", "closed")
            ID_AC_COMPRESSOR -> acCompressor(d)
            ID_COOLANT_HEATER -> if (d.size >= 3) listOf(num(SwcanField.COOLANT_HEATER_KW, d[1] * 0.04, 2)) else none()
            ID_PE_COOLANT -> if (d.size >= 2) listOf(num(SwcanField.PE_COOLANT_TEMP, d[1] - 40.0, 0)) else none()
            ID_HEATER_CORE ->
                if (d.size >= 3 && d[2] != 0) listOf(num(SwcanField.HEATER_CORE_TEMP, d[2] - 40.0, 0)) else none()
            ID_CHARGE_LIMIT -> chargeLimit(d)
            ID_CLIMATE_GENERAL -> acState(d)
            ID_CLIMATE_BASIC -> if (d.size >= 2) listOf(num(SwcanField.BLOWER, d[1] * 0.39, 0)) else none()
            ID_CABIN_TEMP -> if (d.size >= 6) listOf(num(SwcanField.CABIN_TEMP, d[5] / 2.0 - 40.0, 1)) else none()
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

    private fun signedByte(value: Int): Int {
        val b = value and BYTE_MASK
        return if (b > SIGNED_BYTE_MAX) b - SIGNED_BYTE_OFFSET else b
    }

    // Battery_Voltage (arb 0x124), intelligent battery sensor: BatVlt 0.1 V + 3 V, BatSOC 0.392 %,
    // BattCrntFltrd signed 0.5 A (charge-positive).
    private fun battery12v(d: IntArray): List<SwcanReading> {
        if (d.size < 6) return none()
        return listOf(
            num(SwcanField.AUX12V_VOLTAGE, d[2] * 0.1 + 3.0, 1),
            num(SwcanField.AUX12V_SOC, d[3] * PERCENT_PER_COUNT, 0),
            num(SwcanField.AUX12V_CURRENT, signedByte(d[5]) * 0.5, 1),
        )
    }

    // Tire pressures, 4 kPa per count, bytes 2..5 = FL, RL, FR, RR. 0 and 0xFE/0xFF mean no sensor
    // reading (a missing or unlearned sensor) and are dropped.
    private fun tpms(d: IntArray): List<SwcanReading> {
        if (d.size < 6) return none()
        val order = listOf(SwcanField.TIRE_FL, SwcanField.TIRE_RL, SwcanField.TIRE_FR, SwcanField.TIRE_RR)
        return order.mapIndexedNotNull { index, field ->
            val raw = d[index + 2]
            if (raw == 0 || raw >= TPMS_INVALID_RAW) null else num(field, (raw * TPMS_KPA_PER_COUNT).toDouble(), 0)
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

    // Climate control general status: byte 0 bits 4..5, 1 = A/C off, 2 = A/C on.
    private fun acState(d: IntArray): List<SwcanReading> {
        if (d.isEmpty()) return none()
        return when ((d[0] shr 4) and 3) {
            1 -> listOf(text(SwcanField.AC_STATE, "off"))
            2 -> listOf(text(SwcanField.AC_STATE, "on"))
            else -> none()
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
     * Window_Position_Status_LS. Positions only mean something WHILE the glass moves; at rest the
     * BCM sends a fixed idle pattern (driver 0, others 5). Per OVMS's on-car analysis 5 is a
     * per-window "no reading" filler, so it is skipped, and a frame where all three non-driver
     * windows read 5 is pure idle filler and yields nothing (not even the driver's 0).
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
        if (raw.drop(1).all { it.second == WINDOW_FILLER }) return none()
        return raw
            .filter { it.second != WINDOW_FILLER }
            .map { (field, pos) -> num(field, minOf(pos, WINDOW_OPEN_MAX) * 100.0 / WINDOW_OPEN_MAX, 0) }
    }

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
