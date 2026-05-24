package com.volttracker.obdpoc.materialize;

/**
 * Materialized trip segment derived from a continuous run of location samples. The materializer
 * produces these; persistence to {@code trip_segments} happens in {@code ObdLocalStore}.
 *
 * <p>{@link #classification} is a real label of the driving regime ({@code city} / {@code highway}
 * / {@code mixed} / {@code unknown}) — not to be confused with {@link #confidence}, which is how
 * sure the materializer is that the trip itself was real. Earlier code overwrote the {@code
 * classification} column with the confidence enum name; that mix-up is fixed here.
 *
 * <p>{@link #energyKwh} is the integral of {@code pack_voltage * pack_current_a} (discharge
 * positive) over the trip in kWh. {@code null} when we don't have pack telemetry for the window —
 * either because the columns weren't populated yet (pre-schema-v8 sessions) or because the trip was
 * a GPS-only segment with no OBD samples.
 */
public final class Trip {
    public static final String CLASSIFICATION_CITY = "city";
    public static final String CLASSIFICATION_HIGHWAY = "highway";
    public static final String CLASSIFICATION_MIXED = "mixed";
    public static final String CLASSIFICATION_UNKNOWN = "unknown";

    public final long startedAtMs;
    public final long endedAtMs;
    public final double distanceMeters;
    public final long durationMs;
    public final double maxSpeedKph;
    public final int sampleCount;
    public final boolean hasRoute;
    public final Confidence confidence;
    public final String classification;
    public final Double energyKwh;

    public Trip(
            long startedAtMs,
            long endedAtMs,
            double distanceMeters,
            long durationMs,
            double maxSpeedKph,
            int sampleCount,
            boolean hasRoute,
            Confidence confidence) {
        // Back-compat constructor: pre-classification tests pass through unknown / null energy.
        this(
                startedAtMs,
                endedAtMs,
                distanceMeters,
                durationMs,
                maxSpeedKph,
                sampleCount,
                hasRoute,
                confidence,
                CLASSIFICATION_UNKNOWN,
                null);
    }

    public Trip(
            long startedAtMs,
            long endedAtMs,
            double distanceMeters,
            long durationMs,
            double maxSpeedKph,
            int sampleCount,
            boolean hasRoute,
            Confidence confidence,
            String classification,
            Double energyKwh) {
        this.startedAtMs = startedAtMs;
        this.endedAtMs = endedAtMs;
        this.distanceMeters = distanceMeters;
        this.durationMs = durationMs;
        this.maxSpeedKph = maxSpeedKph;
        this.sampleCount = sampleCount;
        this.hasRoute = hasRoute;
        this.confidence = confidence == null ? Confidence.UNKNOWN : confidence;
        this.classification = classification == null ? CLASSIFICATION_UNKNOWN : classification;
        this.energyKwh = energyKwh;
    }
}
