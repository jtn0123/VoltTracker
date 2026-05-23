package com.volttracker.obdpoc.materialize;

import java.util.List;

/**
 * Read-only view the materializers need over the persisted session data. Implemented by {@code
 * ObdLocalStore}. All reads are bounded to one session id and ordered oldest-first by {@code
 * captured_at_ms}.
 *
 * <p><b>Mutability contract.</b> The returned {@link List} instances are owned by the caller and
 * are guaranteed to be safe to iterate, but they are <em>not</em> required to be immutable.
 * Materializer code must not mutate them; callers that need to mutate should make a defensive copy.
 * Treat every returned list as read-only.
 */
public interface MaterializerData {
    /** Returns location samples for the session, ordered oldest-first by {@code captured_at_ms}. */
    List<LocationSample> readLocationSamples(long sessionId);

    /** Returns PID observations for the session, ordered oldest-first by {@code captured_at_ms}. */
    List<PidObservation> readPidObservations(long sessionId);

    /**
     * Returns telemetry samples for the session, ordered oldest-first by {@code captured_at_ms}.
     */
    List<TelemetrySample> readTelemetrySamples(long sessionId);
}
