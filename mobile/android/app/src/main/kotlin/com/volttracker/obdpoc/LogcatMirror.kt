package com.volttracker.obdpoc

import android.util.Log

/**
 * Convenience facade that writes to both logcat and a [RollingAppLog] in one call.
 */
class LogcatMirror(
    private val rollingLog: RollingAppLog,
) {
    fun d(
        tag: String,
        msg: String,
    ) {
        // Debug-level logcat is dev-only noise; the rolling log is the release surface.
        if (BuildConfig.DEBUG) Log.d(tag, msg)
        rollingLog.write("D", tag, msg)
    }

    fun i(
        tag: String,
        msg: String,
    ) {
        Log.i(tag, msg)
        rollingLog.write("I", tag, msg)
    }

    fun w(
        tag: String,
        msg: String,
    ) {
        Log.w(tag, msg)
        rollingLog.write("W", tag, msg)
    }

    fun w(
        tag: String,
        msg: String,
        t: Throwable,
    ) {
        Log.w(tag, msg, t)
        rollingLog.write("W", tag, "$msg ${Log.getStackTraceString(t)}")
    }

    fun e(
        tag: String,
        msg: String,
    ) {
        Log.e(tag, msg)
        rollingLog.write("E", tag, msg)
    }

    fun e(
        tag: String,
        msg: String,
        t: Throwable,
    ) {
        Log.e(tag, msg, t)
        rollingLog.write("E", tag, "$msg ${Log.getStackTraceString(t)}")
    }
}
