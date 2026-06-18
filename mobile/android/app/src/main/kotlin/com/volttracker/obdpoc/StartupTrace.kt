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
