package com.volttracker.obdpoc.materialize;

import static org.junit.Assert.assertEquals;
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
