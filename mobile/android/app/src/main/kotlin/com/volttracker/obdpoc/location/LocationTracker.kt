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

    /** Begins delivering accepted fixes to [listener]. A no-op without location permission. */
    fun start(listener: Listener)

    /** Stops delivery and releases the underlying provider. */
    fun stop()

    /** The most recent accepted fix, or `null` if none has been accepted yet. */
    fun getLastLocation(): FilteredLocation?
}
