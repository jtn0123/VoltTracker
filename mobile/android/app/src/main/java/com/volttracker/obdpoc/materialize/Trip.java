package com.volttracker.obdpoc.materialize;

/**
 * Materialized trip segment derived from a continuous run of location samples. The materializer
 * produces these; persistence to {@code trip_segments} happens in {@code ObdLocalStore}.
 */
public final class Trip {
    public final long startedAtMs;
    public final long endedAtMs;
    public final double distanceMeters;
    public final long durationMs;
    public final double maxSpeedKph;
    public final int sampleCount;
    public final boolean hasRoute;
    public final Confidence confidence;

    public Trip(
            long startedAtMs,
            long endedAtMs,
            double distanceMeters,
            long durationMs,
            double maxSpeedKph,
            int sampleCount,
            boolean hasRoute,
            Confidence confidence) {
        this.startedAtMs = startedAtMs;
        this.endedAtMs = endedAtMs;
        this.distanceMeters = distanceMeters;
        this.durationMs = durationMs;
        this.maxSpeedKph = maxSpeedKph;
        this.sampleCount = sampleCount;
        this.hasRoute = hasRoute;
        this.confidence = confidence == null ? Confidence.UNKNOWN : confidence;
    }
}
