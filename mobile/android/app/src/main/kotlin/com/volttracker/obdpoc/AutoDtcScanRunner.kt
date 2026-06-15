package com.volttracker.obdpoc

import java.io.IOException

/**
 * Focused, on-connect generic Mode 03 read for the auto-DTC-scan feature (M3). It is a deliberately
 * light cousin of [DiagnosticScanRunner]: rather than running the full multi-header probe sweep
 * (which would interrupt live polling), it sends one generic OBD-II `03` request and returns the
 * stored diagnostic codes found, so the auto-scan can run inline at connect without replacing the
 * live session.
 *
 * Runs on the service IO thread; command IO is dispatched back through the engine so the service's
 * `ioLock` and per-command logging continue to apply. Never throws — a probe failure must not break
 * the connect path — returning an empty list instead.
 */
class AutoDtcScanRunner(
    private val engine: ObdPollingEngine,
) {
    /** Sends generic Mode 03 and returns the deduped, upper-cased DTC codes found (may be empty). */
    fun readGenericDtcCodes(): List<String> =
        try {
            // Generic functional header so the parse maps to the generic-OBD module like a manual scan.
            engine.sendRecoverableCommand("ATSH7DF", 1800)
            val response = engine.sendRecoverableCommand("03", 3500)
            extractCodes(response)
        } catch (_: IOException) {
            emptyList()
        }

    private fun extractCodes(response: String?): List<String> {
        val parsed = ObdProtocol.parseDiagnosticTroubleCodes("03", response, "7DF")
        val out = LinkedHashSet<String>(parsed.size)
        for (dtc in parsed) {
            val code = dtc.code.trim().uppercase()
            if (code.isNotEmpty()) {
                out.add(code)
            }
        }
        return out.toList()
    }
}
