package com.volttracker.obdpoc

import java.io.IOException
import java.util.Locale

/**
 * Maps a connect / first-read [IOException] (plus timing + Android-adapter signal) to a
 * [FailureClass]. The classification feeds the engine's adaptive-retry decision (two consecutive
 * [FailureClass.INSTANT_DROP]s in <500 ms triggers long-backoff mode) and is stamped onto every
 * `reconnect` / `reconnect_exhausted` / `wedged_suspected` JSONL event as `failureClass` so
 * post-hoc log triage can distinguish "adapter wedged" from "car asleep" from "stale bond"
 * without reading stack traces.
 *
 * Pure static API + a small immutable input record. No state; no Android imports, so it is safe to
 * unit-test on the host JVM.
 */
class ConnectionFailureClassifier private constructor() {
    /**
     * One classifier input. Construct via [of] so callers do not repeat the message lowercasing
     * dance. Fields stay JVM fields to preserve Java test/caller access semantics.
     */
    class Input private constructor(
        phase: String?,
        durationMs: Long,
        messageLower: String?,
        exceptionClassName: String?,
        bluetoothEnabled: Boolean,
    ) {
        @JvmField
        val phase: String = phase ?: ""

        @JvmField
        val durationMs: Long = durationMs.coerceAtLeast(0L)

        @JvmField
        val messageLower: String = messageLower ?: ""

        @JvmField
        val exceptionClassName: String = exceptionClassName ?: ""

        @JvmField
        val bluetoothEnabled: Boolean = bluetoothEnabled

        companion object {
            @JvmStatic
            fun create(
                phase: String?,
                durationMs: Long,
                messageLower: String?,
                exceptionClassName: String?,
                bluetoothEnabled: Boolean,
            ): Input = Input(phase, durationMs, messageLower, exceptionClassName, bluetoothEnabled)
        }
    }

    companion object {
        /** Threshold (ms) below which a `first_read` failure counts as an instant drop. */
        const val INSTANT_DROP_THRESHOLD_MS = 500L

        /**
         * Build an input from a real [IOException] (typical production path). Lowercases the
         * message once so the classifier itself stays branch-only.
         */
        @JvmStatic
        fun of(
            phase: String?,
            durationMs: Long,
            ex: IOException?,
            bluetoothEnabled: Boolean,
        ): Input {
            val message = ex?.message ?: ""
            val exClass = ex?.javaClass?.name ?: ""
            return Input.create(
                phase,
                durationMs,
                message.lowercase(Locale.US),
                exClass,
                bluetoothEnabled,
            )
        }

        /** Build an input from raw fields; used by unit tests to drive every heuristic branch. */
        @JvmStatic
        fun ofRaw(
            phase: String?,
            durationMs: Long,
            messageLower: String?,
            exceptionClassName: String?,
            bluetoothEnabled: Boolean,
        ): Input = Input.create(phase, durationMs, messageLower, exceptionClassName, bluetoothEnabled)

        /** Apply the heuristics above and return the best-matching [FailureClass]. */
        @JvmStatic
        fun classify(input: Input?): FailureClass {
            if (input == null) {
                return FailureClass.UNKNOWN
            }
            if (!input.bluetoothEnabled) {
                return FailureClass.BT_OFF
            }
            val message = input.messageLower

            if (message.contains("service discovery")) {
                return FailureClass.SDP_FAILURE
            }
            if (message.contains("connection refused")) {
                return FailureClass.REMOTE_REFUSED
            }
            if (
                message.contains("permission denied") ||
                message.contains("not bonded") ||
                message.contains("bond state") ||
                message.contains("bond-state") ||
                message.contains("bond_bonded")
            ) {
                return FailureClass.BOND_LOST
            }

            if (isConnectPhase(input.phase)) {
                return FailureClass.CONNECT_TIMEOUT
            }

            if (input.phase == "first_read") {
                val looksLikeInstantDrop =
                    input.durationMs < INSTANT_DROP_THRESHOLD_MS &&
                        (
                            message.contains("read ret: -1") ||
                                message.contains("socket might closed") ||
                                message.contains("read failed")
                        )
                if (looksLikeInstantDrop) {
                    return FailureClass.INSTANT_DROP
                }
            }

            // Generic timeout-flavored messages with no clearer phase signal: keep dashboard copy
            // useful by bucketing these as connect_timeout rather than UNKNOWN.
            if (message.contains("timeout") || message.contains("timed out")) {
                return FailureClass.CONNECT_TIMEOUT
            }
            if (message.contains("socket might closed") || message.contains("socket closed")) {
                return FailureClass.CONNECT_TIMEOUT
            }

            return FailureClass.UNKNOWN
        }

        /** Convenience overload; classify directly from an IOException + context. */
        @JvmStatic
        fun classify(
            phase: String?,
            durationMs: Long,
            ex: IOException?,
            bluetoothEnabled: Boolean,
        ): FailureClass = classify(of(phase, durationMs, ex, bluetoothEnabled))

        private fun isConnectPhase(phase: String): Boolean = phase == "rfcomm_connect" || phase == "get_streams"
    }
}
