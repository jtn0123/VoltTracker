package com.volttracker.obdpoc.materialize

/**
 * Materialized trip segment derived from a continuous run of location samples. The materializer
 * produces these; persistence to `trip_segments` happens in `ObdLocalStore`.
 */
class Trip(
    @JvmField val startedAtMs: Long,
    @JvmField val endedAtMs: Long,
    @JvmField val distanceMeters: Double,
    @JvmField val durationMs: Long,
    @JvmField val maxSpeedKph: Double,
    @JvmField val sampleCount: Int,
    @JvmField val hasRoute: Boolean,
    confidence: Confidence?,
    classification: String? = CLASSIFICATION_UNKNOWN,
    @JvmField val energyKwh: Double? = null,
) {
    @JvmField val confidence: Confidence = confidence ?: Confidence.UNKNOWN

    @JvmField val classification: String = classification ?: CLASSIFICATION_UNKNOWN

    companion object {
        const val CLASSIFICATION_CITY: String = "city"
        const val CLASSIFICATION_HIGHWAY: String = "highway"
        const val CLASSIFICATION_MIXED: String = "mixed"
        const val CLASSIFICATION_UNKNOWN: String = "unknown"
    }
}
