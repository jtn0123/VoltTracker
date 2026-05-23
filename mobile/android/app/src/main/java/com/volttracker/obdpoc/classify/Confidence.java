package com.volttracker.obdpoc.classify;

import java.util.Locale;

/**
 * How sure {@link VehicleStateClassifier} is about the {@link VehicleState} it returned. Mirrors
 * the same "render-only on the JS side" contract — the value is computed in Java and read on the
 * payload, never re-derived.
 */
public enum Confidence {
    /** No usable signal — caller should not assume anything about state. */
    UNKNOWN,
    /** One soft signal agrees with the state; could easily flip. */
    WEAK,
    /** Multiple cues agree with the state. */
    OBSERVED,
    /** An explicit Volt PID confirms the state. */
    CERTAIN;

    public String asPayloadKey() {
        return name().toLowerCase(Locale.ROOT);
    }
}
