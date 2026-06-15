package com.volttracker.obdpoc

import java.util.Locale

object ObdProtocol {
    internal data class Range(
        val min: Double,
        val max: Double,
    ) {
        fun contains(value: Double): Boolean = value >= min && value <= max
    }

    class ParsedPidValue(
        name: String?,
        valueText: String?,
        @JvmField val valueNumeric: Double?,
        unit: String?,
    ) {
        @JvmField val name: String = name ?: ""

        @JvmField val valueText: String = valueText ?: ""

        @JvmField val unit: String = unit ?: ""
    }

    class DiagnosticTroubleCode(
        code: String?,
        status: String?,
        statusLabel: String?,
        moduleKey: String?,
        moduleName: String?,
        header: String?,
        rawResponse: String?,
    ) {
        @JvmField val code: String = code ?: ""

        @JvmField val status: String = status ?: ""

        @JvmField val statusLabel: String = statusLabel ?: ""

        @JvmField val moduleKey: String = moduleKey ?: ""

        @JvmField val moduleName: String = moduleName ?: ""

        @JvmField val header: String = header ?: ""

        @JvmField val rawResponse: String = rawResponse ?: ""
    }

    @JvmStatic
    fun parseSpeedKph(response: String?): Int? {
        val bytes = mode01Bytes(response, "0D", 1)
        if (bytes == null || bytes[0] == 0xFF) {
            return null
        }
        return boundedInt(bytes[0], SPEED_KPH_RANGE)
    }

    @JvmStatic
    fun hasMaxSpeedSentinel(response: String?): Boolean {
        val bytes = mode01Bytes(response, "0D", 1)
        return bytes != null && bytes[0] == 0xFF
    }

    /**
     * True when [response] is a positive frame for [command] whose payload is a recognized
     * "no reading / inactive" sentinel rather than malformed data: an all-zero Mode 22 payload (the
     * ECU answered, but the signal is zero/idle) or the 0xFF speed sentinel on 010D (handled as a
     * charge-transition hint elsewhere). Used to keep the `pid_parse_failed` diagnostic honest -- a
     * sentinel that legitimately yields no value is expected, not a decode bug worth surfacing.
     */
    @JvmStatic
    fun isBenignSentinelResponse(
        command: String?,
        response: String?,
    ): Boolean {
        val cleanCommand = command?.trim()?.uppercase(Locale.US)?.replace(Regex("[^0-9A-F]"), "") ?: return false
        if (cleanCommand == "010D") {
            return hasMaxSpeedSentinel(response)
        }
        if (!cleanCommand.startsWith("22")) {
            return false
        }
        val payload = mode22Payload(response, cleanCommand) ?: return false
        return payload.isNotEmpty() && payload.all { it == 0 }
    }

    @JvmStatic
    fun parseRpm(response: String?): Float? {
        val bytes = mode01Bytes(response, "0C", 2) ?: return null
        return boundedFloat(((bytes[0] * 256f) + bytes[1]) / 4f, RPM_RANGE)
    }

    @JvmStatic
    fun parseCoolantC(response: String?): Int? =
        mode01Bytes(response, "05", 1)?.let { boundedInt(it[0] - 40, TEMP_C_RANGE) }

    @JvmStatic
    fun parseEngineLoadPct(response: String?): Int? =
        mode01Bytes(response, "04", 1)?.let {
            Math.round(
                it[0] * 100f / 255f,
            )
        }

    @JvmStatic
    fun parseThrottlePct(response: String?): Int? =
        mode01Bytes(response, "11", 1)?.let { Math.round(it[0] * 100f / 255f) }

    @JvmStatic
    fun parseAccelPedalPct(response: String?): Int? =
        mode01Bytes(response, "49", 1)?.let {
            Math.round(
                it[0] * 100f / 255f,
            )
        }

    @JvmStatic
    fun parseStateOfChargePct(response: String?): Int? =
        mode01Bytes(response, "5B", 1)?.let {
            Math.round(
                it[0] * 100f / 255f,
            )
        }

    @JvmStatic
    fun buildMode01MultiCommand(pidHex: List<String>): String =
        buildString {
            append("01")
            for (pid in pidHex) {
                append(pid.uppercase(Locale.US))
            }
        }

