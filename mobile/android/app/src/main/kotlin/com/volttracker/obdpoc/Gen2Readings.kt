package com.volttracker.obdpoc

import com.volttracker.obdpoc.engine.EngineHost
import org.json.JSONException
import org.json.JSONObject

/**
 * Puts the gen 2 status reads ([Gen2StatusDecoders], the pump duties and the body module's
 * remote-start counters) into the live sample with their stale ages, and logs the engine's start
 * reason whenever it changes so a drive log says why the engine came on. Reads polled together
 * (the charge self-checks, the timetable, the five charge records, the two remote-start counters)
 * share one stale key.
 */
internal class Gen2Readings(
    private val service: EngineHost,
    private val pidPolling: PidPollingState,
) {
    private var lastStartReason: String? = null

    /** Clears per-session state so the first start reason of a new session is logged again. */
    fun reset() {
        lastStartReason = null
    }

    @Throws(JSONException::class)
    fun appendTo(
        sample: JSONObject,
        now: Long,
    ) {
        putText(sample, "engineStartReason", "engineStartReasonStaleMs", "222439", now)
        putNumber(sample, "auxTransPumpPct", "auxTransPumpStaleMs", "222C05", now)
        putNumber(sample, "peCoolantPumpPct", "peCoolantPumpStaleMs", "224357", now)
        putText(sample, "batteryConditioning", "batteryConditioningStaleMs", "2243DE", now)
        putText(sample, "aux12vMaintenanceCharge", "aux12vMaintenanceStaleMs", "22441E", now)
        putText(sample, "chargeState", "chargeStateStaleMs", "2243AE", now)
        putText(sample, "chargeSystemStatus", "chargeSystemStaleMs", "224532", now)
        putText(sample, "chargeEnableCheck", "chargeSystemStaleMs", "224530", now)
        putText(sample, "chargeControlCheck", "chargeSystemStaleMs", "22452F", now)
        putText(sample, "chargeStartTime", "chargeTimesStaleMs", "2283E1", now)
        putText(sample, "chargeCompleteTime", "chargeTimesStaleMs", "2283E2", now)
        putText(sample, "chargeStartTime120v", "chargeTimesStaleMs", "228375", now)
        putText(sample, "chargeCompleteTime120v", "chargeTimesStaleMs", "228377", now)
        putText(sample, "chargeRecord1", "chargeRecordsStaleMs", "2243CB", now)
        putText(sample, "chargeRecord2", "chargeRecordsStaleMs", "2243CC", now)
        putText(sample, "chargeRecord3", "chargeRecordsStaleMs", "2243CD", now)
        putText(sample, "chargeRecord4", "chargeRecordsStaleMs", "2243CE", now)
        putText(sample, "chargeRecord5", "chargeRecordsStaleMs", "2243CF", now)
        putNumber(sample, "remoteStartSecondsLeft", "remoteStartCountStaleMs", "228537", now)
        putNumber(sample, "remoteStartsLeft", "remoteStartCountStaleMs", "229025", now)
        logStartReasonChange(sample.optString("engineStartReason"))
    }

    private fun logStartReasonChange(reason: String) {
        if (reason.isEmpty() || reason == lastStartReason) return
        lastStartReason = reason
        // A clear field is the engine off (or never started), not a reason worth a log line.
        if (reason != Gen2StatusDecoders.NO_REASON) {
            service.recorder.logEvent("engine_start_reason", "reason", reason)
        }
    }

    @Throws(JSONException::class)
    private fun putText(
        sample: JSONObject,
        key: String,
        staleKey: String,
        command: String,
        now: Long,
    ) {
        val text = parse(command)?.valueText
        if (!text.isNullOrEmpty()) {
            sample.put(key, text)
            pidPolling.putStaleMsIfTracked(sample, staleKey, command, now)
        }
    }

    @Throws(JSONException::class)
    private fun putNumber(
        sample: JSONObject,
        key: String,
        staleKey: String,
        command: String,
        now: Long,
    ) {
        val value = parse(command)?.valueNumeric ?: return
        sample.put(key, Math.round(value))
        pidPolling.putStaleMsIfTracked(sample, staleKey, command, now)
    }

    private fun parse(command: String): ObdProtocol.ParsedPidValue? =
        ObdProtocol.parseKnownValue(command, pidPolling.lastRaw(command))
}
