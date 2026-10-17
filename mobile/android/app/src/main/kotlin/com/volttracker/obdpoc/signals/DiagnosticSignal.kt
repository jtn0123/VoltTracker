package com.volttracker.obdpoc.signals

import org.json.JSONObject
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Recorded diagnostic fields, with fixed export units and the age of their source reading. */
data class DiagnosticSignal(
    val key: String,
    val label: String,
    val column: String,
    val unit: String,
    val ageKey: String,
    val numeric: Boolean = true,
    val maxAgeMs: Long = 120_000L,
) {
    fun age(sample: JSONObject): Long? =
        if (sample.isNull(ageKey)) null else sample.optLong(ageKey, -1L).takeIf { it >= 0 }

    fun value(sample: JSONObject): Double? {
        if (sample.isNull(key) || (age(sample) ?: 0L) > maxAgeMs) return null
        val value = sample.optDouble(key, Double.NaN).takeIf { it.isFinite() } ?: return null
        if (key == "controlModuleVoltage" && value !in 6.0..32.0) return null
        return value
    }

    fun text(sample: JSONObject): String {
        if (numeric) return value(sample)?.toString().orEmpty()
        if (sample.isNull(key) || (age(sample) ?: 0L) > maxAgeMs) return ""
        return sample.optString(key).trim()
    }
}

object DiagnosticSignals {
    val ALL: List<DiagnosticSignal> =
        listOf(
            DiagnosticSignal("egrCoolerTemp1C", "EGR cooler 1", "egr_cooler_1_c", "°C", "egrStaleMs"),
            DiagnosticSignal("egrCoolerTemp2C", "EGR cooler 2", "egr_cooler_2_c", "°C", "egrStaleMs"),
            DiagnosticSignal("egrCommandedPct", "EGR command", "egr_command_pct", "%", "egrStaleMs"),
            DiagnosticSignal("batteryCoolantC", "Battery coolant", "battery_coolant_c", "°C", "batteryCoolantStaleMs"),
            DiagnosticSignal("batteryTemp", "Battery temperature", "battery_temp_c", "°C", "batteryTempStaleMs"),
            DiagnosticSignal(
                "batteryCoolantPumpRpm",
                "Battery coolant pump",
                "battery_coolant_pump_rpm",
                "rpm",
                "batteryCoolantPumpStaleMs",
            ),
            DiagnosticSignal("acPowerW", "A/C power", "ac_power_w", "W", "acPowerStaleMs"),
            DiagnosticSignal("cabinHeatPowerW", "Cabin heat power", "cabin_heat_power_w", "W", "cabinHeatPowerStaleMs"),
            DiagnosticSignal("voltage", "Adapter voltage", "adapter_voltage_v", "V", "voltageStaleMs"),
            DiagnosticSignal(
                "controlModuleVoltage",
                "Controller voltage",
                "controller_voltage_v",
                "V",
                "controlModuleVoltageStaleMs",
            ),
            DiagnosticSignal("aux12vVoltage", "12 V battery voltage", "battery_12v_voltage_v", "V", "aux12vStaleMs"),
            DiagnosticSignal("aux12vCurrentA", "12 V battery current", "battery_12v_current_a", "A", "aux12vStaleMs"),
            DiagnosticSignal("aux12vSocPct", "12 V battery charge", "battery_12v_charge_pct", "%", "aux12vStaleMs"),
            DiagnosticSignal(
                "aux12vCapacityAh",
                "12 V battery capacity",
                "battery_12v_capacity_ah",
                "Ah",
                "aux12vCapacityStaleMs",
                maxAgeMs = 900_000L,
            ),
            DiagnosticSignal(
                "engineStartReason",
                "Engine start reason",
                "engine_start_reason",
                "",
                "engineStartReasonStaleMs",
                numeric = false,
            ),
            DiagnosticSignal(
                "batteryConditioning",
                "Battery conditioning",
                "battery_conditioning",
                "",
                "batteryConditioningStaleMs",
                numeric = false,
            ),
            DiagnosticSignal("chargeState", "Charge state", "charge_state", "", "chargeStateStaleMs", numeric = false),
            DiagnosticSignal(
                "remoteStartReason",
                "Last started by",
                "last_started_by",
                "",
                "remoteStartCountStaleMs",
                numeric = false,
            ),
        )
    val CHARTS: List<DiagnosticSignal> = ALL.filter { it.numeric }
}

/** Streams a complete session, with blank unavailable values and source ages, without route/identity fields. */
class DiagnosticCsv(
    private val writer: Writer,
) {
    private val time =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone =
                TimeZone.getTimeZone("UTC")
        }

    init {
        writer.write(
            (
                listOf("timestamp_utc", "epoch_ms") +
                    DiagnosticSignals.ALL.flatMap { listOf(it.column, "${it.column}_age_ms") }
            ).joinToString(",") +
                "\n",
        )
    }

    fun append(
        atMs: Long,
        sample: JSONObject,
    ) {
        val fields =
            listOf(time.format(Date(atMs)), atMs.toString()) +
                DiagnosticSignals.ALL.flatMap { signal ->
                    listOf(csvCell(signal.text(sample), signal.numeric), signal.age(sample)?.toString().orEmpty())
                }
        writer.write(fields.joinToString(",") + "\n")
    }

    private fun csvCell(
        value: String,
        numeric: Boolean,
    ): String {
        val safe = if (!numeric && value.firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
        return if (safe.any { it in ",\"\r\n" }) "\"${safe.replace("\"", "\"\"")}\"" else safe
    }
}