    @JvmStatic
    fun responseContainsAllMode01Pids(
        response: String?,
        pidHex: List<String>?,
    ): Boolean {
        if (response == null || pidHex == null || pidHex.isEmpty()) {
            return false
        }
        val hex = response.uppercase(Locale.US).replace(Regex("[^0-9A-F]"), "")
        var cursor = 0
        for (pid in pidHex) {
            val cleanPid = (pid ?: "").uppercase(Locale.US)
            val marker = "41$cleanPid"
            val index = hex.indexOf(marker, cursor)
            if (index < 0) {
                return false
            }
            val expectedBytes = mode01PayloadBytes(cleanPid)
            val dataStart = index + marker.length
            if (hex.length < dataStart + expectedBytes * 2) {
                return false
            }
            cursor = dataStart + expectedBytes * 2
        }
        return true
    }

    @JvmStatic
    fun parseVoltage(response: String?): Float? {
        if (response == null) {
            return null
        }
        val cleaned =
            response
                .replace(">", "")
                .replace("\r", "")
                .replace("\n", "")
                .trim()
                .uppercase(Locale.US)
        val end = cleaned.indexOf('V')
        if (end < 0) {
            return null
        }
        var start = end - 1
        while (start >= 0) {
            val c = cleaned[start]
            if ((c in '0'..'9') || c == '.') {
                start--
            } else {
                break
            }
        }
        return try {
            boundedFloat(cleaned.substring(start + 1, end).toFloat(), AUX_VOLTAGE_RANGE)
        } catch (ex: NumberFormatException) {
            null
        }
    }

    @JvmStatic
    fun summarize(response: String?): String =
        response
            ?.replace("\r", " ")
            ?.replace("\n", " ")
            ?.replace(">", "")
            ?.trim() ?: ""

    @JvmStatic
    fun cleanSupportedPids(response: String?): String =
        summarize(response).replace(Regex("(?i)SEARCHING\\.*"), " ").replace(Regex("\\s+"), " ").trim()

    @JvmStatic
    fun parseKnownValue(
        command: String?,
        response: String?,
    ): ParsedPidValue? {
        val cleanCommand = command?.trim()?.uppercase(Locale.US) ?: ""
        return ObdKnownValueParserRegistry.parse(cleanCommand, response)
    }

