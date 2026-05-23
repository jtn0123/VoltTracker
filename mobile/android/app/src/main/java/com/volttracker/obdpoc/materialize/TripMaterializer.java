package com.volttracker.obdpoc.materialize;

import java.util.ArrayList;
import java.util.List;

/**
 * Conservative trip materializer: takes the location samples logged for a session and groups them
 * into "trip" windows where the device kept moving with continuity. Tunables are deliberately
 * cautious — we'd rather drop a borderline trip than fabricate one. See {@link Tunables} for the
 * thresholds and what they mean.
 */
public final class TripMaterializer {

    private TripMaterializer() {}

    /** All thresholds in one place so they're easy to retune from tests. */
    static final class Tunables {
        /** Two samples farther apart than this end the current trip window. */
        static final long MAX_GAP_MS = 5L * 60_000L;

        /** Below this distance, the window is not a real trip. */
        static final double MIN_DISTANCE_METERS = 100.0;

        /** Below this duration, the window is not a real trip. */
        static final long MIN_DURATION_MS = 30_000L;

        /** At this sample count or more the materializer reports {@link Confidence#OBSERVED}. */
        static final int OBSERVED_SAMPLE_COUNT = 10;

        /** At this sample count or more the trip is considered to have a renderable route. */
        static final int HAS_ROUTE_SAMPLE_COUNT = 5;
    }

    /** Splits the session's location samples into zero or more trips and returns them. */
    public static List<Trip> materialize(MaterializerInput in, MaterializerData data) {
        List<Trip> result = new ArrayList<>();
        if (in == null || data == null) {
            return result;
        }
        List<LocationSample> samples = data.readLocationSamples(in.sessionId);
        if (samples == null || samples.isEmpty()) {
            return result;
        }
        List<TelemetrySample> telemetry = data.readTelemetrySamples(in.sessionId);

        List<LocationSample> window = new ArrayList<>();
        for (LocationSample sample : samples) {
            if (!window.isEmpty()) {
                long gap = sample.capturedAtMs - window.get(window.size() - 1).capturedAtMs;
                if (gap > Tunables.MAX_GAP_MS) {
                    Trip trip = buildTrip(window, telemetry);
                    if (trip != null) {
                        result.add(trip);
                    }
                    window = new ArrayList<>();
                }
            }
            window.add(sample);
        }
        if (!window.isEmpty()) {
            Trip trip = buildTrip(window, telemetry);
            if (trip != null) {
                result.add(trip);
            }
        }
        return result;
    }

    private static Trip buildTrip(List<LocationSample> window, List<TelemetrySample> telemetry) {
        if (window.size() < 2) {
            return null;
        }
        double distance = 0.0;
        LocationSample previous = null;
        for (LocationSample sample : window) {
            if (previous != null) {
                distance +=
                        haversineMeters(
                                previous.latitude,
                                previous.longitude,
                                sample.latitude,
                                sample.longitude);
            }
            previous = sample;
        }
        long startedAtMs = window.get(0).capturedAtMs;
        long endedAtMs = window.get(window.size() - 1).capturedAtMs;
        long durationMs = Math.max(0L, endedAtMs - startedAtMs);
        if (distance < Tunables.MIN_DISTANCE_METERS || durationMs < Tunables.MIN_DURATION_MS) {
            return null;
        }
        double maxSpeedKph = maxSpeedInWindow(telemetry, startedAtMs, endedAtMs, window);
        Confidence confidence =
                window.size() >= Tunables.OBSERVED_SAMPLE_COUNT
                        ? Confidence.OBSERVED
                        : Confidence.WEAK;
        boolean hasRoute = window.size() >= Tunables.HAS_ROUTE_SAMPLE_COUNT;
        return new Trip(
                startedAtMs,
                endedAtMs,
                distance,
                durationMs,
                maxSpeedKph,
                window.size(),
                hasRoute,
                confidence);
    }

    private static double maxSpeedInWindow(
            List<TelemetrySample> telemetry,
            long startMs,
            long endMs,
            List<LocationSample> window) {
        double max = 0.0;
        if (telemetry != null) {
            for (TelemetrySample sample : telemetry) {
                if (sample.capturedAtMs < startMs || sample.capturedAtMs > endMs) {
                    continue;
                }
                if (sample.speedKph != null && sample.speedKph > max) {
                    max = sample.speedKph;
                }
            }
        }
        // Fall back to GPS-reported speed if telemetry yielded nothing.
        if (max == 0.0) {
            for (LocationSample sample : window) {
                if (sample.speedMps != null) {
                    double kph = sample.speedMps * 3.6;
                    if (kph > max) {
                        max = kph;
                    }
                }
            }
        }
        return max;
    }

    private static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double earthMeters = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a =
                Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
                        + Math.cos(Math.toRadians(lat1))
                                * Math.cos(Math.toRadians(lat2))
                                * Math.sin(dLng / 2.0)
                                * Math.sin(dLng / 2.0);
        return earthMeters * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    }
}
