package com.volttracker.obdpoc

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight startup timing markers for local ADB benchmarks. The log format is intentionally
 * stable so tools can parse it from logcat without needing a debugger or profiler attached.
 */
object StartupTrace {
    const val TAG = "VoltStartup"

    // OBD session-latency mark names. Emitted on the connect/scan/demo paths and parsed by
    // tools/perf/check_obd_latency.py (see docs/performance-baseline-history.md). Marks that vary
    // by session mode append ":<mode>" (obd/scan/demo/tpms-scan/clear-dtc) or ":<stage>"; keep the
    // constants and the parser's MARK_* names in sync — ObdLatencyMarksContractTest pins both sides.
    const val OBD_CONNECT_REQUEST = "obd_connect_request"
    const val OBD_SOCKET_CONNECTED = "obd_socket_connected"
    const val OBD_ELM_INIT_START = "obd_elm_init_start"
    const val OBD_ELM_INIT_END = "obd_elm_init_end"
    const val OBD_FIRST_SAMPLE = "obd_first_sample"
    const val OBD_SCAN_START = "obd_scan_start"
    const val OBD_SCAN_STAGE = "obd_scan_stage"
    const val OBD_SCAN_COMPLETE = "obd_scan_complete"

    private val baseElapsedMs = AtomicLong(SystemClock.elapsedRealtime())

    fun reset(reason: String) {
        if (!BuildConfig.DEBUG) {
            return
        }
        baseElapsedMs.set(SystemClock.elapsedRealtime())
        mark("reset:$reason")
    }

    fun mark(name: String) {
        if (!BuildConfig.DEBUG) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - baseElapsedMs.get()
        Log.i(TAG, "mark=$name elapsedMs=$elapsed uptimeMs=${SystemClock.uptimeMillis()}")
    }

    inline fun <T> measure(
        startName: String,
        endName: String,
        block: () -> T,
    ): T {
        mark(startName)
        return try {
            block()
        } finally {
            mark(endName)
        }
    }
}
