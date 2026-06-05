package com.volttracker.obdpoc

import java.util.Locale

object ObdProtocol {
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
        return bytes[0]
    }

    @JvmStatic
    fun hasMaxSpeedSentinel(response: String?): Boolean {
        val bytes = mode01Bytes(response, "0D", 1)
        return bytes != null && bytes[0] == 0xFF
    }

    @JvmStatic
    fun parseRpm(response: String?): Float? {
        val bytes = mode01Bytes(response, "0C", 2) ?: return null
        return ((bytes[0] * 256f) + bytes[1]) / 4f
    }

    @JvmStatic
    fun parseCoolantC(response: String?): Int? = mode01Bytes(response, "05", 1)?.let { it[0] - 40 }

    @JvmStatic
    fun parseEngineLoadPct(response: String?): Int? = mode01Bytes(response, "04", 1)?.let { Math.round(it[0] * 100f / 255f) }

    @JvmStatic
    fun parseThrottlePct(response: String?): Int? = mode01Bytes(response, "11", 1)?.let { Math.round(it[0] * 100f / 255f) }

    @JvmStatic
    fun parseAccelPedalPct(response: String?): Int? = mode01Bytes(response, "49", 1)?.let { Math.round(it[0] * 100f / 255f) }

    @JvmStatic
    fun parseStateOfChargePct(response: String?): Int? = mode01Bytes(response, "5B", 1)?.let { Math.round(it[0] * 100f / 255f) }

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
            cleaned.substring(start + 1, end).toFloat()
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
        when (cleanCommand) {
            "ATRV" -> return parseVoltage(response)?.let { value("adapter voltage", it.toDouble(), "V", 1) }
            "010D" -> return parseSpeedKph(response)?.let { value("vehicle speed", it.toDouble(), "km/h", 0) }
            "010C" -> return parseRpm(response)?.let { value("engine rpm", it.toDouble(), "rpm", 0) }
            "0142" -> return parseControlModuleVoltage(response)?.let { value("control module voltage", it, "V", 3) }
            "011F" -> return parseUnsignedMode01Word(response, "1F")?.let { value("engine run time", it.toDouble(), "s", 0) }
            "01A6" -> return parseOdometerKm(response)?.let { value("odometer", it, "km", 1) }
            "0105" -> return parseCoolantC(response)?.let { value("coolant temperature", it.toDouble(), "deg C", 0) }
            "010F" -> return parseOffsetMode01Byte(response, "0F", -40)?.let {
                value("intake air temperature", it.toDouble(), "deg C", 0)
            }
            "012F" -> return parsePercentMode01Byte(response, "2F")?.let { value("fuel level", it.toDouble(), "%", 0) }
            "0104" -> return parseEngineLoadPct(response)?.let { value("engine load", it.toDouble(), "%", 0) }
            "0111" -> return parseThrottlePct(response)?.let { value("throttle position", it.toDouble(), "%", 0) }
            "0149" -> return parseAccelPedalPct(response)?.let {
                value("accelerator pedal position", it.toDouble(), "%", 0)
            }
            "015B" -> return parseStateOfChargePct(response)?.let { value("state of charge", it.toDouble(), "%", 0) }
            "015C" -> return parseOffsetMode01Byte(response, "5C", -40)?.let {
                value("engine oil temperature", it.toDouble(), "deg C", 0)
            }
            "222429" -> return voltWordValue(response, cleanCommand, 64.0, true)?.let {
                value("hv pack voltage", it, "V", 1)
            }
            "222414" -> return voltWordValue(response, cleanCommand, 20.0, true)?.let {
                value("hv pack current", it, "A", 2)
            }
            "22119F", "22119F01" -> return voltByteValue(response, cleanCommand, 100.0 / 255.0, 0.0)?.let {
                value("engine oil life", it, "%", 0)
            }
            "221154" -> return voltByteValue(response, cleanCommand, 1.0, -40.0)?.let {
                value("engine oil temperature", it, "deg C", 0)
            }
            "22203F" -> return voltWordValue(response, cleanCommand, 4.0, false)?.let {
                value("engine torque", it, "Nm", 1)
            }
            "222883" -> return voltWordValue(response, cleanCommand, 20.0, true)?.let {
                value("motor A current", it, "A", 2)
            }
            "222884" -> return voltWordValue(response, cleanCommand, 20.0, true)?.let {
                value("motor B current", it, "A", 2)
            }
            "222885" -> return voltWordValue(response, cleanCommand, 100.0, false)?.let {
                value("motor A voltage", it, "V", 2)
            }
            "222886" -> return voltWordValue(response, cleanCommand, 100.0, false)?.let {
                value("motor B voltage", it, "V", 2)
            }
            "222487" -> return voltWordValue(response, cleanCommand, 100.0, true)?.let {
                value("ev distance this cycle", it, "km", 2)
            }
            "222889" -> return rawByteValue(response, cleanCommand, "prndl state", "")
            "221940", "22194001" -> return voltByteValue(response, cleanCommand, 1.0, -40.0)?.let {
                value("transmission temperature", it, "deg C", 0)
            }
            "22434F" -> return voltByteValue(response, cleanCommand, 1.0, -40.0)?.let {
                value("hv battery temperature", it, "deg C", 0)
            }
            "224368" -> return voltByteValue(response, cleanCommand, 2.0, 0.0)?.let {
                value("charger ac voltage", it, "V", 0)
            }
            "224369" -> return voltByteValue(response, cleanCommand, 0.2, 0.0)?.let {
                value("charger ac current", it, "A", 1)
            }
            "22436B" -> return voltWordValue(response, cleanCommand, 2.0, true)?.let {
                value("charger hv voltage", it, "V", 1)
            }
            "22436C" -> return voltWordValue(response, cleanCommand, 20.0, true)?.let {
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
            "224531" -> return chargeLevelValue(response, cleanCommand)
            "228334" -> return voltByteValue(response, cleanCommand, 100.0 / 255.0, 0.0)?.let {
                value("hv battery displayed soc", it, "%", 2)
            }
            "2241B2" -> return voltWordValue(response, cleanCommand, 1.0, true)?.let {
                value("battery coolant pump rpm", it, "rpm", 0)
            }
            "2241B4" -> return rawByteValue(response, cleanCommand, "battery coolant valve", "raw")
            "2241B6" -> return voltWordValue(response, cleanCommand, 1.0, true)?.let {
                value("battery heater power", it, "W", 0)
            }
            "22801E" -> return voltByteValue(response, cleanCommand, 0.125, -5.0)?.let {
                value("outside air temperature raw", it, "deg C", 1)
            }
            "22801F" -> return voltByteValue(response, cleanCommand, 0.125, -5.0)?.let {
                value("outside air temperature filtered", it, "deg C", 1)
            }
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
        var collectingMultiFrame = false
        for (line in response.split(Regex("[\\r\\n]+"))) {
            val hex = line.uppercase(Locale.US).replace(Regex("[^0-9A-F]"), "")
            var index = hex.indexOf(marker)
            if (index < 0 && collectingMultiFrame) {
                val continuation = continuationPayload(hex)
                if (continuation.isNotEmpty()) {
                    parseDiagnosticPayload(continuation, status, statusLabel, moduleKey, moduleName, cleanHeader, raw, seen, codes)
                }
                continue
            }
            while (index >= 0) {
                val payload = hex.substring(index + marker.length)
                parseDiagnosticPayload(payload, status, statusLabel, moduleKey, moduleName, cleanHeader, raw, seen, codes)
                collectingMultiFrame = true
                index = hex.indexOf(marker, index + marker.length)
            }
        }
        if (codes.isEmpty()) {
            val hex = response.uppercase(Locale.US).replace(Regex("[^0-9A-F]"), "")
            val index = hex.indexOf(marker)
            if (index >= 0) {
                parseDiagnosticPayload(
                    hex.substring(index + marker.length),
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
        val hex = response.uppercase(Locale.US).replace(Regex("[^0-9A-F]"), "")
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

    private fun parseControlModuleVoltage(response: String?): Double? =
        mode01Bytes(response, "42", 2)?.let { ((it[0] * 256.0) + it[1]) / 1000.0 }

    private fun parseUnsignedMode01Word(
        response: String?,
        pid: String,
    ): Int? = mode01Bytes(response, pid, 2)?.let { it[0] * 256 + it[1] }

    private fun parseOdometerKm(response: String?): Double? {
        val bytes = mode01Bytes(response, "A6", 4) ?: return null
        val raw = (bytes[0].toLong() shl 24) or (bytes[1].toLong() shl 16) or (bytes[2].toLong() shl 8) or bytes[3].toLong()
        return raw / 10.0
    }

    private fun parsePercentMode01Byte(
        response: String?,
        pid: String,
    ): Int? = mode01Bytes(response, pid, 1)?.let { Math.round(it[0] * 100f / 255f) }

    private fun parseOffsetMode01Byte(
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

    private fun cleanHeader(header: String?): String = header?.trim()?.uppercase(Locale.US)?.replace(Regex("[^0-9A-F]"), "") ?: ""

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
        try {
            val firstDataByte = hex.substring(pciOffset, pciOffset + 2).toInt(16)
            if ((firstDataByte and 0xF0) != 0x20) {
                return ""
            }
        } catch (ignored: NumberFormatException) {
            return ""
        }
        if (hex.length >= 10 && hex.startsWith("18")) {
            return hex.substring(10)
        }
        if (hex.length >= 5 && hex.startsWith("7")) {
            return hex.substring(5)
        }
        if (hex.length >= 2) {
            try {
                val firstByte = hex.substring(0, 2).toInt(16)
                if (firstByte in 0x21..0x2F) {
                    return hex.substring(2)
                }
            } catch (ignored: NumberFormatException) {
                return ""
            }
        }
        return ""
    }

    private fun isLikelyExtendedCanFrame(hex: String): Boolean = hex.length >= 10 && hex.startsWith("18")

    private fun isLikelyStandardCanFrame(hex: String): Boolean = hex.length >= 5 && hex.startsWith("7")

    private fun parseDiagnosticPayload(
        payload: String?,
        status: String,
        statusLabel: String,
        moduleKey: String,
        moduleName: String,
        header: String,
        rawResponse: String,
        seen: MutableSet<String>,
        output: MutableList<DiagnosticTroubleCode>,
    ) {
        if (payload == null) {
            return
        }
        val limit = payload.length - (payload.length % 4)
        var i = 0
        while (i + 3 < limit) {
            val first: Int
            val second: Int
            try {
                first = payload.substring(i, i + 2).toInt(16)
                second = payload.substring(i + 2, i + 4).toInt(16)
            } catch (ex: NumberFormatException) {
                return
            }
            if (first != 0 || second != 0) {
                val code = decodeDtc(first, second)
                val key = "$moduleKey|$status|$code"
                if (seen.add(key)) {
                    output.add(DiagnosticTroubleCode(code, status, statusLabel, moduleKey, moduleName, header, rawResponse))
                }
            }
            i += 4
        }
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
        var marker = "62$body"
        val hex = response.uppercase(Locale.US).replace(Regex("[^0-9A-F]"), "")
        var index = hex.indexOf(marker)
        if (index < 0 && body.length > 4) {
            marker = "62${body.substring(0, 4)}"
            index = hex.indexOf(marker)
        }
        if (index < 0) {
            return null
        }
        val dataStart = index + marker.length
        val availableChars = hex.length - dataStart
        if (availableChars < 2) {
            return IntArray(0)
        }
        val count = availableChars / 2
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

    private fun format(
        value: Double,
        decimals: Int,
    ): String = String.format(Locale.US, "%.${decimals}f", value)
}
