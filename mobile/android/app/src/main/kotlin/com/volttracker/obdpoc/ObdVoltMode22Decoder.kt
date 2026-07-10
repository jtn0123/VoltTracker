package com.volttracker.obdpoc

import com.volttracker.obdpoc.ObdProtocol.AUX_VOLTAGE_RANGE
import com.volttracker.obdpoc.ObdProtocol.ParsedPidValue
import com.volttracker.obdpoc.ObdProtocol.Range
import com.volttracker.obdpoc.ObdProtocol.TEMP_C_RANGE
import com.volttracker.obdpoc.ObdProtocol.bounded
import com.volttracker.obdpoc.ObdProtocol.mode22Payload
import com.volttracker.obdpoc.ObdProtocol.value

/**
 * GM/Volt manufacturer-specific Mode-22 PID decoder, extracted from [ObdProtocol]. Owns the
 * Mode-22 dispatch table ([parse]) and its Volt-specific byte/word/cell/charge decode helpers and
 * range bounds. The shared numeric primitives (value/bounded/mode22Payload, the generic ranges)
 * stay on [ObdProtocol]; [ObdProtocol.parseKnownValueLegacy] delegates here.
 */
internal object ObdVoltMode22Decoder {
    fun parse(
        cleanCommand: String,
        response: String?,
    ): ParsedPidValue? {
        // Standard adapter/Mode-01 PIDs are handled in ObdKnownValueParserRegistry.standardParsers,
        // which runs BEFORE this fallback — so only manufacturer-specific Mode-22 PIDs reach here.
        when (cleanCommand) {
            // ---- GM/Volt mode-22 (manufacturer-specific) PIDs --------------------------------
            // NOT OBD-II standard: the PID list and the scale/offset constants below are
            // reverse-engineered from community sources and are NOT validated against this car's
            // real readings — treat the decoded values as approximate until confirmed on-car.
            // Sources + open questions: docs/volt-pid-research-2026-05-20.md,
            // docs/pid-validation-2026-06-03.md, docs/volt-pids-community-sheet.csv.
            // Decode helpers (each value(name, …, unit, decimals) labels the signal + unit):
            //   voltByteValue(resp, cmd, scale, offset)   = payload[0] * scale + offset      (1 byte)
            //   voltWordValue(resp, cmd, divisor, signed) = word / divisor, word = 2 bytes big-endian,
            //                                               two's-complement when signed = true
            "222429" -> return voltWordValue(
                response,
                cleanCommand,
                64.0,
                true,
            )?.let { bounded(it, HV_VOLTAGE_RANGE) }?.let {
                value("hv pack voltage", it, "V", 1)
            }
            "222414" -> return voltWordValue(
                response,
                cleanCommand,
                20.0,
                true,
            )?.let { bounded(it, CURRENT_A_RANGE) }?.let {
                value("hv pack current", it, "A", 2)
            }
            "22119F", "22119F01" -> return voltByteValue(response, cleanCommand, 100.0 / 255.0, 0.0)?.let {
                value("engine oil life", it, "%", 0)
            }
            "221154" -> return voltByteValue(
                response,
                cleanCommand,
                1.0,
                -40.0,
            )?.let { bounded(it, TEMP_C_RANGE) }?.let {
                value("engine oil temperature", it, "deg C", 0)
            }
            "222883" -> return voltWordValue(
                response,
                cleanCommand,
                20.0,
                true,
            )?.let { bounded(it, CURRENT_A_RANGE) }?.let {
                value("motor A current", it, "A", 2)
            }
            "222884" -> return voltWordValue(
                response,
                cleanCommand,
                20.0,
                true,
            )?.let { bounded(it, CURRENT_A_RANGE) }?.let {
                value("motor B current", it, "A", 2)
            }
            "222885" -> return voltWordValue(
                response,
                cleanCommand,
                100.0,
                false,
            )?.let { bounded(it, HV_VOLTAGE_RANGE) }?.let {
                value("motor A voltage", it, "V", 2)
            }
            "222886" -> return voltWordValue(
                response,
                cleanCommand,
                100.0,
                false,
            )?.let { bounded(it, HV_VOLTAGE_RANGE) }?.let {
                value("motor B voltage", it, "V", 2)
            }
            // Distance-this-cycle is non-negative: decode unsigned (a high bit must not flip it
            // negative) and bound it.
            "222487" -> return voltWordValue(response, cleanCommand, 100.0, false)?.let {
                // Bound 0..(0xFFFF/100) km: non-negative and within an unsigned 16-bit word.
                value("ev distance this cycle", bounded(it, Range(0.0, 655.35)), "km", 2)
            }
            // PRNDL is a clean gear code; emit the number, not rawByteValue's "RAW_<n>".
            "222889" -> return value("prndl state", voltByteValue(response, cleanCommand, 1.0, 0.0), "", 0)
            "221940", "22194001" -> return voltByteValue(response, cleanCommand, 1.0, -40.0)
                ?.let {
                    bounded(it, TEMP_C_RANGE)
                }?.let {
                    value("transmission temperature", it, "deg C", 0)
                }
            "22434F" -> return voltByteValue(
                response,
                cleanCommand,
                1.0,
                -40.0,
            )?.let { bounded(it, TEMP_C_RANGE) }?.let {
                value("hv battery temperature", it, "deg C", 0)
            }
            "224368" -> return voltByteValue(
                response,
                cleanCommand,
                2.0,
                0.0,
            )?.let { bounded(it, AC_VOLTAGE_RANGE) }?.let {
                value("charger ac voltage", it, "V", 0)
            }
            "224369" -> return voltByteValue(
                response,
                cleanCommand,
                0.2,
                0.0,
            )?.let { bounded(it, AC_CURRENT_RANGE) }?.let {
                value("charger ac current", it, "A", 1)
            }
            "22436B" -> return voltWordValue(
                response,
                cleanCommand,
                2.0,
                true,
            )?.let { bounded(it, HV_VOLTAGE_RANGE) }?.let {
                value("charger hv voltage", it, "V", 1)
            }
            "22436C" -> return voltWordValue(
                response,
                cleanCommand,
                20.0,
                true,
            )?.let { bounded(it, CURRENT_A_RANGE) }?.let {
                value("charger hv current", it, "A", 2)
            }
            "224373" -> return chargeModeValue(response, cleanCommand)
            "22437D" -> return voltWordValue(response, cleanCommand, 0.1, false)?.let {
                value("last charge energy", it, "Wh", 0)
            }
            "2243A5" -> return voltWordValue(response, cleanCommand, 1.0, false)?.let {
                value("hv battery charge count", it, "count", 0)
            }
            "2243AF" -> return voltWordPercentValue(response, cleanCommand)?.let {
                value("hv battery raw soc", it, "%", 2)
            }
            "2241A3" -> return voltWordValue(response, cleanCommand, 10.0, false)
                ?.let { bounded(it, CAPACITY_AH_RANGE) }
                ?.let {
                    value("HV battery capacity", it, "Ah", 1)
                }
            "2245F9" -> return voltWordValue(response, cleanCommand, 100.0, false)
                ?.let { bounded(it, CAPACITY_AH_RANGE) }
                ?.let {
                    value("HV battery capacity fallback", it, "Ah", 2)
                }
            "224329" -> return ObdVoltCellVoltageDecoder.aggregate(response, cleanCommand, "minimum cell voltage", true)
            "22432B" -> return ObdVoltCellVoltageDecoder.aggregate(response, cleanCommand, "maximum cell voltage", true)
            "22432A" -> return voltByteValue(response, cleanCommand, 1.0, 0.0)
                ?.let { bounded(it, CELL_NUMBER_RANGE) }
                ?.let {
                    value("minimum cell number", it, "", 0)
                }
            "22432C" -> return voltByteValue(response, cleanCommand, 1.0, 0.0)
                ?.let { bounded(it, CELL_NUMBER_RANGE) }
                ?.let {
                    value("maximum cell number", it, "", 0)
                }
            "22435F" -> return voltByteValue(response, cleanCommand, 1.0 / 2.55, 0.0)
                ?.let { bounded(it, PERCENT_RANGE) }
                ?.let {
                    value("SOC variation", it, "%", 1)
                }
            "2240E9" -> return voltWordValue(response, cleanCommand, 2.0, false)
                ?.let { bounded(it, PACK_RESISTANCE_RANGE) }
                ?.let {
                    value("pack resistance", it, "ohm", 1)
                }
            "22433B" -> return voltWordLinearValue(response, cleanCommand, 0.52, 0.0, false)
                ?.let { bounded(it, HV_VOLTAGE_RANGE) }
                ?.let {
                    value("minimum pack voltage", it, "V", 1)
                }
            "22433C" -> return voltWordLinearValue(response, cleanCommand, 0.52, 0.0, false)
                ?.let { bounded(it, HV_VOLTAGE_RANGE) }
                ?.let {
                    value("maximum pack voltage", it, "V", 1)
                }
            "224349" -> return voltByteValue(response, cleanCommand, 1.0, -40.0)
                ?.let { bounded(it, TEMP_C_RANGE) }
                ?.let {
                    value("HV battery max temperature", it, "deg C", 0)
                }
            "22434A" -> return voltByteValue(response, cleanCommand, 1.0, -40.0)
                ?.let { bounded(it, TEMP_C_RANGE) }
                ?.let {
                    value("HV battery min temperature", it, "deg C", 0)
                }
            "22434B" -> return voltByteValue(response, cleanCommand, 1.0, 0.0)
                ?.let { bounded(it, CELL_NUMBER_RANGE) }
                ?.let {
                    value("HV battery max-temp module", it, "", 0)
                }
            "22434C" -> return voltByteValue(response, cleanCommand, 1.0, 0.0)
                ?.let { bounded(it, CELL_NUMBER_RANGE) }
                ?.let {
                    value("HV battery min-temp module", it, "", 0)
                }
            "221C43" -> return voltByteValue(response, cleanCommand, 1.0, -40.0)
                ?.let { bounded(it, TEMP_C_RANGE) }
                ?.let {
                    value("power electronics coolant loop temperature", it, "deg C", 0)
                }
            "2241A4" -> return voltByteValue(response, cleanCommand, 1.0, -40.0)
                ?.let { bounded(it, TEMP_C_RANGE) }
                ?.let {
                    value("battery coolant temperature", it, "deg C", 0)
                }
            "22433F" -> return voltByteValue(response, cleanCommand, 1.0 / 2.55, 0.0)
                ?.let { bounded(it, PERCENT_RANGE) }
                ?.let {
                    value("minimum SOC limit", it, "%", 1)
                }
            "2241B0" -> return voltWordValue(response, cleanCommand, 16.0, false)
                ?.let { bounded(it, HEATER_POWER_RANGE) }
                ?.let {
                    value("APM output power", it, "W", 1)
                }
            "22437E" -> return voltWordValue(response, cleanCommand, 20.0, true)
                ?.let { bounded(it, CURRENT_A_RANGE) }
                ?.let {
                    value("APM output current", it, "A", 2)
                }
            // 2243A6 and 2241EC are distinct PIDs in different units/magnitudes (~1000x apart); keep
            // the unit in the name so two isolation-resistance rows aren't indistinguishable.
            "2243A6" -> return voltByteValue(response, cleanCommand, 25.0, 0.0)
                ?.let { bounded(it, ISOLATION_KOHM_RANGE) }
                ?.let {
                    value("HV isolation resistance (kOhm)", it, "kOhm", 0)
                }
            "2241EC" -> return voltWordValue(response, cleanCommand, 1.0, false)
                ?.let { bounded(it, ISOLATION_OHM_RANGE) }
                ?.let {
                    value("HV isolation resistance (ohm)", it, "ohm", 0)
                }
            "2241B1" -> return voltWordValue(response, cleanCommand, 1.0, true)
                ?.let { bounded(it, HEATER_POWER_RANGE) }
                ?.let {
                    value("AC compressor commanded power", it, "W", 0)
                }
            "2241B3" -> return voltWordValue(response, cleanCommand, 1.0, true)
                ?.let { bounded(it, HEATER_POWER_RANGE) }
                ?.let {
                    value("cabin heater commanded power", it, "W", 0)
                }
            "2241B5" -> return voltWordValue(response, cleanCommand, 1.0, true)
                ?.let { bounded(it, HEATER_POWER_RANGE) }
                ?.let {
                    value("battery heater commanded power", it, "W", 0)
                }
            "2282B5" -> return voltWordValue(response, cleanCommand, 1.0, false)
                ?.let { bounded(it, PUMP_RPM_RANGE) }
                ?.let {
                    value("AC compressor speed", it, "rpm", 0)
                }
            "2282B7" -> return voltWordValue(response, cleanCommand, 1.0, true)
                ?.let { bounded(it, HEATER_POWER_RANGE) }
                ?.let {
                    value("AC compressor power", it, "W", 0)
                }
            "221141" -> return voltByteValue(response, cleanCommand, 0.1, 0.0)
                ?.let { bounded(it, AUX_VOLTAGE_RANGE) }
                ?.let {
                    value("ignition / 12V voltage", it, "V", 1)
                }
            "221C47" -> return voltByteValue(response, cleanCommand, 0.1, 0.0)
                ?.let { bounded(it, AUX_VOLTAGE_RANGE) }
                ?.let {
                    value("14V setpoint voltage", it, "V", 1)
                }
            "221C26" -> return voltByteValue(response, cleanCommand, 1.0, -40.0)
                ?.let { bounded(it, TEMP_C_RANGE) }
                ?.let {
                    value("inverter temperature 1", it, "deg C", 0)
                }
            "221C28" -> return voltByteValue(response, cleanCommand, 1.0, -40.0)
                ?.let { bounded(it, TEMP_C_RANGE) }
                ?.let {
                    value("inverter temperature 2", it, "deg C", 0)
                }
            "221C2A" -> return voltByteValue(response, cleanCommand, 1.0, -40.0)
                ?.let { bounded(it, TEMP_C_RANGE) }
                ?.let {
                    value("inverter temperature 3", it, "deg C", 0)
                }
            "2228CB" -> return voltByteValue(response, cleanCommand, 1.0, -40.0)
                ?.let { bounded(it, TEMP_C_RANGE) }
                ?.let {
                    value("motor temperature", it, "deg C", 0)
                }
            "22242C" -> return voltWordValue(response, cleanCommand, 4.0, true)
                ?.let { bounded(it, BRAKE_TORQUE_RANGE) }
                ?.let {
                    value("brake torque demand", it, "Nm", 1)
                }
            "2224B0" -> return regenActiveValue(response, cleanCommand)
            "224501" -> return voltWordValue(response, cleanCommand, 100.0, true)
                ?.let { bounded(it, BRAKE_PEDAL_MM_RANGE) }
                ?.let {
                    value("brake pedal position 1", it, "mm", 2)
                }
            "224502" -> return voltWordValue(response, cleanCommand, 100.0, true)
                ?.let { bounded(it, BRAKE_PEDAL_MM_RANGE) }
                ?.let {
                    value("brake pedal position 2", it, "mm", 2)
                }
            "2240D7", "2240D9", "2240DB", "2240DD", "2240DF", "2240E1" ->
                return cellSectionTemperatureValue(response, cleanCommand)
            "22C218" -> return ObdVoltCellVoltageDecoder.aggregate(
                response,
                cleanCommand,
                "average cell voltage",
                false,
            )
            "2240D4" -> return voltWordValue(response, cleanCommand, 20.0, true)
                ?.let { bounded(it, CURRENT_A_RANGE) }
                ?.let {
                    value("HD pack current", it, "A", 2)
                }
            "224531" -> return chargeLevelValue(response, cleanCommand)
            "228334" -> return voltByteValue(response, cleanCommand, 100.0 / 255.0, 0.0)?.let {
                value("hv battery displayed soc", it, "%", 2)
            }
            "2241B2" -> return voltWordValue(
                response,
                cleanCommand,
                1.0,
                true,
            )?.let { bounded(it, PUMP_RPM_RANGE) }?.let {
                value("battery coolant pump rpm", it, "rpm", 0)
            }
            "2241B4" -> return rawByteValue(response, cleanCommand, "battery coolant valve", "raw")
            "2241B6" -> return voltWordValue(
                response,
                cleanCommand,
                1.0,
                true,
            )?.let { bounded(it, HEATER_POWER_RANGE) }?.let {
                value("battery heater power", it, "W", 0)
            }
            // 22801E/22801F: outside air temperature uses the GM convention A/2 - 40
            // (range -40..+87.5 C). The earlier 0.125/-5 encoding could only express
            // -5.0..+26.9 C, which is physically impossible for an OAT signal — it could
            // never report a freezing morning or a hot summer day.
            "22801E" -> return voltByteValue(
                response,
                cleanCommand,
                0.5,
                -40.0,
            )?.let { bounded(it, TEMP_C_RANGE) }?.let {
                value("outside air temperature raw", it, "deg C", 1)
            }
            "22801F" -> return voltByteValue(
                response,
                cleanCommand,
                0.5,
                -40.0,
            )?.let { bounded(it, TEMP_C_RANGE) }?.let {
                value("outside air temperature filtered", it, "deg C", 1)
            }
        }
        if (ObdVoltCellVoltageDecoder.isProbe(cleanCommand)) {
            return parseCellVoltage(cleanCommand, response)
        }
        return null
    }

