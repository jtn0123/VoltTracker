package com.volttracker.obdpoc

import com.volttracker.obdpoc.ObdProtocol.AUX_VOLTAGE_RANGE
import com.volttracker.obdpoc.ObdProtocol.ParsedPidValue
import com.volttracker.obdpoc.ObdProtocol.Range
import com.volttracker.obdpoc.ObdProtocol.TEMP_C_RANGE
import com.volttracker.obdpoc.ObdProtocol.bounded
import com.volttracker.obdpoc.ObdProtocol.mode22Payload
import com.volttracker.obdpoc.ObdProtocol.value

/**
 * GM/Volt manufacturer-specific Mode-22 PID parser registry. This is the Mode-22 counterpart of
 * [ObdKnownValueParserRegistry.standardParsers]: each command maps to a small parser instead of a
 * branch in the former ObdVoltMode22Decoder when-table. [ObdKnownValueParserRegistry.parse] runs
 * the standard table first, then falls through to [parse] here.
 *
 * NOT OBD-II standard: the PID list and the scale/offset constants below are reverse-engineered
 * from community sources and are NOT validated against this car's real readings — treat the
 * decoded values as approximate until confirmed on-car, and never change a constant without a
 * matching golden-test vector (ObdProtocolTest pins every family at exact values).
 * Sources + open questions: docs/volt-pid-research-2026-05-20.md,
 * docs/pid-validation-2026-06-03.md, docs/volt-pids-community-sheet.csv.
 *
 * Decode helpers (each value(name, …, unit, decimals) labels the signal + unit):
 *   voltByteValue(resp, cmd, scale, offset)   = payload[0] * scale + offset      (1 byte)
 *   voltWordValue(resp, cmd, divisor, signed) = word / divisor, word = 2 bytes big-endian,
 *                                               two's-complement when signed = true
 */
internal object ObdVoltMode22ParserRegistry {
    internal fun interface Mode22Parser {
        fun parse(
            cleanCommand: String,
            response: String?,
        ): ParsedPidValue?
    }

    fun parse(
        cleanCommand: String,
        response: String?,
    ): ParsedPidValue? {
        val parser = mode22Parsers[cleanCommand]
        if (parser != null) {
            // A mapped command must return its parser's result EVEN WHEN NULL: several mapped
            // DIDs (e.g. "2241A3" = pack capacity on 7E4) collide with the header-aware 96-cell
            // probe DIDs on the 7E7 BECM, and falling through on null would decode a 7E4 frame
            // with the 7E7 meaning. The cell-probe runner bypasses this table entirely via
            // ObdProtocol.parseCellVoltageProbe.
            return parser.parse(cleanCommand, response)
        }
        if (ObdVoltCellVoltageDecoder.isProbe(cleanCommand)) {
            return ObdVoltCellVoltageDecoder.parseProbe(cleanCommand, response)
        }
        return null
    }

    /** Word PID: `(A*256+B) / divisor`, optionally two's-complement, bounded when [range] set. */
    private fun wordPid(
        name: String,
        unit: String,
        decimals: Int,
        divisor: Double,
        signed: Boolean,
        range: Range? = null,
    ): Mode22Parser =
        Mode22Parser { command, response ->
            voltWordValue(response, command, divisor, signed)
                ?.let { if (range == null) it else bounded(it, range) }
                ?.let { value(name, it, unit, decimals) }
        }

    /** Word PID with a linear transform: `(A*256+B) * scale + offset`. */
    private fun wordLinearPid(
        name: String,
        unit: String,
        decimals: Int,
        scale: Double,
        offset: Double,
        signed: Boolean,
        range: Range,
    ): Mode22Parser =
        Mode22Parser { command, response ->
            voltWordLinearValue(response, command, scale, offset, signed)
                ?.let { bounded(it, range) }
                ?.let { value(name, it, unit, decimals) }
        }

    /** Byte PID: `A * scale + offset`, bounded when [range] set. */
    private fun bytePid(
        name: String,
        unit: String,
        decimals: Int,
        scale: Double,
        offset: Double,
        range: Range? = null,
    ): Mode22Parser =
        Mode22Parser { command, response ->
            voltByteValue(response, command, scale, offset)
                ?.let { if (range == null) it else bounded(it, range) }
                ?.let { value(name, it, unit, decimals) }
        }

    private fun sectionTemperaturePid(section: Int): Mode22Parser =
        bytePid("battery section $section temperature", "deg C", 0, 1.0, -40.0, TEMP_C_RANGE)

    // Declared BEFORE mode22Parsers: the map initializer captures these Range instances while
    // the object is still initializing, so they must already be assigned.
    private val HV_VOLTAGE_RANGE = Range(0.0, 500.0)
    private val AC_VOLTAGE_RANGE = Range(0.0, 280.0)
    private val AC_CURRENT_RANGE = Range(0.0, 80.0)
    private val CURRENT_A_RANGE = Range(-500.0, 500.0)

