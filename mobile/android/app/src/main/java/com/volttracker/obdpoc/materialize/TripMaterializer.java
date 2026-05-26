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

        /**
         * Hard upper bound on a single trip window. Protects against pathological
         * sparse-but-continuous sessions becoming multi-day "trips" with a fictitious integrated
         * energy number. If a window exceeds this, it's split at its largest internal gap (the
         * splitting is recursive so very long runs decompose cleanly).
         */
        static final long MAX_TRIP_DURATION_MS = 12L * 60L * 60_000L;

        /** Below this distance, the window is not a real trip. */
        static final double MIN_DISTANCE_METERS = 100.0;

        /** Below this duration, the window is not a real trip. */
        static final long MIN_DURATION_MS = 30_000L;

        /** At this sample count or more the materializer reports {@link Confidence#OBSERVED}. */
        static final int OBSERVED_SAMPLE_COUNT = 10;

        /** At this sample count or more the trip is considered to have a renderable route. */
        static final int HAS_ROUTE_SAMPLE_COUNT = 5;

        /**
         * Avg speed below this is classified as {@link Trip#CLASSIFICATION_CITY}. Tuned against the
         * typical US urban surface-street average (≈40 kph including stops) with a small cushion to
         * absorb noise.
         */
        static final double CITY_AVG_SPEED_KPH = 50.0;

        /**
         * Avg speed above this is classified as {@link Trip#CLASSIFICATION_HIGHWAY}. Anything
         * between {@link #CITY_AVG_SPEED_KPH} and this is {@code mixed}.
         */
        static final double HIGHWAY_AVG_SPEED_KPH = 80.0;
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
                    appendTripsCapped(result, window, telemetry);
                    window = new ArrayList<>();
                }
            }
            window.add(sample);
        }
        if (!window.isEmpty()) {
            appendTripsCapped(result, window, telemetry);
        }
        return result;
    }

    /**
     * Adds zero or one trip per recursion. If the window exceeds {@link
     * Tunables#MAX_TRIP_DURATION_MS}, splits at the largest internal gap and recurses on both
     * halves so the cap holds even for long sparse-but-continuous runs.
     */
    private static void appendTripsCapped(
            List<Trip> result, List<LocationSample> window, List<TelemetrySample> telemetry) {
        if (window.size() < 2) {
            return;
        }
        long duration = window.get(window.size() - 1).capturedAtMs - window.get(0).capturedAtMs;
        if (duration <= Tunables.MAX_TRIP_DURATION_MS) {
            Trip trip = buildTrip(window, telemetry);
            if (trip != null) {
                result.add(trip);
            }
            return;
        }
        // Find the largest internal gap; on ties, prefer the candidate closest to the midpoint
        // so uniformly-sampled long runs split into balanced halves rather than peeling
        // single-sample prefixes off the front (which buildTrip would then drop on the
        // size < 2 check, leaking the entire body of the trip).
        int splitIndex = -1;
        long largestGap = -1L;
        int midpoint = window.size() / 2;
        for (int i = 1; i < window.size(); i++) {
            long gap = window.get(i).capturedAtMs - window.get(i - 1).capturedAtMs;
            boolean tieBreakWin =
                    gap == largestGap
                            && (splitIndex < 0
                                    || Math.abs(i - midpoint) < Math.abs(splitIndex - midpoint));
            if (gap > largestGap || tieBreakWin) {
                largestGap = gap;
                splitIndex = i;
            }
        }
        if (splitIndex <= 0) {
            // No internal gap to split on (every sample at the same ms) — accept as one trip.
            Trip trip = buildTrip(window, telemetry);
            if (trip != null) {
                result.add(trip);
            }
            return;
        }
        appendTripsCapped(result, new ArrayList<>(window.subList(0, splitIndex)), telemetry);
        appendTripsCapped(
                result, new ArrayList<>(window.subList(splitIndex, window.size())), telemetry);
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
        double avgSpeedKph =
                durationMs > 0L ? (distance / 1000.0) / (durationMs / 3_600_000.0) : 0.0;
        String classification = classify(avgSpeedKph);
        Double energyKwh = integrateEnergyKwh(telemetry, startedAtMs, endedAtMs);
        return new Trip(
                startedAtMs,
                endedAtMs,
                distance,
                durationMs,
                maxSpeedKph,
                window.size(),
                hasRoute,
                confidence,
                classification,
                energyKwh);
    }

    /**
     * Buckets the trip's average speed into a human-readable regime label. Boundaries are tuned
     * against the typical US driving mix (see {@link Tunables}); a trip with no usable speed at all
     * falls back to {@link Trip#CLASSIFICATION_UNKNOWN}.
     */
    static String classify(double avgSpeedKph) {
        if (!(avgSpeedKph > 0.0)) {
            return Trip.CLASSIFICATION_UNKNOWN;
        }
        if (avgSpeedKph < Tunables.CITY_AVG_SPEED_KPH) {
            return Trip.CLASSIFICATION_CITY;
        }
        if (avgSpeedKph >= Tunables.HIGHWAY_AVG_SPEED_KPH) {
            return Trip.CLASSIFICATION_HIGHWAY;
        }
        return Trip.CLASSIFICATION_MIXED;
    }

    /**
     * Trapezoidal integration of instantaneous pack power (V × I, in W) over time, returned in kWh.
     * Prefers {@link TelemetrySample#powerKw} when present (already kW) and falls back to computing
     * it from {@code packVoltage * packCurrentA / 1000}. Returns {@code null} when fewer than two
     * samples in the window carry power data — a single point can't form an area.
     */
    private static Double integrateEnergyKwh(
            List<TelemetrySample> telemetry, long startMs, long endMs) {
        if (telemetry == null || telemetry.isEmpty()) {
            return null;
        }
        Double prevPowerKw = null;
        Long prevAtMs = null;
        double energyKwh = 0.0;
        int integrated = 0;
        for (TelemetrySample sample : telemetry) {
            if (sample.capturedAtMs < startMs || sample.capturedAtMs > endMs) {
                continue;
            }
            Double powerKw = samplePowerKw(sample);
            if (powerKw == null) {
                continue;
            }
            if (prevPowerKw != null && prevAtMs != null) {
                double hours = (sample.capturedAtMs - prevAtMs) / 3_600_000.0;
                if (hours > 0.0) {
                    // Trapezoidal: average the two kW endpoints over the interval.
                    energyKwh += ((prevPowerKw + powerKw) / 2.0) * hours;
                    integrated += 1;
                }
            }
            prevPowerKw = powerKw;
            prevAtMs = sample.capturedAtMs;
        }
        return integrated == 0 ? null : energyKwh;
    }

    private static Double samplePowerKw(TelemetrySample sample) {
        if (sample.powerKw != null) {
            return sample.powerKw;
        }
        if (sample.packVoltage != null && sample.packCurrentA != null) {
            return sample.packVoltage * sample.packCurrentA / 1000.0;
        }
        return null;
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
