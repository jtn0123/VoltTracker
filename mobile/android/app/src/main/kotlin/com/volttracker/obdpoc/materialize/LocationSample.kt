package com.volttracker.obdpoc.materialize

/**
 * Read-side view of one row from `location_samples`, as the materializers need it. Boxed numeric
 * fields are nullable because the underlying SQLite columns are.
 */
class LocationSample(
    @JvmField val capturedAtMs: Long,
    @JvmField val latitude: Double,
    @JvmField val longitude: Double,
    @JvmField val speedMps: Double?,
    @JvmField val accuracyM: Float?,
)
