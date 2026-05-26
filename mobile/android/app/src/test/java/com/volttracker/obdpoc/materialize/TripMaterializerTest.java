package com.volttracker.obdpoc.materialize;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Unit tests for {@link TripMaterializer}. Uses an in-memory {@link MaterializerData} stub so the
 * tests stay fast and don't drag in Robolectric.
 */
public class TripMaterializerTest {

    private static final long T_BASE = 1_700_000_000_000L;
    private static final long ONE_SECOND_MS = 1_000L;
    private static final long ONE_MINUTE_MS = 60_000L;

    @Test
    public void emptyInputProducesEmptyList() {
        MaterializerInput input = new MaterializerInput(1L, T_BASE, T_BASE + 10_000L);
        StubData data = new StubData();

        List<Trip> trips = TripMaterializer.materialize(input, data);

        assertEquals(0, trips.size());
    }

    @Test
    public void shortDistanceProducesNoTrip() {
        // Two samples ~50 m apart over 30 s — below the 100 m minimum.
        StubData data = new StubData();
        data.locations.add(loc(T_BASE, 32.700000, -117.100000));
        data.locations.add(loc(T_BASE + 30 * ONE_SECOND_MS, 32.700450, -117.100000));

        List<Trip> trips = TripMaterializer.materialize(input(data), data);

        assertEquals(0, trips.size());
    }

    @Test
    public void continuousSegmentBecomesOneTrip() {
        // Six samples 30 s apart covering ~1 km. Should be one trip whose duration ~ 150 s.
        StubData data = new StubData();
        double startLat = 32.700000;
        for (int i = 0; i < 6; i++) {
            data.locations.add(
                    loc(T_BASE + i * 30 * ONE_SECOND_MS, startLat + i * 0.002, -117.100000));
        }

        List<Trip> trips = TripMaterializer.materialize(input(data), data);

        assertEquals(1, trips.size());
        Trip trip = trips.get(0);
        assertEquals(T_BASE, trip.startedAtMs);
        assertEquals(T_BASE + 150 * ONE_SECOND_MS, trip.endedAtMs);
        assertEquals(150 * ONE_SECOND_MS, trip.durationMs);
        assertTrue("distance should clear 1 km", trip.distanceMeters > 1000.0);
        assertEquals(6, trip.sampleCount);
        assertTrue("six samples is enough for a route", trip.hasRoute);
    }

    @Test
    public void gapOverThresholdProducesTwoTrips() {
        // Five samples, ~10 minute gap, then five more — must split.
        StubData data = new StubData();
        double startLat = 32.700000;
        for (int i = 0; i < 5; i++) {
            data.locations.add(
                    loc(T_BASE + i * 30 * ONE_SECOND_MS, startLat + i * 0.002, -117.100000));
        }
        long resumeAtMs = T_BASE + 5 * 30 * ONE_SECOND_MS + 10 * ONE_MINUTE_MS;
        for (int i = 0; i < 5; i++) {
            data.locations.add(
                    loc(
                            resumeAtMs + i * 30 * ONE_SECOND_MS,
                            startLat + 0.020 + i * 0.002,
                            -117.100000));
        }

        List<Trip> trips = TripMaterializer.materialize(input(data), data);

        assertEquals(2, trips.size());
        assertTrue(trips.get(0).endedAtMs < trips.get(1).startedAtMs);
    }

    @Test
    public void confidenceTracksSampleCount() {
        // Six samples → WEAK (>=5 has route, <10 still WEAK).
        StubData few = new StubData();
        for (int i = 0; i < 6; i++) {
            few.locations.add(loc(T_BASE + i * 30 * ONE_SECOND_MS, 32.7 + i * 0.002, -117.1));
        }
        // Twelve samples → OBSERVED.
        StubData many = new StubData();
        for (int i = 0; i < 12; i++) {
            many.locations.add(loc(T_BASE + i * 30 * ONE_SECOND_MS, 32.7 + i * 0.002, -117.1));
        }

        Trip weak = TripMaterializer.materialize(input(few), few).get(0);
        Trip strong = TripMaterializer.materialize(input(many), many).get(0);

        assertEquals(Confidence.WEAK, weak.confidence);
        assertEquals(Confidence.OBSERVED, strong.confidence);
    }

    @Test
    public void maxSpeedComesFromTelemetryWithinWindow() {
        StubData data = new StubData();
        for (int i = 0; i < 6; i++) {
            data.locations.add(loc(T_BASE + i * 30 * ONE_SECOND_MS, 32.7 + i * 0.002, -117.1));
        }
        // Telemetry spans the same window with a spike in the middle.
        data.telemetry.add(tel(T_BASE + 10 * ONE_SECOND_MS, 35.0));
        data.telemetry.add(tel(T_BASE + 70 * ONE_SECOND_MS, 92.0));
        data.telemetry.add(tel(T_BASE + 130 * ONE_SECOND_MS, 40.0));
        // Telemetry well outside the window should NOT influence max.
        data.telemetry.add(tel(T_BASE + 10 * 60 * ONE_SECOND_MS, 250.0));

        Trip trip = TripMaterializer.materialize(input(data), data).get(0);

        assertEquals(92.0, trip.maxSpeedKph, 0.001);
    }

