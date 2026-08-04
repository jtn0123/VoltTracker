package com.volttracker.obdpoc.ui.live

import com.volttracker.obdpoc.ui.VoltAppUiState
import com.volttracker.obdpoc.ui.drive.DriveMode
import com.volttracker.obdpoc.ui.drive.DriveUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.Locale

/**
 * Folds the service's JSON telemetry/status payloads (the same ones the WebView
 * dashboard receives) into the immutable [VoltAppUiState] the Compose screens
 * render. Pure JVM — no Android dependencies — so the mapping and the trace
 * rings are unit-testable without a device.
 *
 * Field names follow the dashboard telemetry contract (see
 * `dashboard-src/js/telemetry.ts` AUTHORITATIVE_READING_KEYS): base OBD keys are
 * typed on [com.volttracker.obdpoc.TelemetryPayload]; enhanced readings ride in
 * extras under the same names the JS consumes.
 */
class LiveUiStateStore {
    private val _state = MutableStateFlow(VoltAppUiState())
    val state: StateFlow<VoltAppUiState> = _state

    private val speedTrace = ArrayDeque<Float>()
    private val powerTrace = ArrayDeque<Float>()
    private val socTrace = ArrayDeque<Float>()
    private var socTraceLastSampleAt = 0L

    /** `setStatus` payload: connection state, adapter, detail. */
    fun onStatus(payload: JSONObject) {
        val stateName = payload.optString("state", "").lowercase(Locale.US)
        val connected = stateName == "connected" || stateName == "demo"
        val transitioning = stateName in TRANSITION_STATES
        val adapter = payload.optString("adapter", "").ifBlank { "--" }
        val label = statusLabel(stateName, adapter)
        _state.value =
            _state.value.let { s ->
                s.copy(
                    drive = s.drive.copy(connected = connected, connecting = transitioning, statusLabel = label),
                    charge = s.charge.copy(connected = connected, statusLabel = label),
                    map = s.map.copy(connected = connected, statusLabel = label),
                    insights = s.insights.copy(connected = connected, statusLabel = label),
                    diag = s.diag.copy(connected = connected, statusLabel = label, adapterLabel = adapter),
                    settings = s.settings.copy(connected = connected, statusLabel = label, adapterLabel = adapter),
                )
            }
    }

    /** One `updateTelemetry` sample: advances the Drive screen and its traces. */
    fun onTelemetry(payload: JSONObject) {
        appendTraces(payload)
        _state.value = _state.value.let { it.copy(drive = mapDrive(it.drive, payload)) }
    }

    /**
     * A `backfillTelemetry` batch, oldest first: rebuilds traces without
     * animating tiles. Replaces ring history — resume replays the whole
     * service snapshot, so appending would duplicate every prior sample.
     */
    fun onTelemetryBackfill(samples: List<JSONObject>) {
        if (samples.isEmpty()) return
        speedTrace.clear()
        powerTrace.clear()
        socTrace.clear()
        socTraceLastSampleAt = 0L
        samples.forEach(::appendTraces)
        samples.lastOrNull()?.let { last ->
            _state.value = _state.value.let { it.copy(drive = mapDrive(it.drive, last)) }
        }
    }

    private fun appendTraces(t: JSONObject) {
        optDouble(t, "speedKph")?.let { push(speedTrace, kmToMi(it).toFloat(), TRACE_CAP) }
        optDouble(t, "powerKw")?.let { push(powerTrace, it.toFloat(), TRACE_CAP) }
        val soc = optDouble(t, "soc")
        val at = t.optLong("updatedAt", 0L)
        // SOC moves slowly; sample it at most every SOC_SAMPLE_MS so the session
        // trace spans a useful window instead of 30 near-identical seconds.
        if (soc != null && (at - socTraceLastSampleAt >= SOC_SAMPLE_MS || socTrace.isEmpty())) {
            push(socTrace, soc.toFloat(), SOC_TRACE_CAP)
            socTraceLastSampleAt = at
        }
    }

