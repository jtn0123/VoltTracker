package com.volttracker.obdpoc.ui.diag

import com.volttracker.obdpoc.signals.DiagnosticSignals
import com.volttracker.obdpoc.signals.SignalRecording
import com.volttracker.obdpoc.signals.SignalRecordingBuilder
import com.volttracker.obdpoc.signals.SignalSession
import org.json.JSONObject
import kotlin.math.sin

data class SignalHistoryUiState(
    val sessions: List<SignalSession> = emptyList(),
    val recording: SignalRecording? = null,
    val loading: Boolean = true,
    val exporting: Boolean = false,
    val demo: Boolean = false,
    val error: String? = null,
) {
    companion object {
        fun demo(): SignalHistoryUiState {
            val session = SignalSession(-1, 1_777_610_520_000L, 61)
            val builder = SignalRecordingBuilder(session, session.startedAtMs, session.startedAtMs + 300_000L)
            repeat(session.sampleCount) { i ->
                val sample =
                    JSONObject()
                        .put("egrCoolerTemp1C", 130 + 50 * sin(i / 6.0))
                        .put("egrCoolerTemp2C", 78 + i / 6.0)
                        .put("egrCommandedPct", 15 + 10 * sin(i / 5.0))
                        .put("batteryCoolantC", 24 + i / 20.0)
                        .put("batteryTemp", 28 + i / 25.0)
                        .put("batteryCoolantPumpRpm", 1200 + 200 * sin(i / 8.0))
                        .put("acPowerW", 900 + 400 * sin(i / 8.0))
                        .put("cabinHeatPowerW", 0)
                        .put("voltage", 13.9)
                        .put("controlModuleVoltage", 14.0)
                        .put("aux12vVoltage", 14.1)
                        .put("aux12vCurrentA", -1.2 + sin(i / 10.0))
                        .put("aux12vSocPct", 86)
                        .put("aux12vCapacityAh", 61.8)
                DiagnosticSignals.CHARTS.forEach { sample.put(it.ageKey, 0L) }
                builder.append(session.startedAtMs + i * 5_000L, sample)
            }
            return SignalHistoryUiState(listOf(session), builder.build(), loading = false, demo = true)
        }
    }
}
