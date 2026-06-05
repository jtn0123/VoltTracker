package com.volttracker.obdpoc.classify

import java.util.Locale

/**
 * How sure `VehicleStateClassifier` is about the [VehicleState] it returned. Mirrors the same
 * "render-only on the JS side" contract — the value is computed in Java and read on the payload,
 * never re-derived.
 */
enum class Confidence {
    /** No usable signal — caller should not assume anything about state. */
    UNKNOWN,

    /** One soft signal agrees with the state; could easily flip. */
    WEAK,

    /** Multiple cues agree with the state. */
    OBSERVED,

    /** An explicit Volt PID confirms the state. */
    CERTAIN,
    ;

    fun asPayloadKey(): String = name.lowercase(Locale.ROOT)
}
