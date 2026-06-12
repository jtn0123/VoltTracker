package com.volttracker.obdpoc.location

/**
 * Abstraction over a GPS source. Keeps route logging independent of the OBD connection and lets a
 * fused-provider implementation be swapped in later without touching callers.
 */
interface LocationTracker {
    fun interface Listener {
        /** Called on every accepted filtered fix. */
        fun onLocation(location: FilteredLocation)
    }

    /**
     * Begins delivering accepted fixes to [listener]. Without location permission the listener is
     * parked; [resumeUpdatesIfPermitted] can begin delivery once permission is granted.
     */
    fun start(listener: Listener)

    /**
     * Begins delivering fixes for an already-[start]ed tracker whose permission was missing at
     * start time (granted mid-session). Returns true only when updates were started by this call.
     */
    fun resumeUpdatesIfPermitted(): Boolean = false

    /** Stops delivery and releases the underlying provider. */
    fun stop()

    /** The most recent accepted fix, or `null` if none has been accepted yet. */
    fun getLastLocation(): FilteredLocation?
}
