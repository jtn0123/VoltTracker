package com.volttracker.obdpoc

import com.volttracker.obdpoc.data.ObdLocalStore
import java.util.Locale

/**
 * Stateless decoding, classification and formatting helpers for the OBD/ELM layer.
 */
object ObdElmDecode {
    @JvmStatic
    fun hasElmPrompt(response: String?): Boolean = response != null && response.indexOf('>') >= 0

    @JvmStatic
    fun appendRaw(
        raw: String,
        command: String,
        response: String?,
    ): String = raw + command + ": " + summarizeForStorage(command, response) + "\n"

    @JvmStatic
    fun summarizeForStorage(
        command: String?,
        response: String?,
    ): String {
        val summary = ObdProtocol.summarize(response)
        if (!isVinCommand(command)) {
            return summary
        }
        if (summary.isEmpty()) {
            return ""
        }
        return "[VIN redacted; responseLength=${summary.length}]"
    }

    private fun isVinCommand(command: String?): Boolean = command != null && command.trim().uppercase(Locale.US) == "0902"

    @JvmStatic
    fun pidForCommand(command: String?): String {
        if (command == null) {
            return ""
        }
        val clean = command.trim().uppercase(Locale.US)
        if (clean.startsWith("01") && clean.length >= 4) {
            return clean.substring(2, 4)
        }
        if (clean.startsWith("22") && clean.length >= 6) {
            return clean.substring(2)
        }
        if (clean.startsWith("09") && clean.length >= 4) {
            return clean.substring(2, 4)
        }
        return ""
    }

    @JvmStatic
    fun nameForCommand(command: String?): String {
        if (command == null) {
            return ""
        }
        val clean = command.trim().uppercase(Locale.US)
        return when (clean) {
            "ATRV" -> "adapter voltage"
            "010D" -> "vehicle speed"
            "010C" -> "engine rpm"
            "0105" -> "coolant temperature"
            "010F" -> "intake air temperature"
            "0104" -> "engine load"
            "0111" -> "throttle position"
            "0149" -> "accelerator pedal position"
            "0142" -> "control module voltage"
            "011F" -> "engine run time"
            "01A6" -> "odometer"
            "012F" -> "fuel level"
            "015C" -> "engine oil temperature"
            "22119F", "22119F01" -> "engine oil life"
            "221154" -> "engine oil temperature"
            "22203F" -> "engine torque"
            "015B" -> "state of charge"
            "222429" -> "hv pack voltage"
            "222414" -> "hv pack current"
            "222883" -> "motor A current"
            "222884" -> "motor B current"
            "222885" -> "motor A voltage"
            "222886" -> "motor B voltage"
            "222487" -> "ev distance this cycle"
            "222889" -> "prndl state"
            "221940", "22194001" -> "transmission temperature"
            "22434F" -> "hv battery temperature"
            "224368" -> "charger ac voltage"
            "224369" -> "charger ac current"
            "22436B" -> "charger hv voltage"
            "22436C" -> "charger hv current"
            "224373" -> "charging mode"
            "22437D" -> "last charge energy"
            "2243A5" -> "hv battery charge count"
            "2243AF" -> "hv battery raw soc"
            "224531" -> "charging level"
            "228334" -> "hv battery displayed soc"
            "2241B2" -> "battery coolant pump rpm"
            "2241B4" -> "battery coolant valve"
            "2241B6" -> "battery heater power"
            "22801E" -> "outside air temperature raw"
            "22801F" -> "outside air temperature filtered"
            "22248E" -> "candidate tire pressure front-left"
            "22248F" -> "candidate tire pressure front-right"
            "222490" -> "candidate tire pressure rear-right"
            "222491" -> "candidate tire pressure rear-left"
            "22C901" -> "candidate tire pressures"
            "22C902" -> "candidate tire temperatures"
            "224051", "224052", "224053", "224054" -> "candidate tire receiver slot ${clean.substring(clean.length - 1)}"
            "0902" -> "vin"
            else -> ""
        }
    }

    @JvmStatic
    fun appendProbeLine(
        raw: StringBuilder,
        label: String,
        value: String?,
    ) {
        raw
            .append(label)
            .append(": ")
            .append(value ?: "")
            .append('\n')
    }

    @JvmStatic
    fun tail(
        value: String?,
        maxLength: Int,
    ): String? {
        if (value == null || value.length <= maxLength) {
            return value
        }
        return value.substring(value.length - maxLength)
    }

    /** Exponential backoff between OBD reconnect attempts, capped at 30 s. */
    @JvmStatic
    fun reconnectBackoffMs(attempt: Int): Long {
        if (attempt < 1) {
            return 0L
        }
        val base = 2000L * (1L shl minOf(attempt - 1, 4))
        return minOf(30000L, base)
    }

    @JvmStatic
    fun initialConnectBackoffMs(attempt: Int): Long {
        if (attempt < 1) {
            return 0L
        }
        return minOf(3000L, 500L * attempt)
    }

    @JvmStatic
    fun finishStatusFor(state: String?): String {
        if (state == "error" || state == "blocked") {
            return ObdLocalStore.STATUS_ERROR
        }
        if (state == "connected" || state == "scanning" || state == "scan-complete") {
            return ObdLocalStore.STATUS_COMPLETE
        }
        return ObdLocalStore.STATUS_DISCONNECTED
    }

    @JvmStatic
    fun friendlyConnectionMessage(ex: Exception): String {
        val message = safeMessage(ex).lowercase(Locale.US)
        if (message.contains("socket might closed") ||
            message.contains("timeout") ||
            message.contains("read failed")
        ) {
            return "Adapter serial channel did not open. Make sure the car is awake, close other OBD apps, then retry."
        }
        if (message.contains("permission")) {
            return "Bluetooth permission is missing. Grant permissions, then retry."
        }
        return "OBD connection failed: ${safeMessage(ex)}"
    }

    @JvmStatic
    fun safeMessage(ex: Exception): String {
        val message = ex.message
        if (message == null || message.trim().isEmpty()) {
            return ex.javaClass.simpleName
        }
        return message
    }

    @JvmStatic
    fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0
}
