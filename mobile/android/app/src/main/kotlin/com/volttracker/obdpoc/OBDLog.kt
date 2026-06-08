package com.volttracker.obdpoc

import android.util.Log

/**
 * Structured key=value log helper for state-transition events on the Bluetooth IO thread.
 */
object OBDLog {
    @Volatile
    private var mirror: RollingAppLog? = null

    /** Tees subsequent event/warn/error calls to [log]. Pass null to detach. */
    @JvmStatic
    fun mirror(log: RollingAppLog?) {
        mirror = log
    }

    @JvmStatic
    fun event(
        tag: String?,
        event: String?,
        fields: Map<String, *>?,
    ) {
        val safeEvent = if (event.isNullOrEmpty()) "unknown_event" else event
        val sb = StringBuilder(safeEvent)
        if (!fields.isNullOrEmpty()) {
            for ((key, value) in fields) {
                sb
                    .append(' ')
                    .append(key)
                    .append('=')
                    .append(format(value))
            }
        }
        val line = sb.toString()
        val safeTag = tag ?: "obd"
        Log.d(safeTag, line)
        mirror?.write("D", safeTag, line)
    }

    /** Warn-level wrapper; also lands in the rolling log when a mirror is installed. */
    @JvmStatic
    fun warn(
        tag: String?,
        msg: String,
    ) {
        val safeTag = tag ?: "obd"
        Log.w(safeTag, msg)
        mirror?.write("W", safeTag, msg)
    }

    /** Error-level wrapper; also lands in the rolling log when a mirror is installed. */
    @JvmStatic
    fun error(
        tag: String?,
        msg: String,
    ) {
        val safeTag = tag ?: "obd"
        Log.e(safeTag, msg)
        mirror?.write("E", safeTag, msg)
    }

    @JvmStatic
    fun format(value: Any?): String {
        if (value == null) return "-"
        val raw = value.toString()
        if (raw.isEmpty()) return "\"\""
        val s =
            raw
                .replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\"", "\\\"")
        if (s.indexOf(' ') < 0 && s.indexOf('=') < 0 && s.indexOf('"') < 0) return s
        return "\"$s\""
    }
}
