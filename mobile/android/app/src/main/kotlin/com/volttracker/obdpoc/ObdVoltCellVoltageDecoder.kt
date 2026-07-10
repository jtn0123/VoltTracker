package com.volttracker.obdpoc

import com.volttracker.obdpoc.ObdProtocol.ParsedPidValue
import com.volttracker.obdpoc.ObdProtocol.Range
import com.volttracker.obdpoc.ObdProtocol.bounded
import com.volttracker.obdpoc.ObdProtocol.mode22Payload
import com.volttracker.obdpoc.ObdProtocol.value
import java.util.Locale

/** Owns Volt aggregate and 96-cell Mode-22 voltage scaling and DID naming. */
internal object ObdVoltCellVoltageDecoder {
    fun aggregate(
        response: String?,
        command: String,
        name: String,
        validatedScale: Boolean,
    ): ParsedPidValue? =
        voltageValue(
            response,
            command,
            name,
            if (validatedScale) VOLT_CELL_VOLTAGE_SCALE else LEGACY_CELL_VOLTAGE_SCALE,
        )

    fun isProbe(command: String): Boolean {
        val did = mode22Did(command) ?: return false
        return did in 0x4181..0x41E0 || did in 0x4200..0x4240
    }

    fun parseProbe(
        command: String,
        response: String?,
    ): ParsedPidValue? {
        if (!isProbe(command)) return null
        val name = probeName(command)
        return voltageValue(response, command, name, LEGACY_CELL_VOLTAGE_SCALE)
            ?: voltageValue(response, command, name, VOLT_CELL_VOLTAGE_SCALE)
    }

    private fun voltageValue(
        response: String?,
        command: String,
        name: String,
        scale: Double,
    ): ParsedPidValue? {
        val payload = mode22Payload(response, command)
        if (payload == null || payload.size < 2) return null
        val word = payload[0] * 256 + payload[1]
        return bounded(word * scale, CELL_VOLTAGE_RANGE)?.let { value(name, it, "V", 3) }
    }

    private fun probeName(command: String): String {
        val did = mode22Did(command) ?: return "cell voltage"
        // The two tail ranges are alternate encodings of physical cells 32-96.
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

    private fun mode22Did(command: String): Int? {
        val clean = command.trim().uppercase(Locale.US).replace(Regex("[^0-9A-F]"), "")
        if (!clean.startsWith("22") || clean.length < 6) return null
        return clean.substring(2, 6).toIntOrNull(16)
    }

    // Aggregate min/max DIDs are field-calibrated to the Gen2 Volt BECM.
    private const val VOLT_CELL_VOLTAGE_SCALE = 1.0 / 1600.0
    private const val LEGACY_CELL_VOLTAGE_SCALE = 5.0 / 65535.0
    private val CELL_VOLTAGE_RANGE = Range(1.5, 4.5)
}
