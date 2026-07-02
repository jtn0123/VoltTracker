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
    fun parseEngineLoadPct(response: String?): Int? = parsePercentMode01Byte(response, "04")

    @JvmStatic
    fun parseThrottlePct(response: String?): Int? = parsePercentMode01Byte(response, "11")

    @JvmStatic
    fun parseAccelPedalPct(response: String?): Int? = parsePercentMode01Byte(response, "49")

    @JvmStatic
    fun parseStateOfChargePct(response: String?): Int? = parsePercentMode01Byte(response, "5B")

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
    ): ParsedPidValue? = ObdVoltMode22Decoder.parse(cleanCommand, response)

    /**
     * Header-aware decode for the dedicated 96-cell probe on the BECM (ATSH7E7). Bypasses the
     * command-string dispatch of [parseKnownValue], which would misroute the cell DIDs that
     * collide with 7E4 meanings of the same command string (e.g. "2241A3").
     */
    @JvmStatic
    fun parseCellVoltageProbe(
        command: String?,
        response: String?,
    ): ParsedPidValue? {
        val cleanCommand = command?.trim()?.uppercase(Locale.US) ?: ""
        return ObdVoltMode22Decoder.parseCellVoltage(cleanCommand, response)
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
            // A single reassembled line can carry two modules' replies concatenated (ATH0). Reset,
            // then SUM each marker's outstanding-pair count instead of overwriting, so a second
            // message can't zero out the first's promised ISO-TP continuation (dropping its codes).
            remainingPairs = 0
            while (index >= 0) {
                remainingPairs +=
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

    // 0C, 1F and 42 are the 2-byte batched Mode-01 PIDs and A6 is the 4-byte odometer; undercounting a
    // trailing multi-byte PID would let the completeness gate accept a frame truncated by a byte (the
    // multi-byte decoders then null).
    private fun mode01PayloadBytes(pid: String): Int =
        if (pid == "0C" || pid == "1F" || pid == "42") {
            2
        } else if (pid == "A6") {
            4
        } else {
            1
        }

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
        return bounded(raw / 10.0, ODOMETER_KM_RANGE)
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

    internal fun mode22Payload(
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

    internal fun value(
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

    // Odometer (mode-01 PID 01A6) is a 32-bit count of 0.1 km. Bound it like every other numeric
    // decoder so a corrupt/partial 4-byte frame can't surface a garbage mileage (the raw field tops
    // out near 4.29e8 km) that the maintenance "next due / overdue" logic would then trust.
    private val ODOMETER_KM_RANGE = Range(0.0, 2_000_000.0)
}
