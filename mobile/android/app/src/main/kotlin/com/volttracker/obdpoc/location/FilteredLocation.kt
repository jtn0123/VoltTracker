package com.volttracker.obdpoc.location

/** An accepted location fix handed from a [LocationTracker] to its listener. Immutable. */
class FilteredLocation(
    @JvmField val latitude: Double,
    @JvmField val longitude: Double,
    @JvmField val accuracyM: Double?,
    @JvmField val altitudeM: Double?,
    @JvmField val speedMps: Double?,
    @JvmField val bearingDeg: Double?,
    @JvmField val fixTimeMs: Long,
    @JvmField val locationAgeMs: Long?,
    @JvmField val elapsedRealtimeNanos: Long?,
    @JvmField val provider: String?,
)
