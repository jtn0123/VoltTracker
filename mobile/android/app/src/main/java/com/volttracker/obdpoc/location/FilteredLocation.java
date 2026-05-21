package com.volttracker.obdpoc.location;

/** An accepted location fix handed from a {@link LocationTracker} to its listener. Immutable. */
public final class FilteredLocation {

    public final double latitude;
    public final double longitude;
    public final Double accuracyM;
    public final Double altitudeM;
    public final Double speedMps;
    public final Double bearingDeg;
    public final long fixTimeMs;
    public final Long locationAgeMs;
    public final Long elapsedRealtimeNanos;
    public final String provider;

    public FilteredLocation(double latitude, double longitude, Double accuracyM, Double altitudeM,
                            Double speedMps, Double bearingDeg, long fixTimeMs, Long locationAgeMs,
                            Long elapsedRealtimeNanos, String provider) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracyM = accuracyM;
        this.altitudeM = altitudeM;
        this.speedMps = speedMps;
        this.bearingDeg = bearingDeg;
        this.fixTimeMs = fixTimeMs;
        this.locationAgeMs = locationAgeMs;
        this.elapsedRealtimeNanos = elapsedRealtimeNanos;
        this.provider = provider;
    }
}
