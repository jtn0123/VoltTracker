package com.volttracker.obdpoc.ui.live

import com.volttracker.obdpoc.ui.VoltAppUiState
import com.volttracker.obdpoc.ui.diag.DiagUiState
import com.volttracker.obdpoc.ui.diag.DtcCode
import com.volttracker.obdpoc.ui.diag.FreezeFrameSnapshot
import com.volttracker.obdpoc.ui.diag.SCAN_WINDOW_MS

/**
 * The trouble codes Health and the Car tab show: the codes saved on the phone, split into the ones
 * the car's last check found and the ones it has since dropped — or, while the demo runs, the
 * demo's (demo data never touches real history).
 */
internal class HealthHistoryHolder {
    /** What the host read from the store: every saved code, and when the codes were last read / cleared. */
    data class Logged(
        val codes: List<DtcCode>,
        val scannedAtMs: Long?,
        val clearedAtMs: Long?,
        val freezeFrame: FreezeFrameSnapshot? = null,
    )

    private var logged: Logged? = null
    private var loggedAtMs = 0L
    private var demoAtMs: Long? = null
    private var demoScannedAtMs: Long? = null
    private var demoClearedAtMs: Long? = null

    fun onHistory(
        history: Logged,
        nowMs: Long,
    ) {
        logged = history
        loggedAtMs = nowMs
    }

    /** The demo "scanned" now: its codes are found again. */
    fun onDemoScan(nowMs: Long) {
        demoScannedAtMs = nowMs
        demoClearedAtMs = null
    }

    /** The demo "cleared" its codes now. */
    fun onDemoClear(nowMs: Long) {
        demoClearedAtMs = nowMs
    }

    fun apply(
        s: VoltAppUiState,
        nowMs: Long,
    ): VoltAppUiState {
        val next =
            if (s.settings.demoActive) {
                demo(s.diag, nowMs)
            } else {
                demoAtMs = null
                demoScannedAtMs = null
                demoClearedAtMs = null
                real(s.diag, nowMs)
            }
        return if (next == s.diag) s else s.copy(diag = next)
    }

    private fun demo(
        current: DiagUiState,
        nowMs: Long,
    ): DiagUiState {
        val at = demoAtMs ?: nowMs.also { demoAtMs = it }
        val sample = DiagUiState.demoAt(at)
        val scanned = demoScannedAtMs
        val codes =
            when {
                demoClearedAtMs != null -> emptyList()
                scanned != null -> sample.codes.orEmpty().map { it.copy(lastSeenMs = scanned) }
                else -> sample.codes
            }
        return current.copy(
            codes = codes,
            earlierCodes = if (demoClearedAtMs != null) sample.codes.orEmpty().map { it.code } else emptyList(),
            scannedAtMs = scanned ?: sample.scannedAtMs,
            clearedAtMs = demoClearedAtMs,
            freezeFrame = if (demoClearedAtMs != null) null else sample.freezeFrame,
            nowMs = maxOf(nowMs, at),
        )
    }

    private fun real(
        current: DiagUiState,
        nowMs: Long,
    ): DiagUiState {
        val history =
            logged ?: return current.copy(codes = null, earlierCodes = emptyList(), freezeFrame = null, nowMs = nowMs)
        val newest = history.codes.maxOfOrNull { it.lastSeenMs }
        // A scan from the classic dashboard isn't timed here, but the codes it saved carry its time.
        val scanned = listOfNotNull(history.scannedAtMs, newest).maxOrNull()
        val cleared = history.clearedAtMs?.takeIf { scanned == null || it > scanned }
        val since = cleared ?: scanned?.minus(SCAN_WINDOW_MS)
        val (found, earlier) =
            if (since == null) history.codes to emptyList() else history.codes.partition { it.lastSeenMs >= since }
        val neverChecked = history.codes.isEmpty() && history.scannedAtMs == null && cleared == null
        return current.copy(
            codes = if (neverChecked) null else found,
            earlierCodes = earlier.map { it.code }.distinct(),
            scannedAtMs = scanned,
            clearedAtMs = cleared,
            freezeFrame = history.freezeFrame,
            nowMs = maxOf(nowMs, loggedAtMs),
        )
    }
}
