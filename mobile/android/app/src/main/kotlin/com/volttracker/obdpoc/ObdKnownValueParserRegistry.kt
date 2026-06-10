package com.volttracker.obdpoc

/**
 * Registry for decoded PID values. Standard adapter/Mode 01 decoders live here; less-common
 * manufacturer-specific Mode 22 decoders still fall through to [ObdProtocol.parseKnownValueLegacy].
 */
internal object ObdKnownValueParserRegistry {
    private fun interface Parser {
        fun parse(response: String?): ObdProtocol.ParsedPidValue?
    }

    private val standardParsers: Map<String, Parser> =
        mapOf(
            "ATRV" to
                Parser { response ->
                    ObdProtocol.parseVoltage(response)?.let {
                        ObdProtocol.knownValue("adapter voltage", it.toDouble(), "V", 1)
                    }
                },
            "010D" to
                Parser { response ->
                    ObdProtocol.parseSpeedKph(response)?.let {
                        ObdProtocol.knownValue("vehicle speed", it.toDouble(), "km/h", 0)
                    }
                },
            "010C" to
                Parser { response ->
                    ObdProtocol.parseRpm(response)?.let {
                        ObdProtocol.knownValue("engine rpm", it.toDouble(), "rpm", 0)
                    }
                },
            "0142" to
                Parser { response ->
                    ObdProtocol
                        .parseControlModuleVoltage(response)
                        ?.let { ObdProtocol.bounded(it, ObdProtocol.AUX_VOLTAGE_RANGE) }
                        ?.let { ObdProtocol.knownValue("control module voltage", it, "V", 3) }
                },
            "011F" to
                Parser { response ->
                    ObdProtocol.parseUnsignedMode01Word(response, "1F")?.let {
                        ObdProtocol.knownValue("engine run time", it.toDouble(), "s", 0)
                    }
                },
            "01A6" to
                Parser { response ->
                    ObdProtocol.parseOdometerKm(response)?.let {
                        ObdProtocol.knownValue("odometer", it, "km", 1)
                    }
                },
            "0105" to
                Parser { response ->
                    ObdProtocol.parseCoolantC(response)?.let {
                        ObdProtocol.knownValue("coolant temperature", it.toDouble(), "deg C", 0)
                    }
                },
            "010F" to
                Parser { response ->
                    ObdProtocol
                        .parseOffsetMode01Byte(response, "0F", -40)
                        ?.let { ObdProtocol.boundedInt(it, ObdProtocol.TEMP_C_RANGE) }
                        ?.let { ObdProtocol.knownValue("intake air temperature", it.toDouble(), "deg C", 0) }
                },
            "012F" to
                Parser { response ->
                    ObdProtocol.parsePercentMode01Byte(response, "2F")?.let {
                        ObdProtocol.knownValue("fuel level", it.toDouble(), "%", 0)
                    }
                },
            "0104" to
                Parser { response ->
                    ObdProtocol.parseEngineLoadPct(response)?.let {
                        ObdProtocol.knownValue("engine load", it.toDouble(), "%", 0)
                    }
                },
            "0111" to
                Parser { response ->
                    ObdProtocol.parseThrottlePct(response)?.let {
                        ObdProtocol.knownValue("throttle position", it.toDouble(), "%", 0)
                    }
                },
            "0149" to
                Parser { response ->
                    ObdProtocol.parseAccelPedalPct(response)?.let {
                        ObdProtocol.knownValue("accelerator pedal position", it.toDouble(), "%", 0)
                    }
                },
            "015B" to
                Parser { response ->
                    ObdProtocol.parseStateOfChargePct(response)?.let {
                        ObdProtocol.knownValue("state of charge", it.toDouble(), "%", 0)
                    }
                },
            "015C" to
                Parser { response ->
                    ObdProtocol
                        .parseOffsetMode01Byte(response, "5C", -40)
                        ?.let { ObdProtocol.boundedInt(it, ObdProtocol.TEMP_C_RANGE) }
                        ?.let { ObdProtocol.knownValue("engine oil temperature", it.toDouble(), "deg C", 0) }
                },
        )

    fun parse(
        cleanCommand: String,
        response: String?,
    ): ObdProtocol.ParsedPidValue? =
        standardParsers[cleanCommand]?.parse(response)
            ?: ObdProtocol.parseKnownValueLegacy(cleanCommand, response)
}