    // Health bounds reject decode garbage, not bad news: a worn pack below 30 Ah or a faulted
    // cell below 3.0 V is exactly what long-term tracking exists to surface, so these floors sit
    // at physically-possible rather than healthy values.
    private val CAPACITY_AH_RANGE = Range(10.0, 60.0)
    private val CELL_NUMBER_RANGE = Range(1.0, 96.0)
    private val PERCENT_RANGE = Range(0.0, 100.0)
    private val PACK_RESISTANCE_RANGE = Range(0.0, 10_000.0)
    private val ISOLATION_KOHM_RANGE = Range(0.0, 25_000.0)
    private val ISOLATION_OHM_RANGE = Range(0.0, 5_000_000.0)
    private val BRAKE_TORQUE_RANGE = Range(-10_000.0, 10_000.0)
    private val BRAKE_PEDAL_MM_RANGE = Range(-200.0, 200.0)
    private val PUMP_RPM_RANGE = Range(0.0, 10_000.0)
    private val HEATER_POWER_RANGE = Range(-100.0, 10_000.0)
    private val EV_DISTANCE_KM_RANGE = Range(0.0, 655.35)

    private val mode22Parsers: Map<String, Mode22Parser> =
        buildMap {
            put("222429", wordPid("hv pack voltage", "V", 1, 64.0, true, HV_VOLTAGE_RANGE))
            put("222414", wordPid("hv pack current", "A", 2, 20.0, true, CURRENT_A_RANGE))
            val oilLife = bytePid("engine oil life", "%", 0, 100.0 / 255.0, 0.0)
            put("22119F", oilLife)
            put("22119F01", oilLife)
            put("221154", bytePid("engine oil temperature", "deg C", 0, 1.0, -40.0, TEMP_C_RANGE))
            put("222883", wordPid("motor A current", "A", 2, 20.0, true, CURRENT_A_RANGE))
            put("222884", wordPid("motor B current", "A", 2, 20.0, true, CURRENT_A_RANGE))
            put("222885", wordPid("motor A voltage", "V", 2, 100.0, false, HV_VOLTAGE_RANGE))
            put("222886", wordPid("motor B voltage", "V", 2, 100.0, false, HV_VOLTAGE_RANGE))
            // Distance-this-cycle is non-negative: decode unsigned (a high bit must not flip it
            // negative) and bound 0..(0xFFFF/100) km — within an unsigned 16-bit word.
            put("222487", wordPid("ev distance this cycle", "km", 2, 100.0, false, EV_DISTANCE_KM_RANGE))
            // PRNDL is a clean gear code; emit the number, not rawByteValue's "RAW_<n>".
            put("222889", bytePid("prndl state", "", 0, 1.0, 0.0))
            val transmissionTemperature = bytePid("transmission temperature", "deg C", 0, 1.0, -40.0, TEMP_C_RANGE)
            put("221940", transmissionTemperature)
            put("22194001", transmissionTemperature)
            put("22434F", bytePid("hv battery temperature", "deg C", 0, 1.0, -40.0, TEMP_C_RANGE))
            put("224368", bytePid("charger ac voltage", "V", 0, 2.0, 0.0, AC_VOLTAGE_RANGE))
            put("224369", bytePid("charger ac current", "A", 1, 0.2, 0.0, AC_CURRENT_RANGE))
            put("22436B", wordPid("charger hv voltage", "V", 1, 2.0, true, HV_VOLTAGE_RANGE))
            put("22436C", wordPid("charger hv current", "A", 2, 20.0, true, CURRENT_A_RANGE))
            put("224373", Mode22Parser { command, response -> chargeModeValue(response, command) })
            put("22437D", wordPid("last charge energy", "Wh", 0, 0.1, false))
            put("2243A5", wordPid("hv battery charge count", "count", 0, 1.0, false))
            put(
                "2243AF",
                Mode22Parser { command, response ->
                    voltWordPercentValue(response, command)?.let { value("hv battery raw soc", it, "%", 2) }
                },
            )
            put("2241A3", wordPid("HV battery capacity", "Ah", 1, 10.0, false, CAPACITY_AH_RANGE))
            put("2245F9", wordPid("HV battery capacity fallback", "Ah", 2, 100.0, false, CAPACITY_AH_RANGE))
            put(
                "224329",
                Mode22Parser { command, response ->
                    ObdVoltCellVoltageDecoder.aggregate(response, command, "minimum cell voltage", true)
                },
            )
            put(
                "22432B",
                Mode22Parser { command, response ->
                    ObdVoltCellVoltageDecoder.aggregate(response, command, "maximum cell voltage", true)
                },
            )
            put("22432A", bytePid("minimum cell number", "", 0, 1.0, 0.0, CELL_NUMBER_RANGE))
            put("22432C", bytePid("maximum cell number", "", 0, 1.0, 0.0, CELL_NUMBER_RANGE))
            put("22435F", bytePid("SOC variation", "%", 1, 1.0 / 2.55, 0.0, PERCENT_RANGE))
            put("2240E9", wordPid("pack resistance", "ohm", 1, 2.0, false, PACK_RESISTANCE_RANGE))
            put("22433B", wordLinearPid("minimum pack voltage", "V", 1, 0.52, 0.0, false, HV_VOLTAGE_RANGE))
            put("22433C", wordLinearPid("maximum pack voltage", "V", 1, 0.52, 0.0, false, HV_VOLTAGE_RANGE))
            put("224349", bytePid("HV battery max temperature", "deg C", 0, 1.0, -40.0, TEMP_C_RANGE))
            put("22434A", bytePid("HV battery min temperature", "deg C", 0, 1.0, -40.0, TEMP_C_RANGE))
            put("22434B", bytePid("HV battery max-temp module", "", 0, 1.0, 0.0, CELL_NUMBER_RANGE))
            put("22434C", bytePid("HV battery min-temp module", "", 0, 1.0, 0.0, CELL_NUMBER_RANGE))
            put("221C43", bytePid("power electronics coolant loop temperature", "deg C", 0, 1.0, -40.0, TEMP_C_RANGE))
            put("2241A4", bytePid("battery coolant temperature", "deg C", 0, 1.0, -40.0, TEMP_C_RANGE))
            put("22433F", bytePid("minimum SOC limit", "%", 1, 1.0 / 2.55, 0.0, PERCENT_RANGE))
            put("2241B0", wordPid("APM output power", "W", 1, 16.0, false, HEATER_POWER_RANGE))
            put("22437E", wordPid("APM output current", "A", 2, 20.0, true, CURRENT_A_RANGE))
            // 2243A6 and 2241EC are distinct PIDs in different units/magnitudes (~1000x apart); keep
            // the unit in the name so two isolation-resistance rows aren't indistinguishable.
            put("2243A6", bytePid("HV isolation resistance (kOhm)", "kOhm", 0, 25.0, 0.0, ISOLATION_KOHM_RANGE))
            put("2241EC", wordPid("HV isolation resistance (ohm)", "ohm", 0, 1.0, false, ISOLATION_OHM_RANGE))
            put("2241B1", wordPid("AC compressor commanded power", "W", 0, 1.0, true, HEATER_POWER_RANGE))
            put("2241B3", wordPid("cabin heater commanded power", "W", 0, 1.0, true, HEATER_POWER_RANGE))
            put("2241B5", wordPid("battery heater commanded power", "W", 0, 1.0, true, HEATER_POWER_RANGE))
            put("2282B5", wordPid("AC compressor speed", "rpm", 0, 1.0, false, PUMP_RPM_RANGE))
            put("2282B7", wordPid("AC compressor power", "W", 0, 1.0, true, HEATER_POWER_RANGE))
            put("221141", bytePid("ignition / 12V voltage", "V", 1, 0.1, 0.0, AUX_VOLTAGE_RANGE))
            put("221C47", bytePid("14V setpoint voltage", "V", 1, 0.1, 0.0, AUX_VOLTAGE_RANGE))
            put("221C26", bytePid("inverter temperature 1", "deg C", 0, 1.0, -40.0, TEMP_C_RANGE))
            put("221C28", bytePid("inverter temperature 2", "deg C", 0, 1.0, -40.0, TEMP_C_RANGE))
            put("221C2A", bytePid("inverter temperature 3", "deg C", 0, 1.0, -40.0, TEMP_C_RANGE))
            put("2228CB", bytePid("motor temperature", "deg C", 0, 1.0, -40.0, TEMP_C_RANGE))
            put("22242C", wordPid("brake torque demand", "Nm", 1, 4.0, true, BRAKE_TORQUE_RANGE))
            put("2224B0", Mode22Parser { command, response -> regenActiveValue(response, command) })
            put("224501", wordPid("brake pedal position 1", "mm", 2, 100.0, true, BRAKE_PEDAL_MM_RANGE))
            put("224502", wordPid("brake pedal position 2", "mm", 2, 100.0, true, BRAKE_PEDAL_MM_RANGE))
            put("2240D7", sectionTemperaturePid(1))
            put("2240D9", sectionTemperaturePid(2))
            put("2240DB", sectionTemperaturePid(3))
            put("2240DD", sectionTemperaturePid(4))
            put("2240DF", sectionTemperaturePid(5))
            put("2240E1", sectionTemperaturePid(6))
            put(
                "22C218",
                Mode22Parser { command, response ->
                    ObdVoltCellVoltageDecoder.aggregate(response, command, "average cell voltage", false)
                },
            )
            put("2240D4", wordPid("HD pack current", "A", 2, 20.0, true, CURRENT_A_RANGE))
            put("224531", Mode22Parser { command, response -> chargeLevelValue(response, command) })
            put("228334", bytePid("hv battery displayed soc", "%", 2, 100.0 / 255.0, 0.0))
            put("2241B2", wordPid("battery coolant pump rpm", "rpm", 0, 1.0, true, PUMP_RPM_RANGE))
            put(
                "2241B4",
                Mode22Parser { command, response ->
                    rawByteValue(response, command, "battery coolant valve", "raw")
                },
            )
            put("2241B6", wordPid("battery heater power", "W", 0, 1.0, true, HEATER_POWER_RANGE))
            // 22801E/22801F: outside air temperature uses the GM convention A/2 - 40
            // (range -40..+87.5 C). The earlier 0.125/-5 encoding could only express
            // -5.0..+26.9 C, which is physically impossible for an OAT signal — it could
            // never report a freezing morning or a hot summer day.
            put("22801E", bytePid("outside air temperature raw", "deg C", 1, 0.5, -40.0, TEMP_C_RANGE))
            put("22801F", bytePid("outside air temperature filtered", "deg C", 1, 0.5, -40.0, TEMP_C_RANGE))
        }

