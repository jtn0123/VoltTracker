package com.volttracker.obdpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Robolectric coverage for the per-session insights rollup cache (schema v9) behind {@link
 * ObdLocalStore#getInsightsJson()}. The cache must preserve the original one-session-=-one-trip
 * numbers exactly while making repeated reads cheap: closed sessions are cached, the active session
 * is folded in live, and a wipe clears everything.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ObdStoreTripsRollupDbTest {

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

    /** Seeds one closed OBD trip with two GPS fixes and returns the session id. */
    private long seedClosedTrip(double lat0, double lng0, double lat1, double lng1, long startMs)
            throws Exception {
        long id = store.startSession("obd", "00:11", "Adapter");
        store.recordTelemetry(id, gpsSample(40, lat0, lng0, startMs));
        store.recordTelemetry(id, gpsSample(50, lat1, lng1, startMs + 1000L));
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, startMs + 2000L, "");
        return id;
    }

    @Test
    public void repeatedReadsReturnIdenticalInsights() throws Exception {
        seedClosedTrip(32.70, -117.10, 32.71, -117.10, 1000L);
        seedClosedTrip(33.00, -118.00, 33.01, -118.00, 10_000L);

        JSONObject first = store.getInsightsJson();
        JSONObject second = store.getInsightsJson();

        // Idempotent: the second read comes entirely from the cache and matches the first.
        assertEquals(first.toString(), second.toString());
        assertEquals(2, first.getInt("tripCount"));
        assertTrue(first.getDouble("totalDistanceMeters") > 0);
    }

    @Test
    public void newlyClosedSessionIsBackfilledOnNextRead() throws Exception {
        seedClosedTrip(32.70, -117.10, 32.71, -117.10, 1000L);
        JSONObject afterOne = store.getInsightsJson();
        assertEquals(1, afterOne.getInt("tripCount"));
        double distanceOne = afterOne.getDouble("totalDistanceMeters");

        // A second drive closed after the cache was first built must be picked up.
        seedClosedTrip(33.00, -118.00, 33.05, -118.00, 10_000L);
        JSONObject afterTwo = store.getInsightsJson();
        assertEquals(2, afterTwo.getInt("tripCount"));
        assertTrue(afterTwo.getDouble("totalDistanceMeters") > distanceOne);
    }

    @Test
    public void staleRollupVersionIsRefreshedOnRead() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        long session = seedClosedTrip(32.70, -117.10, 32.72, -117.10, 1000L);
        try (VoltTrackerDb helper = new VoltTrackerDb(context)) {
            SQLiteDatabase db = helper.getWritableDatabase();
            ContentValues stale = new ContentValues();
            stale.put("session_id", session);
            stale.put("counted", 1);
            stale.put("distance_m", 1.0);
            stale.put("duration_ms", 1L);
            stale.put("max_speed_kph", 1);
            stale.put("has_route", 0);
            stale.put("started_at_ms", 1000L);
            stale.put("rollup_version", 0);
            db.insertWithOnConflict(
                    VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS,
                    null,
                    stale,
                    SQLiteDatabase.CONFLICT_REPLACE);
        }

        JSONObject insights = store.getInsightsJson();

        assertTrue(insights.getDouble("totalDistanceMeters") > 1.0);
        try (VoltTrackerDb helper = new VoltTrackerDb(context);
                Cursor cursor =
                        helper.getReadableDatabase()
                                .rawQuery(
                                        "SELECT rollup_version FROM "
                                                + VoltTrackerDb.TABLE_SESSION_TRIP_ROLLUPS
                                                + " WHERE session_id = ?",
                                        new String[] {String.valueOf(session)})) {
            assertTrue(cursor.moveToFirst());
            assertEquals(2, cursor.getInt(0));
        }
    }

    @Test
    public void activeSessionIsCountedLiveThenStaysStableOnceClosed() throws Exception {
        // An open (unfinished) session is folded in live, exactly as the old per-session loop did.
        long open = store.startSession("obd", "00:11", "Adapter");
        store.recordTelemetry(open, gpsSample(40, 32.70, -117.10, 1000L));
        store.recordTelemetry(open, gpsSample(55, 32.72, -117.10, 2000L));

        JSONObject whileOpen = store.getInsightsJson();
        assertEquals(1, whileOpen.getInt("tripCount"));
        double openDistance = whileOpen.getDouble("totalDistanceMeters");
        assertTrue(openDistance > 0);

        store.finishSession(open, ObdLocalStore.STATUS_COMPLETE, 3000L, "");
        JSONObject afterClose = store.getInsightsJson();
        assertEquals(1, afterClose.getInt("tripCount"));
        // Same fixes, now read from the cache — distance is unchanged.
        assertEquals(openDistance, afterClose.getDouble("totalDistanceMeters"), 0.5);
    }

    @Test
    public void clearAllDataResetsInsights() throws Exception {
        seedClosedTrip(32.70, -117.10, 32.71, -117.10, 1000L);
        assertEquals(1, store.getInsightsJson().getInt("tripCount"));

        store.clearAllData();
        JSONObject empty = store.getInsightsJson();
        assertEquals(0, empty.getInt("tripCount"));
        assertEquals(0d, empty.getDouble("totalDistanceMeters"), 0.0001);
    }
}