    private fun mapDrive(
        current: DriveUiState,
        t: JSONObject,
    ): DriveUiState {
        val vehicleState = t.optString("vehicleState", "")
        val rpm = optDouble(t, "rpm")?.toInt() ?: current.rpm
        val mode =
            when {
                vehicleState == "driving_gas" -> DriveMode.GAS
                vehicleState.isNotBlank() -> DriveMode.EV
                rpm > GAS_RPM_FLOOR -> DriveMode.GAS
                else -> current.mode
            }
        return current.copy(
            powerKw = optDouble(t, "powerKw") ?: current.powerKw,
            speedMph = optDouble(t, "speedKph")?.let { kmToMi(it).toInt() } ?: current.speedMph,
            speedTrace = speedTrace.toList(),
            powerTrace = powerTrace.toList(),
            socTrace = socTrace.toList(),
            socPercent = optDouble(t, "soc") ?: current.socPercent,
            evRangeMiles = optDouble(t, "evDistanceThisCycleKm")?.let(::kmToMi) ?: current.evRangeMiles,
            packTempF = optDouble(t, "batteryTemp")?.let { cToF(it).toInt() } ?: current.packTempF,
            packVolts = optDouble(t, "packVoltage") ?: current.packVolts,
            packAmps = optDouble(t, "packCurrentA") ?: current.packAmps,
            mode = mode,
            rpm = rpm,
            auxVolts = optDouble(t, "controlModuleVoltage") ?: optDouble(t, "voltage") ?: current.auxVolts,
            coolantF = optDouble(t, "coolantC")?.let { cToF(it).toInt() } ?: current.coolantF,
            gpsAccuracyFt = optDouble(t, "accuracyM")?.let { (it * FT_PER_M).toInt() } ?: current.gpsAccuracyFt,
            ambientF = optDouble(t, "outsideTempC")?.let { cToF(it).toInt() } ?: current.ambientF,
            gear = t.optString("prndlState", "").ifBlank { current.gear },
            motorAKw = optDouble(t, "motorAPowerKw") ?: current.motorAKw,
            motorBKw = optDouble(t, "motorBPowerKw") ?: current.motorBKw,
            transTempF = optDouble(t, "transmissionTempC")?.let { cToF(it).toInt() } ?: current.transTempF,
            torqueNm = optDouble(t, "engineTorqueNm")?.toInt() ?: current.torqueNm,
            oilLifePct = optDouble(t, "engineOilLifePct")?.toInt() ?: current.oilLifePct,
        )
    }

    private fun statusLabel(
        stateName: String,
        adapter: String,
    ): String =
        when (stateName) {
            "connected" -> "Live · 1 Hz"
            "demo" -> "Demo"
            "connecting", "initializing", "reconnecting" -> "Connecting…"
            "scanning", "scan-complete" -> "Scanning…"
            else -> if (adapter == "--") "No adapter" else "Idle · $adapter"
        }

    private fun push(
        ring: ArrayDeque<Float>,
        value: Float,
        cap: Int,
    ) {
        ring.addLast(value)
        while (ring.size > cap) ring.removeFirst()
    }

    private fun optDouble(
        payload: JSONObject,
        key: String,
    ): Double? =
        if (payload.has(key) && !payload.isNull(key)) {
            payload.optDouble(key).takeIf { it.isFinite() }
        } else {
            null
        }

    private fun kmToMi(km: Double): Double = km * MI_PER_KM

    private fun cToF(c: Double): Double = c * 9.0 / 5.0 + 32.0

    private companion object {
        val TRANSITION_STATES = setOf("connecting", "initializing", "reconnecting", "scanning", "scan-complete")
        const val TRACE_CAP = 30
        const val SOC_TRACE_CAP = 60
        const val SOC_SAMPLE_MS = 30_000L
        const val GAS_RPM_FLOOR = 300
        const val MI_PER_KM = 0.621371
        const val FT_PER_M = 3.28084
    }
}
