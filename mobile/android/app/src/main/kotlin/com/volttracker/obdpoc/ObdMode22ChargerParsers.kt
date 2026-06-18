package com.volttracker.obdpoc

/** Mode-22 charger and battery-charge detail decoders split out of [ObdProtocol]. */
internal object ObdMode22ChargerParsers {
    fun parse(
        cleanCommand: String,
        response: String?,
    ): ObdProtocol.ParsedPidValue? =
        when (cleanCommand) {
            "22434F" ->
                byteValue(response, cleanCommand, 1.0, -40.0)
                    ?.let { ObdProtocol.bounded(it, ObdProtocol.TEMP_C_RANGE) }
                    ?.let { ObdProtocol.knownValue("hv battery temperature", it, "deg C", 0) }
            "224368" ->
                byteValue(response, cleanCommand, 2.0, 0.0)
                    ?.let { ObdProtocol.bounded(it, ObdProtocol.AC_VOLTAGE_RANGE) }
                    ?.let { ObdProtocol.knownValue("charger ac voltage", it, "V", 0) }
            "224369" ->
                byteValue(response, cleanCommand, 0.2, 0.0)
                    ?.let { ObdProtocol.bounded(it, ObdProtocol.AC_CURRENT_RANGE) }
                    ?.let { ObdProtocol.knownValue("charger ac current", it, "A", 1) }
            "22436B" ->
                wordValue(response, cleanCommand, 2.0, true)
                    ?.let { ObdProtocol.bounded(it, ObdProtocol.HV_VOLTAGE_RANGE) }
                    ?.let { ObdProtocol.knownValue("charger hv voltage", it, "V", 1) }
            "22436C" ->
                wordValue(response, cleanCommand, 20.0, true)
                    ?.let { ObdProtocol.bounded(it, ObdProtocol.CURRENT_A_RANGE) }
                    ?.let { ObdProtocol.knownValue("charger hv current", it, "A", 2) }
            "224373" -> chargeModeValue(response, cleanCommand)
            "22437D" ->
                wordValue(response, cleanCommand, 0.1, false)
                    ?.let { ObdProtocol.knownValue("last charge energy", it, "Wh", 0) }
            "2243A5" ->
                wordValue(response, cleanCommand, 1.0, false)
                    ?.let { ObdProtocol.knownValue("hv battery charge count", it, "count", 0) }
            "2243AF" ->
                wordPercentValue(response, cleanCommand)
                    ?.let { ObdProtocol.knownValue("hv battery raw soc", it, "%", 2) }
            else -> null
        }

    private fun byteValue(
        response: String?,
        command: String,
        scale: Double,
        offset: Double,
    ): Double? {
        val payload = ObdProtocol.mode22Payload(response, command)
        if (payload == null || payload.isEmpty()) {
            return null
        }
        return payload[0] * scale + offset
    }

    private fun wordValue(
        response: String?,
        command: String,
        divisor: Double,
        signed: Boolean,
    ): Double? {
        val payload = ObdProtocol.mode22Payload(response, command)
        if (payload == null || payload.size < 2) {
            return null
        }
        var word = payload[0] * 256 + payload[1]
        if (signed && word > 0x7FFF) {
            word -= 0x10000
        }
        return word / divisor
    }

    private fun wordPercentValue(
        response: String?,
        command: String,
    ): Double? {
        val payload = ObdProtocol.mode22Payload(response, command)
        if (payload == null || payload.size < 2) {
            return null
        }
        return (payload[0] * 256 + payload[1]) * 100.0 / 65535.0
    }

    private fun chargeModeValue(
        response: String?,
        command: String,
    ): ObdProtocol.ParsedPidValue? {
        val payload = ObdProtocol.mode22Payload(response, command)
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
        return ObdProtocol.ParsedPidValue("charging mode", text, word.toDouble(), "")
    }
}
