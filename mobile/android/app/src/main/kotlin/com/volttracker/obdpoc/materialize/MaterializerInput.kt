package com.volttracker.obdpoc.materialize

/** Immutable bundle of the session identity and time bounds the materializers operate on. */
class MaterializerInput(
    @JvmField val sessionId: Long,
    @JvmField val startedAtMs: Long,
    @JvmField val closedAtMs: Long,
) {
    init {
        // Reject impossible windows so a clock glitch or a swapped argument fails fast instead of
        // silently yielding empty/garbage trips. SessionRecorder#materializeIfEnabled wraps
        // construction in a try/catch, so a bad window just records a materialize_failure and skips
        // — it can never crash session finalization. An equal start/close (zero-duration) window is
        // allowed; the materializers correctly return nothing for it. No upper bound on the span: a
        // car left connected for days is unusual but legitimate, and the materializers already split
        // long runs internally (TripMaterializer.MAX_TRIP_DURATION_MS).
        require(sessionId > 0) { "sessionId must be positive: $sessionId" }
        require(startedAtMs >= 0) { "startedAtMs must be non-negative: $startedAtMs" }
        require(closedAtMs >= startedAtMs) {
            "closedAtMs ($closedAtMs) must be >= startedAtMs ($startedAtMs)"
        }
    }
}
