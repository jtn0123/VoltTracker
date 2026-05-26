package com.volttracker.obdpoc;

import java.io.IOException;
import java.util.Locale;

/**
 * Maps a connect / first-read {@link IOException} (plus timing + Android-adapter signal) to a
 * {@link FailureClass}. The classification feeds the engine's adaptive-retry decision (two
 * consecutive {@link FailureClass#INSTANT_DROP}s in &lt;500 ms triggers long-backoff mode) and is
 * stamped onto every {@code reconnect} / {@code reconnect_exhausted} / {@code wedged_suspected}
 * JSONL event as {@code failureClass} so post-hoc log triage can distinguish "adapter wedged" from
 * "car asleep" from "stale bond" without reading stack traces.
 *
 * <p>Pure static API + a small immutable input record. No state; no Android imports — safe to
 * unit-test on the host JVM.
 *
 * <p>Heuristics (in priority order):
 *
 * <ol>
 *   <li>{@link FailureClass#BT_OFF} — adapter null / disabled (signaled by {@link
 *       Input#bluetoothEnabled} == false).
 *   <li>{@link FailureClass#SDP_FAILURE} — message mentions "service discovery".
 *   <li>{@link FailureClass#REMOTE_REFUSED} — message mentions "connection refused".
 *   <li>{@link FailureClass#BOND_LOST} — message mentions "permission denied" / "not bonded" /
 *       bond-state changed.
 *   <li>{@link FailureClass#CONNECT_TIMEOUT} — error phase is {@code rfcomm_connect} or {@code
 *       get_streams}, OR message mentions "timeout"/"closed". (The watchdog inside {@link
 *       ElmConnection#open} closes the socket on timeout, which surfaces as a "closed" message.)
 *   <li>{@link FailureClass#INSTANT_DROP} — error phase is {@code first_read}, duration &lt;500 ms,
 *       and the message looks like "read failed, socket might closed or timeout, read ret: -1".
 *   <li>{@link FailureClass#UNKNOWN} — fallback.
 * </ol>
 */
final class ConnectionFailureClassifier {

    /** Threshold (ms) below which a {@code first_read} failure counts as an instant drop. */
    static final long INSTANT_DROP_THRESHOLD_MS = 500L;

    private ConnectionFailureClassifier() {}

    /**
     * One classifier input. Construct via {@link #of} so callers don't repeat the message
     * lowercasing dance. Fields are package-private so tests can build instances directly.
     */
    static final class Input {
        final String phase;
        final long durationMs;
        final String messageLower;
        final String exceptionClassName;
        final boolean bluetoothEnabled;

        private Input(
                String phase,
                long durationMs,
                String messageLower,
                String exceptionClassName,
                boolean bluetoothEnabled) {
            this.phase = phase == null ? "" : phase;
            this.durationMs = Math.max(0L, durationMs);
            this.messageLower = messageLower == null ? "" : messageLower;
            this.exceptionClassName = exceptionClassName == null ? "" : exceptionClassName;
            this.bluetoothEnabled = bluetoothEnabled;
        }
    }

    /**
     * Build an input from a real {@link IOException} (typical production path). Lowercases the
     * message once so the classifier itself stays branch-only.
     */
    static Input of(String phase, long durationMs, IOException ex, boolean bluetoothEnabled) {
        String message = ex == null ? "" : (ex.getMessage() == null ? "" : ex.getMessage());
        String exClass = ex == null ? "" : ex.getClass().getName();
        return new Input(
                phase, durationMs, message.toLowerCase(Locale.US), exClass, bluetoothEnabled);
    }

    /** Build an input from raw fields — used by unit tests to drive every heuristic branch. */
    static Input ofRaw(
            String phase,
            long durationMs,
            String messageLower,
            String exceptionClassName,
            boolean bluetoothEnabled) {
        return new Input(phase, durationMs, messageLower, exceptionClassName, bluetoothEnabled);
    }

    /** Apply the heuristics above and return the best-matching {@link FailureClass}. */
    static FailureClass classify(Input input) {
        if (input == null) {
            return FailureClass.UNKNOWN;
        }
        if (!input.bluetoothEnabled) {
            return FailureClass.BT_OFF;
        }
        String m = input.messageLower;

        if (m.contains("service discovery")) {
            return FailureClass.SDP_FAILURE;
        }
        if (m.contains("connection refused")) {
            return FailureClass.REMOTE_REFUSED;
        }
        if (m.contains("permission denied")
                || m.contains("not bonded")
                || m.contains("bond state")
                || m.contains("bond-state")
                || m.contains("bond_bonded")) {
            return FailureClass.BOND_LOST;
        }

        if (isConnectPhase(input.phase)) {
            return FailureClass.CONNECT_TIMEOUT;
        }

        if ("first_read".equals(input.phase)) {
            boolean looksLikeInstantDrop =
                    input.durationMs < INSTANT_DROP_THRESHOLD_MS
                            && (m.contains("read ret: -1")
                                    || m.contains("socket might closed")
                                    || m.contains("read failed"));
            if (looksLikeInstantDrop) {
                return FailureClass.INSTANT_DROP;
            }
        }

        // Generic timeout-flavored messages with no clearer phase signal — bucket as a
        // connect_timeout rather than UNKNOWN so the dashboard copy stays useful.
        if (m.contains("timeout") || m.contains("timed out")) {
            return FailureClass.CONNECT_TIMEOUT;
        }
        if (m.contains("socket might closed") || m.contains("socket closed")) {
            return FailureClass.CONNECT_TIMEOUT;
        }

        return FailureClass.UNKNOWN;
    }

    /** Convenience overload — classify directly from an IOException + context. */
    static FailureClass classify(
            String phase, long durationMs, IOException ex, boolean bluetoothEnabled) {
        return classify(of(phase, durationMs, ex, bluetoothEnabled));
    }

    private static boolean isConnectPhase(String phase) {
        return "rfcomm_connect".equals(phase) || "get_streams".equals(phase);
    }
}