    private fun voltByteValue(
        response: String?,
        command: String?,
        scale: Double,
        offset: Double,
    ): Double? {
        val payload = mode22Payload(response, command)
        if (payload == null || payload.isEmpty()) {
            return null
        }
        return payload[0] * scale + offset
    }

    private fun voltWordValue(
        response: String?,
        command: String?,
        divisor: Double,
        signed: Boolean,
    ): Double? = mode22Word(response, command, signed)?.let { it / divisor }

    private fun voltWordLinearValue(
        response: String?,
        command: String?,
        scale: Double,
        offset: Double,
        signed: Boolean,
    ): Double? = mode22Word(response, command, signed)?.let { it * scale + offset }

    private fun mode22Word(
        response: String?,
        command: String?,
        signed: Boolean,
    ): Int? {
        val payload = mode22Payload(response, command)
        if (payload == null || payload.size < 2) {
            return null
        }
        var word = payload[0] * 256 + payload[1]
        if (signed && word > 0x7FFF) {
            word -= 0x10000
        }
        return word
    }

    private fun voltWordPercentValue(
        response: String?,
        command: String?,
    ): Double? {
        val payload = mode22Payload(response, command)
        if (payload == null || payload.size < 2) {
            return null
        }
        val word = payload[0] * 256 + payload[1]
        return word * 100.0 / 65535.0
    }

