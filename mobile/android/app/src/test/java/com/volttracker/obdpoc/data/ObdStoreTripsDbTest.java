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

    private static JSONObject inactiveGpsSample(double lat, double lng, long atMs)
            throws Exception {
        JSONObject sample = new JSONObject();
        sample.put("source", "obd");
        sample.put("speedKph", 0);
        sample.put("rpm", 0);
        sample.put("voltage", 0.0);
        sample.put("powerKw", 0.0);
        sample.put("latitude", lat);
        sample.put("longitude", lng);
        sample.put("accuracyM", 5.0);
        sample.put("updatedAt", atMs);
        return sample;
    }

    private static JSONObject obdSampleNoGps(int speedKph, long atMs) throws Exception {
        JSONObject sample = new JSONObject();
        sample.put("source", "obd");
        sample.put("speedKph", speedKph);
        sample.put("rpm", 1500);
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

    private void addMovingTelemetryRun(
            long sessionId, long baseMs, int startMinute, int endMinute, double startLat)
            throws Exception {
        long minuteMs = 60_000L;
        for (int minute = startMinute; minute <= endMinute; minute++) {
            store.recordTelemetry(
                    sessionId,
                    gpsSample(
                            35,
                            startLat + (minute - startMinute) * 0.002,
                            -117.1000,
                            baseMs + minute * minuteMs));
        }
    }

    private void addStoppedTelemetryRun(
            long sessionId, long baseMs, int startMinute, int endMinute, double lat)
            throws Exception {
        long minuteMs = 60_000L;
        for (int minute = startMinute; minute <= endMinute; minute++) {
            store.recordTelemetry(
                    sessionId, inactiveGpsSample(lat, -117.1000, baseMs + minute * minuteMs));
        }
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
    public void sourceOnlyTelemetryIsNotReportedAsATrip() throws Exception {
        long id = store.startSession("obd", "00:11", "Adapter");
        JSONObject emptyPoll = new JSONObject();
        emptyPoll.put("source", "obd");
        emptyPoll.put("raw", "NO DATA");
        emptyPoll.put("updatedAt", 1000L);
        store.recordTelemetry(id, emptyPoll);
        store.finishSession(id, ObdLocalStore.STATUS_ERROR, 2000L, "");

        assertEquals(0, store.getTripsJson(40).length());
        assertEquals(
                0L,
                com.volttracker.obdpoc.StorageSummaryJson.build(store.getStorageSummaryRecord())
                        .optLong("sampleCount"));
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
    public void tripDurationExcludesSustainedInactiveTail() throws Exception {
        long startMs = 1_000_000L;
        long minuteMs = 60_000L;
        long id = store.startSession("obd", "00:11", "Adapter", startMs);
        double startLat = 32.7000;
        for (int i = 0; i <= 5; i++) {
            store.recordTelemetry(
                    id, gpsSample(35, startLat + i * 0.002, -117.1000, startMs + i * minuteMs));
        }
        for (int i = 6; i <= 16; i++) {
            store.recordTelemetry(
                    id, inactiveGpsSample(startLat + 5 * 0.002, -117.1000, startMs + i * minuteMs));
        }
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, startMs + 17 * minuteMs, "");

        JSONArray trips = store.getTripsJson(40);

        assertEquals(1, trips.length());
        JSONObject trip = trips.getJSONObject(0);
        assertEquals(startMs, trip.optLong("startedAtMs"));
        assertEquals(startMs + 5 * minuteMs, trip.optLong("endedAtMs"));
        assertEquals(5 * minuteMs, trip.optLong("durationMs"));
    }

    @Test
    public void getTripsSplitsOneSessionIntoMultipleDriveWindows() throws Exception {
        long startMs = 2_000_000L;
        long minuteMs = 60_000L;
        long id = store.startSession("obd", "00:11", "Adapter", startMs);
        addMovingTelemetryRun(id, startMs, 0, 5, 32.7000);
        addStoppedTelemetryRun(id, startMs, 6, 12, 32.7100);
        addMovingTelemetryRun(id, startMs, 13, 18, 32.7200);
        addStoppedTelemetryRun(id, startMs, 19, 23, 32.7300);
        addMovingTelemetryRun(id, startMs, 24, 29, 32.7400);
        addStoppedTelemetryRun(id, startMs, 30, 40, 32.7500);
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, startMs + 41 * minuteMs, "");

        JSONArray trips = store.getTripsJson(40);

        assertEquals(3, trips.length());
        JSONObject newest = trips.getJSONObject(0);
        JSONObject middle = trips.getJSONObject(1);
        JSONObject oldest = trips.getJSONObject(2);
        assertEquals(startMs + 24 * minuteMs, newest.optLong("startedAtMs"));
        assertEquals(startMs + 29 * minuteMs, newest.optLong("endedAtMs"));
        assertEquals(startMs + 13 * minuteMs, middle.optLong("startedAtMs"));
        assertEquals(startMs + 18 * minuteMs, middle.optLong("endedAtMs"));
        assertEquals(startMs, oldest.optLong("startedAtMs"));
        assertEquals(startMs + 5 * minuteMs, oldest.optLong("endedAtMs"));
        assertEquals(id, newest.optLong("sessionId"));

        JSONObject route = store.getTripRouteJson(newest.optString("id"));
        JSONArray points = route.optJSONArray("points");
        assertNotNull(points);
        assertTrue(points.length() >= 2);
        assertEquals(startMs + 24 * minuteMs, points.getJSONObject(0).optLong("atMs"));
        assertEquals(
                startMs + 29 * minuteMs, points.getJSONObject(points.length() - 1).optLong("atMs"));
    }

    @Test
    public void telemetryOnlyTripDurationUsesSampleBounds() throws Exception {
        long id = store.startSession("obd", "00:11", "Adapter", 1000L);
        store.recordTelemetry(id, obdSampleNoGps(30, 2000L));
        store.recordTelemetry(id, obdSampleNoGps(35, 3000L));
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 1_000_000L, "");

        JSONArray trips = store.getTripsJson(40);

        assertEquals(1, trips.length());
        JSONObject trip = trips.getJSONObject(0);
        assertEquals(2000L, trip.optLong("startedAtMs"));
        assertEquals(3000L, trip.optLong("endedAtMs"));
        assertEquals(1000L, trip.optLong("durationMs"));
        assertEquals(0, trip.optInt("pointCount"));
    }

    @Test
    public void getTripsSortsSplitWindowsGloballyBeforeApplyingLimit() throws Exception {
        long baseMs = 3_000_000L;
        long minuteMs = 60_000L;
        long sessionA = store.startSession("obd", "00:11", "Adapter A", baseMs);
        store.recordTelemetry(sessionA, gpsSample(35, 32.7000, -117.1000, baseMs));
        store.recordTelemetry(sessionA, gpsSample(35, 32.7020, -117.1000, baseMs + minuteMs));
        store.recordTelemetry(
                sessionA, inactiveGpsSample(32.7020, -117.1000, baseMs + 2 * minuteMs));
        store.recordTelemetry(
                sessionA, inactiveGpsSample(32.7020, -117.1000, baseMs + 7 * minuteMs));
        store.recordTelemetry(sessionA, gpsSample(35, 32.7200, -117.1000, baseMs + 10 * minuteMs));
        store.recordTelemetry(sessionA, gpsSample(35, 32.7220, -117.1000, baseMs + 11 * minuteMs));
        store.finishSession(sessionA, ObdLocalStore.STATUS_COMPLETE, baseMs + 12 * minuteMs, "");

        long sessionB = store.startSession("obd", "00:22", "Adapter B", baseMs - 1000L);
        store.recordTelemetry(sessionB, gpsSample(35, 33.0000, -118.0000, baseMs + 9 * minuteMs));
        store.recordTelemetry(
                sessionB, gpsSample(35, 33.0020, -118.0000, baseMs + 9 * minuteMs + 30_000L));
        store.finishSession(sessionB, ObdLocalStore.STATUS_COMPLETE, baseMs + 10 * minuteMs, "");

        JSONArray trips = store.getTripsJson(2);

        assertEquals(2, trips.length());
        assertEquals(sessionA, trips.getJSONObject(0).optLong("sessionId"));
        assertEquals(baseMs + 11 * minuteMs, trips.getJSONObject(0).optLong("endedAtMs"));
        assertEquals(sessionB, trips.getJSONObject(1).optLong("sessionId"));
        assertEquals(baseMs + 9 * minuteMs + 30_000L, trips.getJSONObject(1).optLong("endedAtMs"));
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
            long tripId = trips.getJSONObject(i).optLong("sessionId");
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

    @Test
    public void longSessionRouteIsDownsampledAcrossFullTimespan() throws Exception {
        // Regression: before fix, a session with > 500 GPS samples only surfaced its last 500
        // points on the dashboard map, hiding the beginning of every long drive.
        long id = store.startSession("obd", "00:11", "Adapter");
        int totalSamples = 2000;
        long firstAtMs = 1000L;
        long lastAtMs = firstAtMs + (totalSamples - 1L);
        for (int i = 0; i < totalSamples; i++) {
            double lat = 32.70 + (i * 0.00001);
            double lng = -117.10 + (i * 0.00001);
            store.recordTelemetry(id, gpsSample(40, lat, lng, firstAtMs + i));
        }
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, lastAtMs + 1L, "");

        JSONObject summary =
                com.volttracker.obdpoc.StorageSummaryJson.build(store.getStorageSummaryRecord());
        JSONArray routes = summary.optJSONArray("recentRoutes");
        assertNotNull(routes);
        assertEquals(1, routes.length());

        JSONArray points = routes.getJSONObject(0).optJSONArray("points");
        assertNotNull(points);
        // Bounded — never more than the cap.
        assertTrue(
                "downsampled points must not exceed 500 (was " + points.length() + ")",
                points.length() <= 500);
        // Dense enough to render a meaningful polyline.
        assertTrue(
                "downsampled points must keep at least 100 samples (was " + points.length() + ")",
                points.length() >= 100);

        // Crucially: the first sample is the START of the drive, the last is the END — proving
        // the renderer no longer truncates from one end.
        long firstSampleMs = points.getJSONObject(0).optLong("atMs");
        long lastSampleMs = points.getJSONObject(points.length() - 1).optLong("atMs");
        assertEquals(
                "downsampled route must include the very first GPS fix", firstAtMs, firstSampleMs);
        assertEquals(
                "downsampled route must include the very last GPS fix", lastAtMs, lastSampleMs);
    }

    @Test
    public void shortSessionRouteReturnsEveryPoint() throws Exception {
        // No downsampling when the session has fewer points than the cap — every fix lands on
        // the map exactly once.
        long id = store.startSession("obd", "00:11", "Adapter");
        double[][] route = {
            {32.70, -117.10},
            {32.71, -117.10},
            {32.72, -117.10},
            {32.73, -117.10}
        };
        for (int i = 0; i < route.length; i++) {
            store.recordTelemetry(id, gpsSample(40, route[i][0], route[i][1], 1000L + i));
        }
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 5000L, "");

        JSONObject summary =
                com.volttracker.obdpoc.StorageSummaryJson.build(store.getStorageSummaryRecord());
        JSONArray points =
                summary.getJSONArray("recentRoutes").getJSONObject(0).getJSONArray("points");
        assertEquals(
                "every GPS fix in a short session must survive", route.length, points.length());
    }

    @Test
    public void telemetryGpsBackfillsPartialLocationRoute() throws Exception {
        long id = store.startSession("obd", "00:11", "Adapter");
        store.recordLocationSample(id, 900L, "gps", 0.0, 0.0, null, null, null, null, null, null);
        store.recordTelemetry(id, gpsSample(40, 32.70, -117.10, 1000L));
        store.recordTelemetry(id, gpsSample(42, 32.71, -117.11, 2000L));
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 3000L, "");

        JSONArray points =
                com.volttracker.obdpoc.StorageSummaryJson.build(store.getStorageSummaryRecord())
                        .getJSONArray("recentRoutes")
                        .getJSONObject(0)
                        .getJSONArray("points");
        assertEquals(2, points.length());
        assertEquals(32.70, points.getJSONObject(0).optDouble("lat"), 0.001);
        assertEquals(-117.11, points.getJSONObject(1).optDouble("lng"), 0.001);
    }

    @Test
    public void routePointsKeepUnknownLocationScalarsNull() throws Exception {
        long id = store.startSession("obd", "00:11", "Adapter");
        store.recordLocationSample(
                id, 1000L, "gps", 32.70, -117.10, null, null, null, null, null, null);
        store.recordLocationSample(
                id, 2000L, "gps", 32.71, -117.11, null, null, null, null, null, null);
        store.recordTelemetry(id, gpsSample(40, 32.70, -117.10, 1000L));
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 3000L, "");

        JSONObject point =
                com.volttracker.obdpoc.StorageSummaryJson.build(store.getStorageSummaryRecord())
                        .getJSONArray("recentRoutes")
                        .getJSONObject(0)
                        .getJSONArray("points")
                        .getJSONObject(0);
        assertTrue(point.isNull("accuracyM"));
        assertTrue(point.isNull("speedMps"));
        assertTrue(point.isNull("altM"));
    }
}
