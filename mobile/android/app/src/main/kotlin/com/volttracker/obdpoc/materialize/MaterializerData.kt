package com.volttracker.obdpoc.materialize

/**
 * Read-only view the materializers need over the persisted session data. Implemented by
 * `ObdLocalStore`. All reads are bounded to one session id and ordered oldest-first by
 * `captured_at_ms`.
 */
interface MaterializerData {
    /** Returns location samples for the session, ordered oldest-first by `captured_at_ms`. */
    fun readLocationSamples(sessionId: Long): List<LocationSample>

    /** Returns PID observations for the session, ordered oldest-first by `captured_at_ms`. */
    fun readPidObservations(sessionId: Long): List<PidObservation>

    /** Returns telemetry samples for the session, ordered oldest-first by `captured_at_ms`. */
    fun readTelemetrySamples(sessionId: Long): List<TelemetrySample>
}
