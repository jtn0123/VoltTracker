package com.volttracker.obdpoc;

import android.util.Log;

/**
 * Convenience facade that writes to both {@link android.util.Log logcat} and a {@link
 * RollingAppLog} in one call. Used for sites that want their messages in the diagnostics zip but
 * don't want to thread the rolling-log reference through their constructor.
 *
 * <p>{@link OBDLog#mirror(RollingAppLog)} is the global install hook — once it's been called, every
 * {@code OBDLog.event/warn/error} also tees here. This class is the explicit alternative for
 * callers that aren't using {@code OBDLog} (e.g. a one-off site that prefers {@code Log.d(tag,
 * msg)} ergonomics).
 *
 * <p>Trying to globally intercept {@link android.util.Log} via {@code Log.setWtfHandler} only works
 * for the WTF channel, and there is no public per-level hook on Android. The explicit wrapper is
 * the only portable answer.
 */
final class LogcatMirror {

    private final RollingAppLog rollingLog;

    LogcatMirror(RollingAppLog rollingLog) {
        this.rollingLog = rollingLog;
    }

    void d(String tag, String msg) {
        Log.d(tag, msg);
        rollingLog.write("D", tag, msg);
    }

    void i(String tag, String msg) {
        Log.i(tag, msg);
        rollingLog.write("I", tag, msg);
    }

    void w(String tag, String msg) {
        Log.w(tag, msg);
        rollingLog.write("W", tag, msg);
    }

    void w(String tag, String msg, Throwable t) {
        Log.w(tag, msg, t);
        rollingLog.write("W", tag, msg + " " + Log.getStackTraceString(t));
    }

    void e(String tag, String msg) {
        Log.e(tag, msg);
        rollingLog.write("E", tag, msg);
    }

    void e(String tag, String msg, Throwable t) {
        Log.e(tag, msg, t);
        rollingLog.write("E", tag, msg + " " + Log.getStackTraceString(t));
    }
}
