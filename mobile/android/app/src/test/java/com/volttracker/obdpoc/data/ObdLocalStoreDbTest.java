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
        store.recordDiagnosticCode(id, diagnosticCode("P25A2", "stored", 1000));
        store.clearAllData();

        JSONObject summary = store.getStorageSummary();
        assertEquals(0, summary.optInt("sessionCount"));
        assertEquals(0L, summary.optLong("sampleCount"));
        assertEquals(0L, summary.optLong("diagnosticCodeCount"));
    }

    @Test
    public void diagnosticCodesTrackFirstAndLastSeen() throws Exception {
        long id = store.startSession("scan", "00:11", "Adapter");
        store.recordDiagnosticCode(id, diagnosticCode("P25A2", "stored", 1000));
        long nextId = store.startSession("scan", "00:11", "Adapter");
        store.recordDiagnosticCode(nextId, diagnosticCode("P25A2", "stored", 2000));
        store.recordDiagnosticCode(nextId, diagnosticCode("U0073", "pending", 2000));

        JSONObject summary = store.getStorageSummary();
        assertEquals(2L, summary.optLong("diagnosticCodeCount"));
        JSONObject statusCounts = summary.getJSONObject("diagnosticCodeStatusCounts");
        assertEquals(1L, statusCounts.optLong("stored"));
        assertEquals(1L, statusCounts.optLong("pending"));
        JSONArray codes = summary.getJSONArray("latestDiagnosticCodes");
        assertEquals(2, codes.length());
        JSONObject code = null;
        for (int i = 0; i < codes.length(); i++) {
            JSONObject candidate = codes.getJSONObject(i);
            if ("P25A2".equals(candidate.optString("dtc"))) {
                code = candidate;
                break;
            }
        }
        assertNotNull(code);
        assertEquals("P25A2", code.optString("dtc"));
        assertEquals("stored", code.optString("status"));
        assertEquals(1000L, code.optLong("firstSeenMs"));
        assertEquals(2000L, code.optLong("lastSeenMs"));
        assertEquals(2L, code.optLong("seenCount"));
    }

    @Test
    public void diagnosticCodesDoNotDoubleCountWithinOneScan() throws Exception {
        long id = store.startSession("scan", "00:11", "Adapter");
        store.recordDiagnosticCode(id, diagnosticCode("U0073", "stored", 1000));
        store.recordDiagnosticCode(id, diagnosticCode("U0073", "stored", 1200));

        JSONObject code = store.getStorageSummary()
                .getJSONArray("latestDiagnosticCodes").getJSONObject(0);
        assertEquals("U0073", code.optString("dtc"));
        assertEquals(1L, code.optLong("seenCount"));
        assertEquals(1200L, code.optLong("lastSeenMs"));
    }

    // ---- GPS route building (the data the map renders) -----------------------------

    private void locationSample(long sessionId, long atMs, double lat, double lng, Double accuracyM) {
        store.recordLocationSample(
                sessionId, atMs, "gps", lat, lng, accuracyM, null, null, null, null, null);
    }

    private static JSONObject diagnosticCode(String dtc, String status, long seenAtMs) throws Exception {
        JSONObject code = new JSONObject();
        code.put("dtc", dtc);
        code.put("status", status);
        code.put("statusLabel", "Stored/current");
        code.put("moduleKey", "generic-obd");
        code.put("moduleName", "ECM / powertrain (generic OBD-II)");
        code.put("seenAtMs", seenAtMs);
        code.put("rawResponse", "43 25 A2 00 00");
        return code;
    }

    @Test
    public void recordedLocationSamplesBuildARecentRoute() throws Exception {
        long id = store.startSession("obd", "00:11", "Adapter");
        locationSample(id, 1000L, 32.70, -117.10, 5.0);
        locationSample(id, 2000L, 32.71, -117.10, 5.0);
        locationSample(id, 3000L, 32.72, -117.10, 5.0);

        JSONArray routes = store.getStorageSummary().getJSONArray("recentRoutes");
        assertEquals(1, routes.length());
        JSONObject route = routes.getJSONObject(0);
        assertEquals(3, route.optInt("pointCount"));
        // ~0.02 deg of latitude is a little over 2 km
        assertTrue("route should span ~2 km", route.optDouble("distanceMeters") > 2000);
    }

    @Test
    public void aSinglePointDoesNotRenderAsARoute() throws Exception {
        long id = store.startSession("obd", "00:11", "Adapter");
        locationSample(id, 1000L, 32.70, -117.10, 5.0);

        assertEquals(0, store.getStorageSummary().getJSONArray("recentRoutes").length());
    }

    @Test
    public void routePointsAreReturnedInChronologicalOrder() throws Exception {
        long id = store.startSession("obd", "00:11", "Adapter");
        // inserted out of order; the route must still render oldest-first
        locationSample(id, 3000L, 32.72, -117.10, 5.0);
        locationSample(id, 1000L, 32.70, -117.10, 5.0);
        locationSample(id, 2000L, 32.71, -117.10, 5.0);

        JSONArray points = store.getStorageSummary()
                .getJSONArray("recentRoutes").getJSONObject(0).getJSONArray("points");
        assertEquals(1000L, points.getJSONObject(0).optLong("atMs"));
        assertEquals(2000L, points.getJSONObject(1).optLong("atMs"));
        assertEquals(3000L, points.getJSONObject(2).optLong("atMs"));
    }

    @Test
    public void routePointsCarryAccuracyForRendering() throws Exception {
        long id = store.startSession("obd", "00:11", "Adapter");
        locationSample(id, 1000L, 32.70, -117.10, 7.5);
        locationSample(id, 2000L, 32.71, -117.10, 9.0);

        JSONArray points = store.getStorageSummary()
                .getJSONArray("recentRoutes").getJSONObject(0).getJSONArray("points");
        assertEquals(7.5, points.getJSONObject(0).optDouble("accuracyM"), 0.01);
        assertEquals(9.0, points.getJSONObject(1).optDouble("accuracyM"), 0.01);
    }
}
