package com.volttracker.obdpoc

import com.volttracker.obdpoc.data.ObdLocalStore
import java.util.Locale

/**
 * Stateless decoding, classification and formatting helpers for the OBD/ELM layer.
 */
object ObdElmDecode {
    @JvmStatic
    fun hasElmPrompt(response: String?): Boolean = response != null && response.indexOf('>') >= 0

    /**
     * True when an ELM reply carries no usable OBD payload for the PID that was asked — the adapter
     * answered "NO DATA" or returned only a prompt / whitespace. The live-poll negative-PID cache
     * uses this to stop re-issuing PIDs the car never answers, and the engine uses it to notice a
     * fully asleep bus.
     *
     * Deliberately narrow: transient "CAN ERROR" / "STOPPED" / "BUFFER FULL" frames all carry
     * hex-ish characters and are NOT treated as no-data, so a momentary bus hiccup can never disable
     * a PID that is actually supported. ("NO DATA" itself contains hex letters A/D, so the literal
     * check must come before the no-hex fallback.)
     */
    @JvmStatic
    fun isNoDataResponse(response: String?): Boolean {
        val cleaned = (response ?: "").uppercase(Locale.US)
        if (cleaned.contains("NO DATA")) {
            return true
        }
        return cleaned.none { it in '0'..'9' || it in 'A'..'F' }
    }

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

    private fun isVinCommand(command: String?): Boolean =
        command != null && command.trim().uppercase(Locale.US) == "0902"

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
            "2241A3" -> "hv battery capacity"
            "2245F9" -> "hv battery capacity fallback"
            "224329" -> "minimum cell voltage"
            "22432A" -> "minimum cell number"
            "22432B" -> "maximum cell voltage"
            "22432C" -> "maximum cell number"
            "22435F" -> "SOC variation"
            "2240E9" -> "pack resistance"
            "22433B" -> "minimum pack voltage"
            "22433C" -> "maximum pack voltage"
            "224349" -> "hv battery max temperature"
            "22434A" -> "hv battery min temperature"
            "22434B" -> "hv battery max-temp module"
            "22434C" -> "hv battery min-temp module"
            "221C43" -> "power electronics coolant loop temperature"
            "2241A4" -> "battery coolant temperature"
            "22433F" -> "minimum SOC limit"
            "2241B0" -> "APM output power"
            "22437E" -> "APM output current"
            "2243A6" -> "HV isolation resistance (kOhm)"
            "2241EC" -> "HV isolation resistance (ohm)"
            "2241B1" -> "AC compressor commanded power"
            "2241B3" -> "cabin heater commanded power"
            "2241B5" -> "battery heater commanded power"
            "2282B5" -> "AC compressor speed"
            "2282B7" -> "AC compressor power"
            "224531" -> "charging level"
            "228334" -> "hv battery displayed soc"
            "2241B2" -> "battery coolant pump rpm"
            "2241B4" -> "battery coolant valve"
            "2241B6" -> "battery heater power"
            "22801E" -> "outside air temperature raw"
            "22801F" -> "outside air temperature filtered"
            "221141" -> "ignition / 12V voltage"
            "221C47" -> "14V setpoint voltage"
            "221C26" -> "inverter temperature 1"
            "221C28" -> "inverter temperature 2"
            "221C2A" -> "inverter temperature 3"
            "2228CB" -> "motor temperature"
            "22242C" -> "brake torque demand"
            "2224B0" -> "regen braking active"
            "224501" -> "brake pedal position 1"
            "224502" -> "brake pedal position 2"
            "2240D7" -> "battery section 1 temperature"
            "2240D9" -> "battery section 2 temperature"
            "2240DB" -> "battery section 3 temperature"
            "2240DD" -> "battery section 4 temperature"
            "2240DF" -> "battery section 5 temperature"
            "2240E1" -> "battery section 6 temperature"
            "2240D4" -> "HD pack current"
            "22C218" -> "average cell voltage"
            "0132" -> "EVAP vapor pressure"
            "22248E" -> "candidate tire pressure front-left"
            "22248F" -> "candidate tire pressure front-right"
            "222490" -> "candidate tire pressure rear-right"
            "222491" -> "candidate tire pressure rear-left"
            "22C901" -> "candidate tire pressures"
            "22C902" -> "candidate tire temperatures"
            "224051", "224052", "224053", "224054" -> "candidate tire receiver slot ${clean.substring(
                clean.length - 1,
            )}"
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