    @Test
    public void energyKwhIntegratesOverWindow() {
        // Twelve location samples 30s apart so the trip materializes; the energy integral
        // is driven entirely off the telemetry's powerKw points. Constant 10 kW over 6 min
        // = 1.0 kWh.
        StubData data = new StubData();
        for (int i = 0; i < 13; i++) {
            data.locations.add(loc(T_BASE + i * 30 * ONE_SECOND_MS, 32.7 + i * 0.002, -117.1));
        }
        for (int i = 0; i < 13; i++) {
            data.telemetry.add(telWithPower(T_BASE + i * 30 * ONE_SECOND_MS, 60.0, 10.0));
        }

        Trip trip = TripMaterializer.materialize(input(data), data).get(0);

        assertNotNull(
                "energy_kwh should be populated when pack power is available", trip.energyKwh);
        // 13 samples 30s apart = 12 intervals * 30s = 360s = 0.1 h, * 10 kW = 1.0 kWh.
        assertEquals(1.0, trip.energyKwh, 0.001);
    }

    @Test
    public void singleLocationSampleProducesNoTrip() {
        // A single sample can't form a segment — no distance, no duration.
        StubData data = new StubData();
        data.locations.add(loc(T_BASE, 32.700000, -117.100000));

        List<Trip> trips = TripMaterializer.materialize(input(data), data);

        assertEquals(0, trips.size());
    }

    @Test
    public void allZeroSpeedStationaryFixesProduceNoTrip() {
        // Six samples covering ~150s but all at the same coordinates — zero distance,
        // below MIN_DISTANCE_METERS. Conservative behavior: drop, don't fabricate.
        StubData data = new StubData();
        for (int i = 0; i < 6; i++) {
            data.locations.add(loc(T_BASE + i * 30 * ONE_SECOND_MS, 32.700000, -117.100000));
        }
        // Telemetry also reports zero speed throughout.
        for (int i = 0; i < 6; i++) {
            data.telemetry.add(tel(T_BASE + i * 30 * ONE_SECOND_MS, 0.0));
        }

        List<Trip> trips = TripMaterializer.materialize(input(data), data);

        assertEquals(0, trips.size());
    }

    @Test
    public void allNullPowerYieldsTripWithoutEnergy() {
        // A real trip with no usable power data anywhere — energyKwh stays null
        // (we don't fabricate; an absent integral is "unknown", not zero).
        StubData data = new StubData();
        for (int i = 0; i < 6; i++) {
            data.locations.add(
                    loc(T_BASE + i * 30 * ONE_SECOND_MS, 32.700000 + i * 0.002, -117.100000));
        }
        // Telemetry has no power columns populated.
        for (int i = 0; i < 6; i++) {
            data.telemetry.add(tel(T_BASE + i * 30 * ONE_SECOND_MS, 40.0));
        }

        List<Trip> trips = TripMaterializer.materialize(input(data), data);

        assertEquals(1, trips.size());
        assertEquals(
                "energy_kwh stays null when no power samples are available",
                null,
                trips.get(0).energyKwh);
    }

    @Test
    public void uniformlySampledRunOverCapSplitsBalancedInsteadOfPeelingPrefixes() {
        // Regression: with `if (gap > largestGap)` ties preserve splitIndex=1, so a
        // uniformly-sampled long run would recursively peel single-sample prefixes that
        // buildTrip drops (size < 2), leaking the entire body of the trip into thin air.
        // The fix (midpoint-preferring tie-break) must produce multiple cap-obeying trips.
        StubData data = new StubData();
        long sampleInterval = 5_000L;
        // 18 hours of perfectly uniform samples — every gap is exactly sampleInterval.
        int count = (int) ((18L * 60L * 60_000L) / sampleInterval); // 12_960 samples
        double startLat = 32.700000;
        for (int i = 0; i < count; i++) {
            data.locations.add(
                    loc(T_BASE + i * sampleInterval, startLat + i * 0.00001, -117.100000));
        }

        List<Trip> trips = TripMaterializer.materialize(input(data), data);

        assertTrue(
                "uniform 18h run must split into multiple trips (got " + trips.size() + ")",
                trips.size() >= 2);
        for (Trip trip : trips) {
            assertTrue(
                    "trip duration " + trip.durationMs + " exceeds MAX_TRIP_DURATION_MS",
                    trip.durationMs <= 12L * 60L * 60_000L);
        }
        // Sum of trip durations should be close to the full 18h span (within one
        // sampleInterval per split — the splitting drops the boundary edge gap, not the
        // sample). With ~2 splits and 5s intervals, the gap is < 1% of total — assert >95%.
        long totalSpan = (long) (count - 1) * sampleInterval;
        long materializedMs = 0L;
        for (Trip trip : trips) {
            materializedMs += trip.durationMs;
        }
        assertTrue(
                "materialized "
                        + materializedMs
                        + "ms of "
                        + totalSpan
                        + "ms should cover >95% (prevents single-sample-peel leak)",
                materializedMs * 100 > totalSpan * 95);
    }