    internal fun parseKnownValueLegacy(
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
            "22203F" -> return voltWordValue(response, cleanCommand, 4.0, false)?.let {
                value("engine torque", it, "Nm", 1)
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
            "222487" -> return voltWordValue(response, cleanCommand, 100.0, true)?.let {
                value("ev distance this cycle", it, "km", 2)
            }
            "222889" -> return rawByteValue(response, cleanCommand, "prndl state", "")
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
            "224329" -> return cellVoltageValue(response, cleanCommand, "minimum cell voltage", VOLT_CELL_VOLTAGE_SCALE)
            "22432B" -> return cellVoltageValue(response, cleanCommand, "maximum cell voltage", VOLT_CELL_VOLTAGE_SCALE)
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
            "2243A6" -> return voltByteValue(response, cleanCommand, 25.0, 0.0)
                ?.let { bounded(it, ISOLATION_KOHM_RANGE) }
                ?.let {
                    value("HV isolation resistance", it, "kOhm", 0)
                }
            "2241EC" -> return voltWordValue(response, cleanCommand, 1.0, false)
                ?.let { bounded(it, ISOLATION_OHM_RANGE) }
                ?.let {
                    value("HV isolation resistance", it, "ohm", 0)
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
            "22C218" -> return cellVoltageValue(response, cleanCommand, "average cell voltage")
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
        if (isCellVoltageProbe(cleanCommand)) {
            return cellVoltageValue(response, cleanCommand, cellVoltageProbeName(cleanCommand))
        }
        return null
    }

    @JvmStatic
    fun parseDiagnosticTroubleCodes(
        command: String?,
        response: String?,
        header: String?,
    ): List<DiagnosticTroubleCode> {
        val codes = ArrayList<DiagnosticTroubleCode>()
        val marker = positiveDtcMarker(command)
        if (marker == null || response == null) {
            return codes
        }
        val cleanHeader = cleanHeader(header)
        val moduleKey = if (cleanHeader.isEmpty() || cleanHeader == "7DF") "generic-obd" else "header-$cleanHeader"
        val moduleName =
            if (moduleKey == "generic-obd") {
                "ECM / powertrain (generic OBD-II)"
            } else {
                "OBD module header $cleanHeader"
            }
        val status = dtcStatusForCommand(command)
        val statusLabel = dtcStatusLabel(status)
        val raw = summarize(response)
        val seen = HashSet<String>()
        // ATH0 + CAF1 multi-frame replies print in ELM segmented form (a 3-digit total-length
        // line, then "N:"-prefixed data lines). Reassemble those into one contiguous payload
        // before parsing; otherwise the length line and segment indices would pollute the hex.
        val segmented = elmSegmentedHex(response)
        val lines =
            segmented?.let { listOf(it) }
                ?: response.split(Regex("[\\r\\n]+")).map {
                    it.uppercase(Locale.US).replace(NON_HEX, "")
                }
        var sawMarker = false
        var remainingPairs = 0
        for (hex in lines) {
            var index = hex.indexOf(marker)
            if (index < 0) {
                if (sawMarker && remainingPairs > 0) {
                    val continuation = continuationPayload(hex)
                    if (continuation.isNotEmpty()) {
                        remainingPairs -=
                            parseDiagnosticPayload(
                                continuation,
                                remainingPairs,
                                status,
                                statusLabel,
                                moduleKey,
                                moduleName,
                                cleanHeader,
                                raw,
                                seen,
                                codes,
                            )
                    }
                }
                continue
            }
            while (index >= 0) {
                remainingPairs =
                    parseDtcMessageStart(
                        hex.substring(index + marker.length),
                        marker,
                        status,
                        statusLabel,
                        moduleKey,
                        moduleName,
                        cleanHeader,
                        raw,
                        seen,
                        codes,
                    )
                sawMarker = true
                index = hex.indexOf(marker, index + marker.length)
            }
        }
        if (!sawMarker) {
            // The marker may straddle a line break (partial socket reads); retry on joined hex.
            val hex = response.uppercase(Locale.US).replace(NON_HEX, "")
            val index = hex.indexOf(marker)
            if (index >= 0) {
                parseDtcMessageStart(
                    hex.substring(index + marker.length),
                    marker,
                    status,
                    statusLabel,
                    moduleKey,
                    moduleName,
                    cleanHeader,
                    raw,
                    seen,
                    codes,
                )
            }
        }
        return codes
    }

    @JvmStatic
    fun parseVin(response: String?): String? {
        if (response == null) {
            return null
        }
        // With ATH0 + CAF1 the 0902 reply is multi-frame and the ELM prints it in segmented
        // form ("014" total-length line + "N:"-prefixed data lines). Blindly stripping non-hex
        // characters would keep the length line and segment indices in the stream and misalign
        // every byte, so reassemble the segments first when that format is detected.
        val hex = elmSegmentedHex(response) ?: response.uppercase(Locale.US).replace(NON_HEX, "")
        var index = hex.indexOf("490201")
        if (index < 0) {
            index = hex.indexOf("4902")
            if (index < 0) {
                return null
            }
            index += 4
        } else {
            index += 6
        }
        if (index + 34 > hex.length) {
            return null
        }
        val vin = StringBuilder(17)
        for (i in 0 until 17) {
            val byteOffset = index + i * 2
            val value =
                try {
                    hex.substring(byteOffset, byteOffset + 2).toInt(16)
                } catch (ex: NumberFormatException) {
                    return null
                }
            val c = value.toChar()
            val validVinChar = (c in 'A'..'Z' && c != 'I' && c != 'O' && c != 'Q') || c in '0'..'9'
            if (!validVinChar) {
                return null
            }
            vin.append(c)
        }
        return vin.toString()
    }

    @JvmStatic
    fun parsePackPowerKw(
        voltageResponse: String?,
        currentResponse: String?,
    ): Double? {
        val voltage = parseKnownValue("222429", voltageResponse)
        val current = parseKnownValue("222414", currentResponse)
        if (voltage?.valueNumeric == null || current?.valueNumeric == null) {
            return null
        }
        return voltage.valueNumeric * current.valueNumeric / 1000.0
    }

    private fun mode01PayloadBytes(pidHex: String): Int = if (pidHex == "0C") 2 else 1

    internal fun parseControlModuleVoltage(response: String?): Double? =
        mode01Bytes(response, "42", 2)?.let { ((it[0] * 256.0) + it[1]) / 1000.0 }

    internal fun parseUnsignedMode01Word(
        response: String?,
        pid: String,
    ): Int? = mode01Bytes(response, pid, 2)?.let { it[0] * 256 + it[1] }

    internal fun parseSignedMode01Word(
        response: String?,
        pid: String,
    ): Int? =
        parseUnsignedMode01Word(response, pid)?.let {
            if (it > 0x7FFF) it - 0x10000 else it
        }

    internal fun parseOdometerKm(response: String?): Double? {
        val bytes = mode01Bytes(response, "A6", 4) ?: return null
        val raw =
            (bytes[0].toLong() shl 24) or (bytes[1].toLong() shl 16) or (bytes[2].toLong() shl 8) or bytes[3].toLong()
        return raw / 10.0
    }

    internal fun parsePercentMode01Byte(
        response: String?,
        pid: String,
    ): Int? = mode01Bytes(response, pid, 1)?.let { Math.round(it[0] * 100f / 255f) }

    internal fun parseOffsetMode01Byte(
        response: String?,
        pid: String,
        offset: Int,
    ): Int? = mode01Bytes(response, pid, 1)?.let { it[0] + offset }

    private fun mode01Bytes(
        response: String?,
        pid: String,
        expectedBytes: Int,
    ): IntArray? {
        if (response == null) {
            return null
        }
        val hex = response.uppercase(Locale.US).replace(Regex("[^0-9A-F]"), "")
        val marker = "41${pid.uppercase(Locale.US)}"
        val index = hex.indexOf(marker)
        if (index < 0) {
            return null
        }
        val dataStart = index + marker.length
        if (hex.length < dataStart + expectedBytes * 2) {
            return null
        }
        return IntArray(expectedBytes) { i ->
            val offset = dataStart + i * 2
            hex.substring(offset, offset + 2).toInt(16)
        }
    }

    private fun positiveDtcMarker(command: String?): String? =
        when (command?.trim()?.uppercase(Locale.US) ?: "") {
            "03" -> "43"
            "07" -> "47"
            "0A" -> "4A"
            "0202" -> "4202"
            else -> null
        }

    private fun dtcStatusForCommand(command: String?): String =
        when (command?.trim()?.uppercase(Locale.US) ?: "") {
            "07" -> "pending"
            "0A" -> "permanent"
            "0202" -> "freeze-frame"
            else -> "stored"
        }

    private fun dtcStatusLabel(status: String): String =
        when (status) {
            "pending" -> "Pending"
            "permanent" -> "Permanent"
            "freeze-frame" -> "Freeze frame"
            else -> "Stored/current"
        }

    private fun cleanHeader(header: String?): String =
        header?.trim()?.uppercase(Locale.US)?.replace(Regex("[^0-9A-F]"), "") ?: ""

    /**
     * Extracts the DTC payload from an ISO-TP CONSECUTIVE frame (one of the continuation lines that
     * follow the first frame in a multi-frame mode 03/07/0A reply). The CAN addressing format is
     * determined ONCE from the line's header prefix and the resulting PCI offset is used for both
     * validation AND slicing — we never re-sniff the bytes a second time. That matters because a
     * consecutive frame's data can legitimately begin with `0x7x` or `0x18` bytes; keying the slice
     * on the header offset (not on payload content) keeps those frames from being mis-cut.
     *
     * Layout per addressing format (offsets in hex chars):
     *   29-bit extended : header `18..` (8 chars) + CF PCI `2N` (2 chars) -> payload at offset 10.
     *   11-bit standard : CAN ID `7xx`  (3 chars) + CF PCI `2N` (2 chars) -> payload at offset 5.
     *   headers off     :                            CF PCI `2N` (2 chars) -> payload at offset 2.
     * A consecutive frame must carry a CF PCI byte (high nibble `0x2`); anything else (flow control,
     * a negative-response `7F`, padding, junk) fails the gate and yields no payload.
     */
    private fun continuationPayload(hex: String?): String {
        if (hex == null || hex.length < 4) {
            return ""
        }
        val pciOffset =
            if (isLikelyExtendedCanFrame(hex)) {
                8
            } else if (isLikelyStandardCanFrame(hex)) {
                3
            } else {
                0
            }
        if (hex.length < pciOffset + 2) {
            return ""
        }
        val pciByte =
            try {
                hex.substring(pciOffset, pciOffset + 2).toInt(16)
            } catch (ignored: NumberFormatException) {
                return ""
            }
        if ((pciByte and 0xF0) != 0x20) {
            return ""
        }
        return hex.substring(pciOffset + 2)
    }

    private fun isLikelyExtendedCanFrame(hex: String): Boolean = hex.length >= 10 && hex.startsWith("18")

    private fun isLikelyStandardCanFrame(hex: String): Boolean = hex.length >= 5 && hex.startsWith("7")

    /**
     * Reassembles ELM327 "segmented" multi-frame output (the format printed under ATH0 + CAF1):
     * a 3-digit hex total-length line followed by `N:`-prefixed data lines, e.g.
     * ```
     * 014
     * 0: 49 02 01 31 47 31
     * 1: 5A 44 35 53 54 38 4A
     * ```
     * Returns the concatenated data hex (truncated to the announced total length when present),
     * or null when the response is not in segmented form.
     */
    internal fun elmSegmentedHex(response: String?): String? {
        if (response == null || !response.contains(':')) {
            return null
        }
        var totalBytes = -1
        var sawSegment = false
        val data = StringBuilder()
        for (rawLine in response.split(Regex("[\\r\\n]+"))) {
            val line = rawLine.trim().uppercase(Locale.US)
            if (line.isEmpty() || line == ">") {
                continue
            }
            val segment = ELM_SEGMENT_LINE.matchEntire(line)
            if (segment != null) {
                sawSegment = true
                data.append(segment.groupValues[2].replace(NON_HEX, ""))
                continue
            }
            // The ISO-TP total-length header (e.g. "014") precedes the segment lines. Detect it only
            // before any segment is seen: a preface line such as "SEARCHING..." is not a 3-hex token
            // so ELM_LENGTH_LINE skips it, while a stray 3-hex data line appearing AFTER segments
            // started can't be misread as the length (sawSegment is already true).
            if (!sawSegment && ELM_LENGTH_LINE.matches(line)) {
                totalBytes = line.toInt(16)
            }
        }
        if (!sawSegment) {
            return null
        }
        var hex = data.toString()
        if (totalBytes in 0..(hex.length / 2)) {
            hex = hex.substring(0, totalBytes * 2)
        }
        return hex
    }

    /**
     * Parses the head of a positive DTC reply, starting right after the positive-response
     * marker. On ISO 15765-4 (the only protocol this car speaks) the modes 03/07/0A replies
     * carry a DTC-count byte before the first code (`43 02 0133 25A2` = "2 codes: P0133,
     * P25A2"), and mode 02 freeze-frame replies echo the requested frame number
     * (`42 02 00 0133`). Returns the number of DTC pairs still expected in ISO-TP
     * consecutive frames (0 when the message is complete or unparseable).
     */
    private fun parseDtcMessageStart(
        afterMarker: String,
        marker: String,
        status: String,
        statusLabel: String,
        moduleKey: String,
        moduleName: String,
        header: String,
        rawResponse: String,
        seen: MutableSet<String>,
        output: MutableList<DiagnosticTroubleCode>,
    ): Int {
        if (afterMarker.length < 2) {
            return 0
        }
        var payload = afterMarker
        var expectedPairs = -1
        if (marker == "4202") {
            // Freeze frame: skip the echoed frame-number byte; no count byte follows.
            payload = payload.substring(2)
        } else {
            // Modes 03/07/0A: consume the DTC-count byte and use it (bounded by what is
            // actually available) to know how many 2-byte DTC pairs to read. "43 00" means
            // zero stored codes.
            expectedPairs =
                try {
                    payload.substring(0, 2).toInt(16)
                } catch (ex: NumberFormatException) {
                    return 0
                }
            payload = payload.substring(2)
            if (expectedPairs == 0) {
                return 0
            }
        }
        val consumed =
            parseDiagnosticPayload(
                payload,
                expectedPairs,
                status,
                statusLabel,
                moduleKey,
                moduleName,
                header,
                rawResponse,
                seen,
                output,
            )
        return if (expectedPairs > consumed) expectedPairs - consumed else 0
    }

    /**
     * Reads up to [maxPairs] 2-byte DTC pairs from [payload] (negative = unlimited), skipping
     * `0000` padding/terminator pairs. Returns how many pairs were consumed, so multi-frame
     * callers can track how many codes the count byte still owes them.
     */
    private fun parseDiagnosticPayload(
        payload: String?,
        maxPairs: Int,
        status: String,
        statusLabel: String,
        moduleKey: String,
        moduleName: String,
        header: String,
        rawResponse: String,
        seen: MutableSet<String>,
        output: MutableList<DiagnosticTroubleCode>,
    ): Int {
        if (payload == null) {
            return 0
        }
        val limit = payload.length - (payload.length % 4)
        var i = 0
        var consumed = 0
        while (i + 3 < limit && (maxPairs < 0 || consumed < maxPairs)) {
            val first: Int
            val second: Int
            try {
                first = payload.substring(i, i + 2).toInt(16)
                second = payload.substring(i + 2, i + 4).toInt(16)
            } catch (ex: NumberFormatException) {
                return consumed
            }
            if (first != 0 || second != 0) {
                val code = decodeDtc(first, second)
                val key = "$moduleKey|$status|$code"
                if (seen.add(key)) {
                    output.add(
                        DiagnosticTroubleCode(code, status, statusLabel, moduleKey, moduleName, header, rawResponse),
                    )
                }
            }
            consumed++
            i += 4
        }
        return consumed
    }

    private fun decodeDtc(
        first: Int,
        second: Int,
    ): String {
        val family = "PCBU"[(first shr 6) and 0x03]
        val digit1 = (first shr 4) and 0x03
        val digit2 = first and 0x0F
        val digit3 = (second shr 4) and 0x0F
        val digit4 = second and 0x0F
        return String.format(Locale.US, "%c%d%X%X%X", family, digit1, digit2, digit3, digit4)
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
    ): Double? {
        val payload = mode22Payload(response, command)
        if (payload == null || payload.size < 2) {
            return null
        }
        var word = payload[0] * 256 + payload[1]
        if (signed && word > 0x7FFF) {
            word -= 0x10000
        }
        return word / divisor
    }

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

    // Gen2 Volt BECM cell-voltage scale for the aggregate min/max DIDs (224329 / 22432B). The old
    // 5/65535 value was a Bolt placeholder that put every real Volt read at ~0.44 V, so min/max cell
    // voltage (and the derived cell-balance view) decoded to null on every sample. A 0-5 V cell over
    // the full 16-bit range would answer ~0xBD6F for 3.7 V, but this BECM answers ~0x1700 -- under 10%
    // of the range. Field-calibrated to 1/1600 V/count against a pack-voltage/96 anchor (N=118 paired
    // field samples; 95-97% of reads land in 3.0-4.2 V). Cell imbalance (max-min) is near-invariant to
    // small scale error, and CELL_VOLTAGE_RANGE still drops decode garbage. See pid-validation-2026-06-03.md.
    private const val VOLT_CELL_VOLTAGE_SCALE = 1.0 / 1600.0

    // Legacy scale still used by the unvalidated average (22C218) and individual cell-voltage probes,
    // for which we have no field anchor yet (and the probe DIDs answer at a different magnitude). Kept
    // separate so the validated min/max fix doesn't ship an unvalidated change to those paths.
    private const val LEGACY_CELL_VOLTAGE_SCALE = 5.0 / 65535.0

    private fun cellVoltageValue(
        response: String?,
        command: String?,
        name: String,
        scale: Double = LEGACY_CELL_VOLTAGE_SCALE,
    ): ParsedPidValue? =
        voltWordLinearValue(response, command, scale, 0.0, false)
            ?.let { bounded(it, CELL_VOLTAGE_RANGE) }
            ?.let {
                value(name, it, "V", 3)
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

    private fun isCellVoltageProbe(command: String): Boolean {
        val did = mode22Did(command) ?: return false
        return did in 0x4181..0x41E0 || did in 0x4200..0x4240
    }

    private fun cellVoltageProbeName(command: String): String {
        val did = mode22Did(command) ?: return "cell voltage"
        val cellIndex =
            if (did in 0x4181..0x41E0) {
                did - 0x4180
            } else if (did in 0x4200..0x4240) {
                did - 0x4200 + 32
            } else {
                0
            }
        return if (cellIndex > 0) "cell $cellIndex voltage" else "cell voltage"
    }

    private fun mode22Did(command: String?): Int? {
        val clean = command?.trim()?.uppercase(Locale.US)?.replace(Regex("[^0-9A-F]"), "") ?: return null
        if (!clean.startsWith("22") || clean.length < 6) {
            return null
        }
        return try {
            clean.substring(2, 6).toInt(16)
        } catch (ex: NumberFormatException) {
            null
        }
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

    private fun mode22Payload(
        response: String?,
        command: String?,
    ): IntArray? {
        if (response == null || command == null) {
            return null
        }
        val cleanCommand = command.trim().uppercase(Locale.US).replace(Regex("[^0-9A-F]"), "")
        if (!cleanCommand.startsWith("22") || cleanCommand.length < 6) {
            return null
        }
        val body = cleanCommand.substring(2)
        val hex = response.uppercase(Locale.US).replace(Regex("[^0-9A-F]"), "")
        // Preferred: the full echoed DID, reading all following bytes (the all-zeros sentinel check
        // in isBenignSentinelResponse needs the whole payload, so this path stays unbounded). The
        // >2-byte fallback below is BOUNDED: some ECUs echo only the leading 2-byte DID (dropping the
        // request selector byte), so we match the shorter marker — but a short match must not pull a
        // large cross-frame / stale-buffer tail in as data (the old unbounded read could then decode
        // a plausible-but-wrong value).
        val fullIndex = hex.indexOf("62$body")
        val dataStart: Int
        val maxBytes: Int
        if (fullIndex >= 0) {
            dataStart = fullIndex + 2 + body.length
            maxBytes = Int.MAX_VALUE
        } else if (body.length > 4) {
            val shortMarker = "62${body.substring(0, 4)}"
            val shortIndex = hex.indexOf(shortMarker)
            if (shortIndex < 0) return null
            dataStart = shortIndex + shortMarker.length
            maxBytes = MODE22_FALLBACK_MAX_BYTES
        } else {
            return null
        }
        val availableChars = hex.length - dataStart
        if (availableChars < 2) {
            return IntArray(0)
        }
        val count = (availableChars / 2).coerceAtMost(maxBytes)
        return IntArray(count) { i ->
            val offset = dataStart + i * 2
            hex.substring(offset, offset + 2).toInt(16)
        }
    }

    private fun value(
        name: String,
        value: Double?,
        unit: String,
        decimals: Int,
    ): ParsedPidValue? {
        if (value == null) {
            return null
        }
        return ParsedPidValue(name, format(value, decimals), value, unit)
    }

    internal fun knownValue(
        name: String,
        value: Double?,
        unit: String,
        decimals: Int,
    ): ParsedPidValue? = value(name, value, unit, decimals)

    internal fun bounded(
        value: Double,
        range: Range,
    ): Double? = if (range.contains(value)) value else null

    internal fun boundedInt(
        value: Int,
        range: Range,
    ): Int? = if (range.contains(value.toDouble())) value else null

    private fun boundedFloat(
        value: Float,
        range: Range,
    ): Float? = if (range.contains(value.toDouble())) value else null

    private fun format(
        value: Double,
        decimals: Int,
    ): String = String.format(Locale.US, "%.${decimals}f", value)

    private val NON_HEX = Regex("[^0-9A-F]")

    /** Upper bound on bytes read via the mode-22 short-marker fallback (see mode22Payload). */
    private const val MODE22_FALLBACK_MAX_BYTES = 8

    // ELM segmented multi-frame output (ATH0 + CAF1): "N:" data lines + 3-digit length line.
    private val ELM_SEGMENT_LINE = Regex("([0-9A-F]{1,3}):\\s*(.*)")
    private val ELM_LENGTH_LINE = Regex("[0-9A-F]{3}")

    private val SPEED_KPH_RANGE = Range(0.0, 250.0)
    private val RPM_RANGE = Range(0.0, 8_000.0)
    internal val TEMP_C_RANGE = Range(-50.0, 150.0)
    internal val AUX_VOLTAGE_RANGE = Range(0.0, 50.0)
    private val HV_VOLTAGE_RANGE = Range(0.0, 500.0)
    private val AC_VOLTAGE_RANGE = Range(0.0, 280.0)
    private val AC_CURRENT_RANGE = Range(0.0, 80.0)
    private val CURRENT_A_RANGE = Range(-500.0, 500.0)

    // Health bounds reject decode garbage, not bad news: a worn pack below 30 Ah or a faulted
    // cell below 3.0 V is exactly what long-term tracking exists to surface, so these floors sit
    // at physically-possible rather than healthy values.
    private val CAPACITY_AH_RANGE = Range(10.0, 60.0)
    private val CELL_VOLTAGE_RANGE = Range(1.5, 4.5)
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
