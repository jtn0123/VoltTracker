package com.volttracker.obdpoc

import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/**
 * Runs the OBD-II Mode 04 (clear/reset diagnostic trouble codes) sequence on behalf of
 * [ObdPollingEngine]. Mode 04 is a one-shot write: send `"04"`, read the reply, parse one of:
 *
 * - `44` - positive response, DTCs cleared
 * - `7F 04 <NRC>` - negative response
 *
 * Runs on the service IO thread. Command IO is dispatched back through
 * `ObdPollingEngine.sendRecoverableCommand` so the service's `ioLock` and the per-command logging
 * continue to apply.
 */
class ClearDtcRunner(
    private val service: EngineHost,
    private val engine: ObdPollingEngine,
) {
    @Throws(IOException::class)
    fun run() {
        service.broadcastStatus("clearing-codes", "Sending OBD-II Mode 04 (clear DTCs)...", false)
        service.updateNotification("Clearing codes on ${service.activeName}")

        val response = engine.sendRecoverableCommand("04", 6000)
        val compact = compactResponse(response)
        OBDLog.event(
            "ClearDtcRunner",
            "mode04_reply",
            mapOf("raw" to safeForLog(response), "compact" to compact),
        )

        val nrc = extractNegativeResponseCode(compact)
        if (nrc.isNotEmpty()) {
            val detail = describeFailure(nrc)
            service.broadcastStatus("error", detail, true)
            service.updateNotification("Clear-codes failed on ${service.activeName}")
            broadcastResult(false, nrc, compact)
            return
        }

        if (hasPositiveMode04Response(compact)) {
            service.broadcastStatus(
                "codes-cleared",
                "Vehicle DTCs cleared. The check-engine light will go out once the PCM " +
                    "confirms - emissions readiness monitors are now reset.",
                false,
            )
            service.updateNotification("DTCs cleared on ${service.activeName}")
            broadcastResult(true, "ok", compact)
            return
        }

        val detail = describeFailure("")
        service.broadcastStatus("error", detail, true)
        service.updateNotification("Clear-codes failed on ${service.activeName}")
        broadcastResult(false, "", compact)
    }

    private fun broadcastResult(
        ok: Boolean,
        code: String,
        raw: String,
    ) {
        val sample = JSONObject()
        try {
            sample.put("source", "clear-dtc")
            sample.put("connected", true)
            sample.put("adapter", service.activeName)
            sample.put("updatedAt", System.currentTimeMillis())
            sample.put("clearDtcOk", ok)
            sample.put("clearDtcCode", code)
            sample.put("raw", safeForLog(raw))
        } catch (ex: JSONException) {
            Log.w(MainActivity.TAG, "clear-dtc telemetry encode failed", ex)
        }
        service.broadcastTelemetry(sample)
    }

    companion object {
        private fun describeFailure(nrc: String): String =
            when (nrc) {
                "22" ->
                    "Vehicle rejected Mode 04: conditions not correct. Park, ignition on, " +
                        "engine off, then try again."
                "11" -> "Vehicle does not support Mode 04 (clear codes) over OBD-II."
                "12" -> "Vehicle rejected Mode 04 sub-function."
                "13" -> "Vehicle rejected Mode 04: invalid message length."
                "33" -> "Vehicle rejected Mode 04: security access denied."
                "7F",
                "",
                ->
                    "No usable reply from the vehicle for Mode 04. Re-check the adapter and " +
                        "try again."
                else -> "Vehicle rejected Mode 04 (NRC 0x$nrc). Codes were NOT cleared."
            }

        private fun safeForLog(raw: String?): String {
            val trimmed = raw?.trim() ?: return ""
            return if (trimmed.length <= 256) trimmed else trimmed.substring(0, 256)
        }

        @JvmStatic
        fun compactResponse(raw: String?): String =
            raw
                ?.replace('\r', ' ')
                ?.replace('\n', ' ')
                ?.replace('>', ' ')
                ?.trim()
                ?.uppercase(Locale.ROOT)
                ?.replace(Regex("\\s+"), " ")
                ?: ""

        @JvmStatic
        fun extractNegativeResponseCode(compact: String?): String {
            val normalized = (compact ?: "").replace(Regex("[^0-9A-F]"), "")
            val idx = normalized.indexOf("7F04")
            if (idx < 0 || idx + 6 > normalized.length) {
                return ""
            }
            return normalized.substring(idx + 4, idx + 6)
        }

        @JvmStatic
        fun hasPositiveMode04Response(compact: String?): Boolean {
            if (compact.isNullOrEmpty()) {
                return false
            }
            return compact.split(Regex("[^0-9A-F]+")).any { it == "44" }
        }
    }
}