    @Test
    public void runOverMaxTripDurationSplitsAtLargestGap() {
        // Build a single continuous run that exceeds MAX_TRIP_DURATION_MS (12h).
        // Use 5-second samples (well under MAX_GAP_MS so it stays one window in the
        // first pass), with a single deliberately-larger 4-minute gap in the middle.
        StubData data = new StubData();
        long sampleInterval = 5_000L;
        // Half a day of samples on each side of the larger gap.
        long firstHalfStart = T_BASE;
        int firstHalfCount = (int) ((6L * 60L * 60_000L) / sampleInterval); // 4320 samples
        double startLat = 32.700000;
        for (int i = 0; i < firstHalfCount; i++) {
            data.locations.add(
                    loc(firstHalfStart + i * sampleInterval, startLat + i * 0.00001, -117.100000));
        }
        long largerGap = 4L * 60_000L;
        long secondHalfStart =
                firstHalfStart + firstHalfCount * sampleInterval - sampleInterval + largerGap;
        int secondHalfCount = (int) ((7L * 60L * 60_000L) / sampleInterval); // 5040 samples
        double secondStartLat = startLat + firstHalfCount * 0.00001 + 0.01;
        for (int i = 0; i < secondHalfCount; i++) {
            data.locations.add(
                    loc(
                            secondHalfStart + i * sampleInterval,
                            secondStartLat + i * 0.00001,
                            -117.100000));
        }

        List<Trip> trips = TripMaterializer.materialize(input(data), data);

        assertTrue(
                "13h continuous run must split into at least 2 trips at the largest gap",
                trips.size() >= 2);
        // Every trip must obey the cap.
        for (Trip trip : trips) {
            assertTrue(
                    "trip duration " + trip.durationMs + " exceeds MAX_TRIP_DURATION_MS",
                    trip.durationMs <= 12L * 60L * 60_000L);
        }
    }

    @Test
    public void classificationFromAverageSpeed() {
        // ~20 kph avg → city.
        assertEquals(Trip.CLASSIFICATION_CITY, TripMaterializer.classify(20.0));
        // 50–80 kph → mixed.
        assertEquals(Trip.CLASSIFICATION_MIXED, TripMaterializer.classify(65.0));
        // ≥80 kph → highway.
        assertEquals(Trip.CLASSIFICATION_HIGHWAY, TripMaterializer.classify(95.0));
        // Zero or negative → unknown.
        assertEquals(Trip.CLASSIFICATION_UNKNOWN, TripMaterializer.classify(0.0));
    }

    // ---- helpers ------------------------------------------------------------------

    private static MaterializerInput input(StubData data) {
        long start = data.locations.isEmpty() ? T_BASE : data.locations.get(0).capturedAtMs;
        long end =
                data.locations.isEmpty()
                        ? T_BASE
                        : data.locations.get(data.locations.size() - 1).capturedAtMs;
        return new MaterializerInput(1L, start, end);
    }

    private static LocationSample loc(long atMs, double lat, double lng) {
        return new LocationSample(atMs, lat, lng, null, 5.0f);
    }

    private static TelemetrySample tel(long atMs, double speedKph) {
        return new TelemetrySample(atMs, speedKph, 1500, 12.4, null, null);
    }

    private static TelemetrySample telWithPower(long atMs, double speedKph, double powerKw) {
        // 8-arg constructor lets us provide powerKw directly so the energy integration runs.
        return new TelemetrySample(atMs, speedKph, 1500, 12.4, 360.0, 0.0, powerKw, null);
    }

    static final class StubData implements MaterializerData {
        final List<LocationSample> locations = new ArrayList<>();
        final List<TelemetrySample> telemetry = new ArrayList<>();
        final List<PidObservation> pids = new ArrayList<>();

        @Override
        public List<LocationSample> readLocationSamples(long sessionId) {
            return Collections.unmodifiableList(locations);
        }

        @Override
        public List<PidObservation> readPidObservations(long sessionId) {
            return Collections.unmodifiableList(pids);
        }

        @Override
        public List<TelemetrySample> readTelemetrySamples(long sessionId) {
            return Collections.unmodifiableList(telemetry);
        }
    }
}
