package com.volttracker.obdpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Robolectric coverage for the trip-aggregation projection in {@link ObdStoreTrips}, exercised
 * through {@link ObdLocalStore#getTripsJson(int)}. Sessions are seeded with synthetic GPS
 * telemetry; distance is verified against the haversine sum.
 *
 * <p>Reading {@code ObdStoreTrips.tripsJson}: a session only appears in the trips JSON when at
 * least one useful telemetry row exists for it. The route geometry that drives {@code
 * distanceMeters} is sourced from the {@code location_samples} table when it has rows for that
 * session, and falls back to telemetry rows that carry lat/lng — which is what these tests rely on
 * so they can drive both readings in one call.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ObdStoreTripsDbTest {

    private ObdLocalStore store;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        store = new ObdLocalStore(context);
        store.clearAllData();
    }

    @After
    public void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    /**
     * Builds a telemetry sample with GPS, speed and a source marker so the row is counted as
     * "useful" by {@link ObdStoreSupport#isUsefulTelemetry(JSONObject)}.
     */
    private static JSONObject gpsSample(int speedKph, double lat, double lng, long atMs)
            throws Exception {
        JSONObject sample = new JSONObject();
        sample.put("source", "obd");
        sample.put("speedKph", speedKph);
        sample.put("rpm", 1500);
        sample.put("latitude", lat);
        sample.put("longitude", lng);
        sample.put("accuracyM", 5.0);
        sample.put("updatedAt", atMs);
        return sample;
    }

    /** Haversine distance between two lat/lng points, in meters. Mirrors the production formula. */
    private static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double earthMeters = 6371000d;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a =
                Math.sin(dLat / 2d) * Math.sin(dLat / 2d)
                        + Math.cos(Math.toRadians(lat1))
                                * Math.cos(Math.toRadians(lat2))
                                * Math.sin(dLng / 2d)
                                * Math.sin(dLng / 2d);
        return earthMeters * 2d * Math.atan2(Math.sqrt(a), Math.sqrt(1d - a));
    }

    // ---- empty store ---------------------------------------------------------------

    @Test
    public void emptyStoreReturnsEmptyTripsArray() {
        JSONArray trips = store.getTripsJson(40);
        assertNotNull(trips);
        assertEquals(0, trips.length());
    }

    // ---- session shape -------------------------------------------------------------

    @Test
    public void sessionWithNoUsefulTelemetryIsNotReportedAsATrip() {
        // A session that never logged any telemetry was a failed connection, not a trip;
        // the production code (ObdStoreTrips#tripJson) returns null in that case.
        long id = store.startSession("obd", "00:11", "Adapter");
        store.finishSession(id, ObdLocalStore.STATUS_ERROR, 2000L, "");

        JSONArray trips = store.getTripsJson(40);
        assertEquals(0, trips.length());
    }

    @Test
    public void multiSampleSessionReportsHaversineDistance() throws Exception {
        long id = store.startSession("obd", "00:11", "Adapter");
        // Three points along the same longitude — ~1.1 km between each consecutive lat step.
        double[][] route = {
            {32.7000, -117.1000},
            {32.7100, -117.1000},
            {32.7200, -117.1000},
        };
        store.recordTelemetry(id, gpsSample(40, route[0][0], route[0][1], 1000L));
        store.recordTelemetry(id, gpsSample(55, route[1][0], route[1][1], 2000L));
        store.recordTelemetry(id, gpsSample(60, route[2][0], route[2][1], 3000L));
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 4000L, "");

        double expected =
                haversineMeters(route[0][0], route[0][1], route[1][0], route[1][1])
                        + haversineMeters(route[1][0], route[1][1], route[2][0], route[2][1]);

        JSONArray trips = store.getTripsJson(40);
        assertEquals(1, trips.length());
        JSONObject trip = trips.getJSONObject(0);
        // distanceMeters should match the haversine sum to within a meter (no smoothing
        // or projection differences should creep in for a 2 km synthetic route).
        assertEquals(expected, trip.optDouble("distanceMeters"), 1.0);
        assertEquals(60, trip.optInt("maxSpeedKph"));
        assertEquals(3, trip.optInt("sampleCount"));
        assertTrue("3 GPS points should render as a route", trip.optBoolean("hasRoute"));
    }

    @Test
    public void zeroMovementSessionHasZeroDistance() throws Exception {
        long id = store.startSession("obd", "00:11", "Adapter");
        // All samples at the exact same coordinate — distance must be 0.
        store.recordTelemetry(id, gpsSample(0, 32.70, -117.10, 1000L));
        store.recordTelemetry(id, gpsSample(0, 32.70, -117.10, 2000L));
        store.recordTelemetry(id, gpsSample(0, 32.70, -117.10, 3000L));
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 4000L, "");

        JSONArray trips = store.getTripsJson(40);
        assertEquals(1, trips.length());
        JSONObject trip = trips.getJSONObject(0);
        assertEquals(0d, trip.optDouble("distanceMeters"), 0.001);
        assertEquals(0, trip.optInt("maxSpeedKph"));
    }

    @Test
    public void twoSessionsBothAppearInTripsJson() throws Exception {
        long first = store.startSession("obd", "00:11", "Adapter A");
        store.recordTelemetry(first, gpsSample(40, 32.70, -117.10, 1000L));
        store.recordTelemetry(first, gpsSample(50, 32.71, -117.10, 2000L));
        store.finishSession(first, ObdLocalStore.STATUS_COMPLETE, 3000L, "");

        long second = store.startSession("obd", "00:22", "Adapter B");
        store.recordTelemetry(second, gpsSample(60, 33.00, -118.00, 10000L));
        store.recordTelemetry(second, gpsSample(70, 33.01, -118.00, 11000L));
        store.finishSession(second, ObdLocalStore.STATUS_COMPLETE, 12000L, "");

        JSONArray trips = store.getTripsJson(40);
        assertEquals(2, trips.length());

        // Recent sessions come back DESC by started_at; surface that both trip ids land here.
        boolean sawFirst = false;
        boolean sawSecond = false;
        for (int i = 0; i < trips.length(); i++) {
            long tripId = trips.getJSONObject(i).optLong("id");
            if (tripId == first) sawFirst = true;
            if (tripId == second) sawSecond = true;
        }
        assertTrue("first session must appear in trips JSON", sawFirst);
        assertTrue("second session must appear in trips JSON", sawSecond);
    }

    @Test
    public void getTripsJsonRespectsTheLimitArgument() throws Exception {
        // Three real driving sessions; ask for only one.
        for (int i = 0; i < 3; i++) {
            long id = store.startSession("obd", "00:0" + i, "Adapter " + i);
            store.recordTelemetry(id, gpsSample(30, 32.70 + (i * 0.01), -117.10, 1000L + i));
            store.recordTelemetry(id, gpsSample(40, 32.71 + (i * 0.01), -117.10, 2000L + i));
            store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 3000L + i, "");
        }
        JSONArray trips = store.getTripsJson(1);
        assertEquals(1, trips.length());
    }
}
