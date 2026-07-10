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
 * the connect path — returning an empty list instead. A parse failure after a real Mode 03 reply is
 * still a completed scan so reconnect throttling can arm.
 */
class AutoDtcScanRunner(
    private val engine: ObdPollingEngine,
) {
    data class ScanResult(
        val completed: Boolean,
        val codes: List<String>,
    )

    /** Sends generic Mode 03 and returns the deduped, upper-cased DTC codes found (may be empty). */
    fun readGenericDtcCodes(): List<String> = readGenericDtcScan().codes

    /**
     * Sends generic Mode 03 and reports whether the adapter completed the request. A clean car may
     * legitimately return no codes; that is still a completed scan and should arm the reconnect
     * throttle. IO/command failures return [ScanResult.completed] false; parse failures after the
     * reply is received return a completed scan with no usable codes.
     */
    fun readGenericDtcScan(): ScanResult {
        val response =
            try {
                // Set the generic OBD-II broadcast (tester) request header 7DF so the reply parses
                // into the generic-OBD module like a manual scan.
                engine.sendRecoverableCommand("ATSH7DF", 1800)
                engine.sendRecoverableCommand("03", 3500)
            } catch (_: IOException) {
                return ScanResult(completed = false, codes = emptyList())
            } catch (_: RuntimeException) {
                return ScanResult(completed = false, codes = emptyList())
            }
        if (!ObdProtocol.hasPositiveDiagnosticResponse("03", response)) {
            return ScanResult(completed = false, codes = emptyList())
        }
        val codes =
            try {
                extractCodes(response)
            } catch (_: RuntimeException) {
                return ScanResult(completed = false, codes = emptyList())
            }
        return ScanResult(completed = true, codes = codes)
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
