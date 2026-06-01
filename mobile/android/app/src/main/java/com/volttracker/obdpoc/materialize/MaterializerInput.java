package com.volttracker.obdpoc.materialize;

/** Immutable bundle of the session identity and time bounds the materializers operate on. */
public final class MaterializerInput {
    public final long sessionId;
    public final long startedAtMs;
    public final long closedAtMs;

    public MaterializerInput(long sessionId, long startedAtMs, long closedAtMs) {
        // Reject impossible windows so a clock glitch or a swapped argument fails fast instead of
        // silently yielding empty/garbage trips. SessionRecorder#materializeIfEnabled wraps
        // construction in a try/catch, so a bad window just records a materialize_failure and skips
        // — it can never crash session finalization. An equal start/close (zero-duration) window is
        // allowed; the materializers correctly return nothing for it. No upper bound on the span: a
        // car left connected for days is unusual but legitimate, and the materializers already
        // split
        // long runs internally (TripMaterializer.MAX_TRIP_DURATION_MS).
        if (sessionId <= 0) {
            throw new IllegalArgumentException("sessionId must be positive: " + sessionId);
        }
        if (startedAtMs < 0) {
            throw new IllegalArgumentException("startedAtMs must be non-negative: " + startedAtMs);
        }
        if (closedAtMs < startedAtMs) {
            throw new IllegalArgumentException(
                    "closedAtMs (" + closedAtMs + ") must be >= startedAtMs (" + startedAtMs + ")");
        }
        this.sessionId = sessionId;
        this.startedAtMs = startedAtMs;
        this.closedAtMs = closedAtMs;
    }
}
