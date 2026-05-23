package com.volttracker.obdpoc.materialize;

/**
 * Read-side view of one row from {@code location_samples}, as the materializers need it. Fields are
 * package-private since the materializers and tests are in the same package.
 */
public final class LocationSample {
    public final long capturedAtMs;
    public final double latitude;
    public final double longitude;
    public final Double speedMps;
    public final Float accuracyM;

    public LocationSample(
            long capturedAtMs,
            double latitude,
            double longitude,
            Double speedMps,
            Float accuracyM) {
        this.capturedAtMs = capturedAtMs;
        this.latitude = latitude;
        this.longitude = longitude;
        this.speedMps = speedMps;
        this.accuracyM = accuracyM;
    }
}