    /**
     * Header-aware decode for the dedicated 96-cell probe: the generic [parse] dispatch is keyed by
     * command string only, so on the BECM (ATSH7E7) a cell DID like 0x41A3 (cell 35) would be
     * swallowed by the 7E4 meaning of the same string ("2241A3" = pack capacity). The cell probe
     * runner calls this directly to skip the command table.
     *
     * No field anchor exists yet for the per-cell DIDs, so accept either known encoding. The
     * scales' plausible word ranges are disjoint (a 1.5-4.5 V cell needs word 19661-58982 at
     * 5/65535 but 2400-7200 at 1/1600), so trying both is unambiguous.
     */
    internal fun parseCellVoltage(
        cleanCommand: String,
        response: String?,
    ): ParsedPidValue? = ObdVoltCellVoltageDecoder.parseProbe(cleanCommand, response)

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

    private fun cellSectionTemperatureValue(
        response: String?,
        command: String,
    ): ParsedPidValue? =
        voltByteValue(response, command, 1.0, -40.0)
            ?.let { bounded(it, TEMP_C_RANGE) }
            ?.let {
                value("battery section ${cellSectionIndex(command)} temperature", it, "deg C", 0)
            }

    private fun cellSectionIndex(command: String): Int =
        when (command) {
            "2240D7" -> 1
            "2240D9" -> 2
            "2240DB" -> 3
            "2240DD" -> 4
            "2240DF" -> 5
            "2240E1" -> 6
            else -> 0
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
}
