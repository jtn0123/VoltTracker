package com.volttracker.obdpoc;

import android.util.Log;
import java.util.Map;
import java.util.Objects;

/**
 * Structured key=value log helper for state-transition events on the Bluetooth IO thread.
 *
 * <p>Emits a single line like {@code tag k1=v1 k2=v2}. Use for connect/disconnect/reconnect/
 * protocol_init/poll_start/poll_end — not for every routine telemetry line. The intent is
 * grep-ability of field logs, not a structured logging framework.
 *
 * <p>Values are toString()'d; nulls render as {@code -}. Pass already-redacted values (use {@link
 * MainActivity#redactAddress(String)} for MAC addresses).
 */
final class OBDLog {
    private OBDLog() {}

    // Mirror target: when non-null, every event/warn/error call also tees its formatted line into
    // the rolling app log so the diagnostics-share zip carries cross-session logcat history.
    // Volatile rather than synchronized because the only mutation is install-time from
    // ObdService.onCreate; reads from many threads need a happens-before guarantee, not mutual
    // exclusion.
    private static volatile RollingAppLog mirror;

    /** Tees subsequent event/warn/error calls to {@code log}. Pass null to detach. */
    static void mirror(RollingAppLog log) {
        mirror = log;
    }

    static void event(String tag, String event, Map<String, ?> fields) {
        String safeEvent = (event == null || event.isEmpty()) ? "unknown_event" : event;
        StringBuilder sb = new StringBuilder(safeEvent);
        if (fields != null && !fields.isEmpty()) {
            for (Map.Entry<String, ?> e : fields.entrySet()) {
                sb.append(' ').append(e.getKey()).append('=').append(format(e.getValue()));
            }
        }
        String line = sb.toString();
        String safeTag = Objects.requireNonNullElse(tag, "obd");
        Log.d(safeTag, line);
        RollingAppLog m = mirror;
        if (m != null) {
            m.write("D", safeTag, line);
        }
    }

    /** Warn-level wrapper; also lands in the rolling log when a mirror is installed. */
    static void warn(String tag, String msg) {
        String safeTag = Objects.requireNonNullElse(tag, "obd");
        Log.w(safeTag, msg);
        RollingAppLog m = mirror;
        if (m != null) {
            m.write("W", safeTag, msg);
        }
    }

    /** Error-level wrapper; also lands in the rolling log when a mirror is installed. */
    static void error(String tag, String msg) {
        String safeTag = Objects.requireNonNullElse(tag, "obd");
        Log.e(safeTag, msg);
        RollingAppLog m = mirror;
        if (m != null) {
            m.write("E", safeTag, msg);
        }
    }

    static String format(Object value) {
        if (value == null) return "-";
        String raw = value.toString();
        if (raw.isEmpty()) return "\"\"";
        // Escape order matters: escape backslashes from the source FIRST so subsequent escapes
        // we introduce (\n, \r, \") never get re-escaped. After this pass the string is
        // single-line and grep-safe; any of the trigger characters then forces the quoted form.
        String s =
                raw.replace("\\", "\\\\")
                        .replace("\r", "\\r")
                        .replace("\n", "\\n")
                        .replace("\"", "\\\"");
        if (s.indexOf(' ') < 0 && s.indexOf('=') < 0 && s.indexOf('"') < 0) return s;
        return "\"" + s + "\"";
    }
}
