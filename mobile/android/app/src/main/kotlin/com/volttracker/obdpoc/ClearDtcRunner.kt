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
        // NRC 0x78 (responsePending) is NOT terminal: the ECU is telling us it needs more time and a
        // positive "44" typically follows in the same reply. Fall through to the positive-response
        // check so a "7F 04 78 … 44" sequence is correctly reported as cleared, not failed.
        if (nrc.isNotEmpty() && nrc != NRC_RESPONSE_PENDING) {
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

        // No positive 44 arrived. Carry the NRC through (notably 0x78 responsePending, which
        // reached here because it was treated as non-terminal above but no reply ever followed) so
        // the message distinguishes "ECU asked for more time but never confirmed" from a bare
        // no-reply failure.
        val detail = describeFailure(nrc)
        service.broadcastStatus("error", detail, true)
        service.updateNotification("Clear-codes failed on ${service.activeName}")
        broadcastResult(false, nrc, compact)
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
        // ISO 14229 NRC 0x78 (requestCorrectlyReceived-ResponsePending): the ECU acknowledges the
        // request but needs more time; a real positive reply follows. Treated as non-terminal.
        private const val NRC_RESPONSE_PENDING = "78"

        private fun describeFailure(nrc: String): String =
            when (nrc) {
                "22" ->
                    "Vehicle rejected Mode 04: conditions not correct. Park, ignition on, " +
                        "engine off, then try again."
                "11" -> "Vehicle does not support Mode 04 (clear codes) over OBD-II."
                "12" -> "Vehicle rejected Mode 04 sub-function."
                "13" -> "Vehicle rejected Mode 04: invalid message length."
                "33" -> "Vehicle rejected Mode 04: security access denied."
                NRC_RESPONSE_PENDING ->
                    "Vehicle is still processing the clear request (response pending) and never " +
                        "confirmed. Wait a moment and try again."
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
            // Scan EVERY "7F04" negative-response frame, not just the first: a pending 0x78 frame is
            // often followed by the real reply, so a lone 78 must not mask a later terminal rejection
            // (e.g. "7F 04 78 … 7F 04 22"). Return the last non-78 NRC when present; otherwise 78 if
            // only pending frames appeared, or "" when there was no negative-response frame at all.
            var lastTerminal = ""
            var sawPending = false
            var searchFrom = 0
            while (true) {
                val idx = normalized.indexOf("7F04", searchFrom)
                if (idx < 0 || idx + 6 > normalized.length) {
                    break
                }
                val code = normalized.substring(idx + 4, idx + 6)
                if (code == NRC_RESPONSE_PENDING) {
                    sawPending = true
                } else {
                    lastTerminal = code
                }
                searchFrom = idx + 6
            }
            return when {
                lastTerminal.isNotEmpty() -> lastTerminal
                sawPending -> NRC_RESPONSE_PENDING
                else -> ""
            }
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
