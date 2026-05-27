package com.volttracker.obdpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
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
 * Robolectric coverage for the storage-summary and insights projections in {@link ObdStoreReports}
 * (exposed via {@link ObdLocalStore#getStorageSummary()} and {@link
 * ObdLocalStore#getInsightsJson()}). Asserts only the keys the source actually writes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ObdStoreReportsDbTest {

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

    private static JSONObject sample(int speedKph, double soc, double lat, double lng, long atMs)
            throws Exception {
        JSONObject sample = new JSONObject();
        sample.put("source", "obd");
        sample.put("speedKph", speedKph);
        sample.put("rpm", 1500);
        sample.put("soc", soc);
        sample.put("latitude", lat);
        sample.put("longitude", lng);
        sample.put("updatedAt", atMs);
        return sample;
    }

    // ---- empty store ---------------------------------------------------------------

    @Test
    public void emptyStoreSummaryHasZeroCountsAndKnownKeys() {
        JSONObject summary = store.getStorageSummary();
        assertNotNull(summary);

        // The keys we know storageSummary() writes — see ObdStoreReports#storageSummary.
        assertEquals(0, summary.optInt("sessionCount"));
        assertEquals(0L, summary.optLong("sampleCount"));
        assertEquals(0L, summary.optLong("rawTelemetryCount"));
        assertEquals(0L, summary.optLong("emptyTelemetryCount"));
        assertEquals(0L, summary.optLong("eventCount"));
        assertEquals(0L, summary.optLong("adapterCount"));
        assertEquals(0L, summary.optLong("pidObservationCount"));
        assertEquals(0L, summary.optLong("diagnosticCodeCount"));
        assertEquals(0L, summary.optLong("locationSampleCount"));
        // The database header itself takes some bytes once opened.
        assertTrue("databaseBytes should be reported", summary.has("databaseBytes"));
    }

    @Test
    public void emptyStoreInsightsHasZeroCountsAndKnownKeys() {
        JSONObject insights = store.getInsightsJson();
        assertNotNull(insights);

        // The keys we know insightsJson() writes — see ObdStoreTrips#insightsJson.
        assertEquals(0, insights.optInt("tripCount"));
        assertEquals(0d, insights.optDouble("totalDistanceMeters", -1d), 0.001);
        assertEquals(0L, insights.optLong("totalDriveMs"));
        assertEquals(0d, insights.optDouble("longestTripMeters", -1d), 0.001);
        assertEquals(0d, insights.optDouble("avgTripDistanceMeters", -1d), 0.001);
        assertEquals(0, insights.optInt("maxSpeedKph"));
        assertEquals(0, insights.optInt("gpsTripCount"));
        assertEquals(0L, insights.optLong("firstTripAtMs"));
        assertEquals(0L, insights.optLong("lastTripAtMs"));
        assertEquals(0L, insights.optLong("sessionCount"));
        assertEquals(0L, insights.optLong("sampleCount"));
        assertEquals(0L, insights.optLong("locationSampleCount"));
    }

    // ---- after seeded data ---------------------------------------------------------

    @Test
    public void summaryReflectsSeededSessionAndTelemetry() throws Exception {
        long id = store.startSession("obd", "00:11", "Adapter");
        store.recordTelemetry(id, sample(40, 50.0, 32.70, -117.10, 1000L));
        store.recordTelemetry(id, sample(50, 51.0, 32.71, -117.10, 2000L));
        store.recordTelemetry(id, sample(60, 52.0, 32.72, -117.10, 3000L));

        JSONObject summary = store.getStorageSummary();
        assertEquals(1, summary.optInt("sessionCount"));
        assertEquals(3L, summary.optLong("sampleCount"));
        assertEquals(3L, summary.optLong("rawTelemetryCount"));
        assertEquals(0L, summary.optLong("emptyTelemetryCount"));
        // Latest-session breadcrumbs only populate after at least one session exists.
        assertEquals(id, summary.optLong("lastSessionId"));
        assertEquals("obd", summary.optString("lastMode"));
        assertEquals("Adapter", summary.optString("lastAdapter"));
    }

    @Test
    public void insightsReportsRightMaxSpeedAndDistance() throws Exception {
        long id = store.startSession("obd", "00:11", "Adapter");
        store.recordTelemetry(id, sample(40, 50.0, 32.70, -117.10, 1000L));
        store.recordTelemetry(id, sample(85, 51.0, 32.71, -117.10, 2000L));
        store.recordTelemetry(id, sample(62, 52.0, 32.72, -117.10, 3000L));
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 4000L, "");

        JSONObject insights = store.getInsightsJson();
        assertEquals(1, insights.optInt("tripCount"));
        assertEquals(85, insights.optInt("maxSpeedKph"));
        // ~2.2 km along latitude — distance is computed from telemetry-fallback GPS points.
        double total = insights.optDouble("totalDistanceMeters", 0d);
        assertTrue("total distance ~2 km should be reported, got " + total, total > 2000d);
        assertEquals(total, insights.optDouble("longestTripMeters"), 0.001);
        assertEquals(1, insights.optInt("gpsTripCount"));
    }

    @Test
    public void insightsAggregatesMaxAcrossMultipleSessions() throws Exception {
        long first = store.startSession("obd", "00:11", "Adapter A");
        store.recordTelemetry(first, sample(40, 50.0, 32.70, -117.10, 1000L));
        store.recordTelemetry(first, sample(50, 51.0, 32.71, -117.10, 2000L));
        store.finishSession(first, ObdLocalStore.STATUS_COMPLETE, 3000L, "");

        long second = store.startSession("obd", "00:22", "Adapter B");
        store.recordTelemetry(second, sample(95, 60.0, 33.00, -118.00, 10000L));
        store.recordTelemetry(second, sample(80, 61.0, 33.01, -118.00, 11000L));
        store.finishSession(second, ObdLocalStore.STATUS_COMPLETE, 12000L, "");

        JSONObject insights = store.getInsightsJson();
        assertEquals(2, insights.optInt("tripCount"));
        assertEquals("max should be taken across all trips", 95, insights.optInt("maxSpeedKph"));
    }

    @Test
    public void summaryPreservesNullChargeAndBatteryFields() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        VoltTrackerDb helper = new VoltTrackerDb(context);
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            ContentValues charge = new ContentValues();
            charge.put("started_at_ms", 1000L);
            charge.putNull("ended_at_ms");
            charge.putNull("charger_type");
            charge.putNull("start_soc");
            charge.putNull("end_soc");
            charge.putNull("power_kw");
            charge.putNull("energy_kwh");
            charge.putNull("confidence");
            charge.put("created_at_ms", 1000L);
            db.insertOrThrow(VoltTrackerDb.TABLE_CHARGE_SESSIONS, null, charge);

            ContentValues battery = new ContentValues();
            battery.put("captured_at_ms", 2000L);
            battery.putNull("soc");
            battery.putNull("capacity_ah");
            battery.putNull("soh_pct");
            battery.putNull("pack_voltage");
            battery.putNull("pack_current_a");
            battery.putNull("pack_power_kw");
            battery.putNull("battery_temp_c");
            battery.put("created_at_ms", 2000L);
            db.insertOrThrow(VoltTrackerDb.TABLE_BATTERY_SNAPSHOTS, null, battery);
        } finally {
            helper.close();
        }

        JSONObject summary = store.getStorageSummary();
        JSONObject latestCharge = summary.getJSONObject("chargeSummary").getJSONObject("latest");
        assertEquals(JSONObject.NULL, latestCharge.get("endedAtMs"));
        assertEquals(JSONObject.NULL, latestCharge.get("chargerType"));
        assertEquals(JSONObject.NULL, latestCharge.get("startSoc"));
        assertEquals(JSONObject.NULL, latestCharge.get("endSoc"));
        assertEquals(JSONObject.NULL, latestCharge.get("powerKw"));
        assertEquals(JSONObject.NULL, latestCharge.get("energyKwh"));
        assertEquals(JSONObject.NULL, latestCharge.get("confidence"));

        JSONObject latestBattery =
                summary.getJSONObject("batterySummary").getJSONObject("latestBatterySnapshot");
        assertEquals(JSONObject.NULL, latestBattery.get("soc"));
        assertEquals(JSONObject.NULL, latestBattery.get("capacityAh"));
        assertEquals(JSONObject.NULL, latestBattery.get("sohPct"));
        assertEquals(JSONObject.NULL, latestBattery.get("packVoltage"));
        assertEquals(JSONObject.NULL, latestBattery.get("packCurrentA"));
        assertEquals(JSONObject.NULL, latestBattery.get("packPowerKw"));
        assertEquals(JSONObject.NULL, latestBattery.get("batteryTempC"));
    }
}
