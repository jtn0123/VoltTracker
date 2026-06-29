package com.volttracker.obdpoc

/**
 * Gating policy for the opt-in "auto-scan for codes on connect" feature (M3). Mirrors
 * [AutoConnectController]: a small, pure-ish decision unit the host wires its real actions into.
 *
 * When enabled it allows exactly one background Mode 03 scan shortly after a successful connect, and
 * never more than once per [throttleMs] window (persisted via [EventNotificationPrefs] so it survives
 * a reconnect/app restart within the same drive). Default OFF.
 *
 * It does not perform the scan itself — the caller passes a `startScan` action that reuses the
 * existing diagnostic-scan path and reports whether it actually read codes. [onConnected] returns
 * whether a scan was kicked off, so callers and tests can assert the gating directly.
 */
class AutoScanController(
    private val prefs: EventNotificationPrefs,
    private val throttleMs: Long = DEFAULT_THROTTLE_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    // Guards against a single connect firing more than one scan if onConnected is reached twice for
    // the same physical connect (e.g. status replayed). Reset on a fresh connect via resetForConnect.
    private var scanStartedThisConnect = false

    fun isEnabled(): Boolean = prefs.autoScanOnConnectEnabled()

    /**
     * Call once at the start of each connect attempt/session so the per-connect "already scanned"
     * latch is cleared; the throttle (persisted timestamp) still applies across connects.
     */
    fun resetForConnect() {
        scanStartedThisConnect = false
    }

    /**
     * Invoked after a successful connect. Runs [startScan] when enabled, not already scanned this
     * connect, and outside the throttle window. Returns true iff it started a scan.
     *
     * [startScan] reports whether the scan completed (a real Mode-03 reply, even when the car has no
     * stored codes). The per-drive throttle timestamp is recorded ONLY when it did, so a failed scan
     * (clone adapter / transient bus error) does not arm the 30-min throttle and silently block the next
     * reconnect from retrying. The per-connect latch is still set up front so a replayed connect
     * cannot double-fire a scan within the same connect, regardless of outcome.
     */
    fun onConnected(startScan: () -> Boolean): Boolean {
        if (!isEnabled() || scanStartedThisConnect) {
            return false
        }
        val now = nowMs()
        val last = prefs.lastAutoScanAtMs()
        if (last > 0L && now - last < throttleMs) {
            return false
        }
        scanStartedThisConnect = true
        val completed = startScan()
        if (completed) {
            prefs.setLastAutoScanAtMs(now)
        }
        return true
    }

    /** Remaining throttle time in ms, for diagnostics/UI; 0 when a scan is allowed now. */
    fun throttleRemainingMs(): Long {
        val last = prefs.lastAutoScanAtMs()
        if (last <= 0L) {
            return 0L
        }
        return maxOf(0L, throttleMs - maxOf(0L, nowMs() - last))
    }

    companion object {
        /** No more than one auto-scan per this window — roughly once per drive. */
        const val DEFAULT_THROTTLE_MS = 30L * 60L * 1000L
    }
}
