package com.volttracker.obdpoc.data;

import static org.junit.Assert.assertEquals;
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
 * Exercises the real SQLite data layer via Robolectric: writes, the on-read trip and
 * insight aggregation, and clearAllData. Previously this layer had no test coverage.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ObdLocalStoreDbTest {

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

    private static JSONObject sample(int speedKph, int rpm, double lat, double lng, long atMs)
            throws Exception {
        JSONObject sample = new JSONObject();
        sample.put("source", "obd");
        sample.put("speedKph", speedKph);
        sample.put("rpm", rpm);
        sample.put("latitude", lat);
        sample.put("longitude", lng);
        sample.put("updatedAt", atMs);
        return sample;
    }

    @Test
    public void startSessionShowsInStorageSummary() {
        store.startSession("obd", "00:11:22:33", "Adapter");
        assertEquals(1, store.getStorageSummary().optInt("sessionCount"));
    }

    @Test
    public void recordTelemetryCountsUsefulSamples() throws Exception {
        long id = store.startSession("obd", "00:11", "Adapter");
        store.recordTelemetry(id, sample(40, 1500, 32.70, -117.10, 1000));
        store.recordTelemetry(id, sample(45, 1550, 32.71, -117.10, 2000));
        assertEquals(2L, store.getStorageSummary().optLong("sampleCount"));
    }

    @Test
    public void emptyTelemetryIsRejected() {
        long id = store.startSession("obd", "00:11", "Adapter");
        assertEquals(-1L, store.recordTelemetry(id, new JSONObject()));
        assertEquals(0L, store.getStorageSummary().optLong("sampleCount"));
    }

    @Test
    public void getTripsComputesDistanceAndMaxSpeed() throws Exception {
        long id = store.startSession("obd", "00:11", "Adapter");
        store.recordTelemetry(id, sample(50, 1500, 32.70, -117.10, 1000));
        store.recordTelemetry(id, sample(90, 1800, 32.75, -117.10, 2000));
        store.recordTelemetry(id, sample(60, 1600, 32.80, -117.10, 3000));
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 4000, "");

        JSONArray trips = store.getTripsJson(10);
        assertEquals(1, trips.length());
        JSONObject trip = trips.getJSONObject(0);
        assertEquals(90, trip.optInt("maxSpeedKph"));
        assertTrue("route should span ~11 km", trip.optDouble("distanceMeters") > 10000);
        assertTrue(trip.optBoolean("hasRoute"));
        assertEquals(3, trip.optInt("sampleCount"));
    }

    @Test
    public void getInsightsAggregatesAcrossTrips() throws Exception {
        long id = store.startSession("obd", "00:11", "Adapter");
        store.recordTelemetry(id, sample(50, 1500, 32.70, -117.10, 1000));
        store.recordTelemetry(id, sample(70, 1700, 32.71, -117.10, 2000));
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 3000, "");

        JSONObject insights = store.getInsightsJson();
        assertEquals(1, insights.optInt("tripCount"));
        assertEquals(70, insights.optInt("maxSpeedKph"));
        assertTrue(insights.optDouble("totalDistanceMeters") > 0);
    }

    @Test
    public void demoSessionsAreExcludedFromTrips() throws Exception {
        long id = store.startSession("demo", "", "Demo");
        store.recordTelemetry(id, sample(30, 0, 32.70, -117.10, 1000));
        assertEquals(0, store.getTripsJson(10).length());
    }

    @Test
    public void clearAllDataEmptiesTheDatabase() throws Exception {
        long id = store.startSession("obd", "00:11", "Adapter");
        store.recordTelemetry(id, sample(40, 1500, 32.70, -117.10, 1000));
        store.clearAllData();

        JSONObject summary = store.getStorageSummary();
        assertEquals(0, summary.optInt("sessionCount"));
        assertEquals(0L, summary.optLong("sampleCount"));
    }
}