    private fun chargeModeValue(
        response: String?,
        command: String?,
    ): ParsedPidValue? {
        val payload = mode22Payload(response, command)
        if (payload == null || payload.size < 2) {
            return null
        }
        val word = payload[0] * 256 + payload[1]
        val text =
            if (word == 0) {
                "NOT_CHARGING"
            } else if (word == 0xFFFF) {
                "CHARGING"
            } else {
                "UNKNOWN"
            }
        return ParsedPidValue("charging mode", text, word.toDouble(), "")
    }

    private fun chargeLevelValue(
        response: String?,
        command: String?,
    ): ParsedPidValue? {
        val payload = mode22Payload(response, command)
        if (payload == null || payload.isEmpty()) {
            return null
        }
        val value = payload[0]
        val text =
            when (value) {
                0 -> "NOT_CHARGING"
                1 -> "AC_1"
                2 -> "AC_2"
                else -> "UNKNOWN"
            }
        return ParsedPidValue("charging level", text, value.toDouble(), "")
    }

    private fun regenActiveValue(
        response: String?,
        command: String?,
    ): ParsedPidValue? {
        val payload = mode22Payload(response, command)
        if (payload == null || payload.isEmpty()) {
            return null
        }
        val active = payload[0] > 7
        return ParsedPidValue(
            "regen braking active",
            if (active) "ACTIVE" else "INACTIVE",
            if (active) 1.0 else 0.0,
            "",
        )
    }

    private fun rawByteValue(
        response: String?,
        command: String?,
        name: String?,
        unit: String?,
    ): ParsedPidValue? {
        val payload = mode22Payload(response, command)
        if (payload == null || payload.isEmpty()) {
            return null
        }
        val raw = payload[0]
        return ParsedPidValue(name, "RAW_$raw", raw.toDouble(), unit)
    }
}
