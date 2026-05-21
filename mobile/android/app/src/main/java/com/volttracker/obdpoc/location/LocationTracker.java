package com.volttracker.obdpoc.location;

/**
 * Abstraction over a GPS source. Keeps route logging independent of the OBD connection and
 * lets a fused-provider implementation be swapped in later without touching callers.
 */
public interface LocationTracker {

    interface Listener {
        /** Called on every accepted (filtered) fix. */
        void onLocation(FilteredLocation location);
    }

    /** Begins delivering accepted fixes to {@code listener}. A no-op without location permission. */
    void start(Listener listener);

    /** Stops delivery and releases the underlying provider. */
    void stop();

    /** The most recent accepted fix, or {@code null} if none has been accepted yet. */
    FilteredLocation getLastLocation();
}
