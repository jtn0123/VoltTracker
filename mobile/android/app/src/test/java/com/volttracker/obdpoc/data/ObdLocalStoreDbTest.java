package com.volttracker.obdpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import com.volttracker.obdpoc.StorageSummaryJson;
import java.io.File;
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
 * Exercises the real SQLite data layer via Robolectric: writes, the on-read trip and insight
 * aggregation, and clearAllData. Previously this layer had no test coverage.
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
        assertEquals(
                1,
                StorageSummaryJson.build(store.getStorageSummaryRecord()).optInt("sessionCount"));
    }

    @Test
    public void recordTelemetryCountsUsefulSamples() throws Exception {
        long id = store.startSession("obd", "00:11", "Adapter");
        store.recordTelemetry(id, sample(40, 1500, 32.70, -117.10, 1000));
        store.recordTelemetry(id, sample(45, 1550, 32.71, -117.10, 2000));
        assertEquals(
                2L,
                StorageSummaryJson.build(store.getStorageSummaryRecord()).optLong("sampleCount"));
    }

    @Test
    public void emptyTelemetryIsRejected() {
        long id = store.startSession("obd", "00:11", "Adapter");
        assertEquals(-1L, store.recordTelemetry(id, new JSONObject()));
        assertEquals(
                0L,
                StorageSummaryJson.build(store.getStorageSummaryRecord()).optLong("sampleCount"));
    }

    @Test
    public void getTripRouteJsonReturnsTheRouteForAnyStoredSession() throws Exception {
        // The Trips screen loads a selected drive's route on demand (bypassing the recent-routes
        // window) via getTripRouteJson — so any session with GPS must yield its route geometry.
        long id = store.startSession("obd", "00:11", "Adapter");
        store.recordTelemetry(id, sample(50, 1500, 32.70, -117.10, 1000));
        store.recordTelemetry(id, sample(70, 1700, 32.74, -117.10, 2000));
        store.recordTelemetry(id, sample(60, 1600, 32.78, -117.10, 3000));
        store.finishSession(id, ObdLocalStore.STATUS_COMPLETE, 4000, "");

        JSONObject route = store.getTripRouteJson(id);
        assertEquals(id, route.optJSONObject("session").optLong("id"));
        assertTrue("route must carry >= 2 points", route.optInt("pointCount") >= 2);
        assertEquals(route.optInt("pointCount"), route.optJSONArray("points").length());
        assertTrue("route distance should be > 0", route.optDouble("distanceMeters") > 0);
    }

    @Test
    public void getTripRouteJsonReturnsEmptyForUnknownSession() {
        // Defensive: selecting a trip whose row is gone must not throw — it returns {} so the UI
        // falls back to its "no route" state.
        JSONObject route = store.getTripRouteJson(999_999L);
        assertEquals(0, route.length());
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

        JSONObject summary = StorageSummaryJson.build(store.getStorageSummaryRecord());
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

        JSONObject summary = StorageSummaryJson.build(store.getStorageSummaryRecord());
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

        JSONObject code =
                StorageSummaryJson.build(store.getStorageSummaryRecord())
                        .getJSONArray("latestDiagnosticCodes")
                        .getJSONObject(0);
        assertEquals("U0073", code.optString("dtc"));
        assertEquals(1L, code.optLong("seenCount"));
        assertEquals(1200L, code.optLong("lastSeenMs"));
    }

    @Test
    public void enhancedPidObservationsUpdateFieldCapabilities() throws Exception {
        long id = store.startSession("scan", "00:11", "Adapter");
        store.recordPidObservation(
                id,
                pidObservation(
                        1000L,
                        "221154",
                        "ATSH7E0",
                        "1154",
                        "engine oil temperature",
                        "56",
                        56.0,
                        "C",
                        "62115460"),
                1000L);

        JSONArray capabilities = store.getEnhancedCapabilitiesJson(10);
        assertEquals(1, capabilities.length());
        JSONObject capability = capabilities.getJSONObject(0);
        assertEquals("221154", capability.optString("command"));
        assertEquals("ATSH7E0", capability.optString("header"));
        assertTrue(capability.optBoolean("supported"));
        assertEquals(1L, capability.optLong("responseCount"));
        assertEquals("confirmed", capability.getJSONObject("sample").optString("validationStatus"));
        assertEquals("low-risk", capability.getJSONObject("sample").optString("scanStage"));
        assertEquals("low", capability.getJSONObject("sample").optString("risk"));

        JSONObject summary = StorageSummaryJson.build(store.getStorageSummaryRecord());
        JSONArray summaryCapabilities = summary.getJSONArray("enhancedCapabilities");
        assertEquals(1, summaryCapabilities.length());
        assertEquals("221154", summaryCapabilities.getJSONObject(0).optString("command"));
        assertTrue(summaryCapabilities.getJSONObject(0).optBoolean("supported"));
        assertTrue(summary.getJSONArray("detailedSignalCatalog").length() >= 10);
    }

    @Test
    public void enhancedCapabilityKeepsSupportAfterLaterNoData() throws Exception {
        long id = store.startSession("scan", "00:11", "Adapter");
        store.recordPidObservation(
                id,
                pidObservation(
                        1000L,
                        "221154",
                        "ATSH7E0",
                        "1154",
                        "engine oil temperature",
                        "56",
                        56.0,
                        "C",
                        "62115460"),
                1000L);
        store.recordPidObservation(
                id,
                pidObservation(
                        2000L,
                        "221154",
                        "ATSH7E0",
                        "1154",
                        "engine oil temperature",
                        "",
                        null,
                        "C",
                        "NO DATA"),
                2000L);

        JSONObject capability = store.getEnhancedCapabilitiesJson(10).getJSONObject(0);
        assertTrue(capability.optBoolean("supported"));
        assertEquals(1L, capability.optLong("responseCount"));
        assertEquals(2000L, capability.optLong("lastSeenMs"));
        assertFalse(capability.getJSONObject("sample").optBoolean("positiveResponse"));
    }

    @Test
    public void rejectedEnhancedCapabilityCanBeReadForDiscoverySkip() throws Exception {
        long id = store.startSession("tpms-scan", "00:11", "Adapter");
        store.recordPidObservation(
                id,
                pidObservation(
                        1000L,
                        "224051",
                        "ATSH760",
                        "4051",
                        "candidate tire receiver slot 1",
                        "",
                        null,
                        "",
                        "NO DATA"),
                1000L);

        assertTrue(store.hasRejectedEnhancedCapability("00:11", "ATSH760", "224051"));
        assertFalse(store.hasRejectedEnhancedCapability("00:11", "ATSH760", "224052"));
        assertTrue(
                store.hasRecentEnhancedCapability(
                        "00:11", "ATSH760", "224051", Long.MAX_VALUE / 2L));
        assertFalse(
                store.hasRecentEnhancedCapability(
                        "00:11", "ATSH760", "224052", Long.MAX_VALUE / 2L));
    }

    @Test
    public void enhancedCapabilityCanBeExportedAndDeletedIndividually() throws Exception {
        long id = store.startSession("scan", "00:11", "Adapter");
        store.recordPidObservation(
                id,
                pidObservation(
                        1000L,
                        "221154",
                        "ATSH7E0",
                        "1154",
                        "engine oil temperature",
                        "56",
                        56.0,
                        "C",
                        "62115460"),
                1000L);

        JSONObject capability = store.getEnhancedCapabilitiesJson(10).getJSONObject(0);
        long rowId = capability.optLong("id");
        assertTrue(rowId > 0L);

        JSONObject one = store.getEnhancedCapabilityExportJson(rowId);
        assertTrue(one.optBoolean("ok"));
        assertEquals("detailed-signal-log", one.optString("kind"));
        assertEquals("221154", one.getJSONObject("item").optString("command"));

        JSONObject all = store.getEnhancedCapabilitiesExportJson(10);
        assertTrue(all.optBoolean("ok"));
        assertEquals(1, all.getJSONArray("items").length());

        assertEquals(1, store.deleteEnhancedCapability(rowId));
        assertEquals(0, store.getEnhancedCapabilitiesJson(10).length());
        assertFalse(store.getEnhancedCapabilityExportJson(rowId).optBoolean("ok"));
    }

    // ---- GPS route building (the data the map renders) -----------------------------

    private void locationSample(
            long sessionId, long atMs, double lat, double lng, Double accuracyM) {
        store.recordLocationSample(
                sessionId, atMs, "gps", lat, lng, accuracyM, null, null, null, null, null);
    }

    private static JSONObject diagnosticCode(String dtc, String status, long seenAtMs)
            throws Exception {
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

    private static JSONObject pidObservation(
            long observedAtMs,
            String command,
            String header,
            String pid,
            String name,
            String valueText,
            Double valueNumeric,
            String unit,
            String rawResponse)
            throws Exception {
        JSONObject observation = new JSONObject();
        observation.put("observedAtMs", observedAtMs);
        observation.put("command", command);
        observation.put("header", header);
        observation.put("pid", pid);
        observation.put("name", name);
        observation.put("valueText", valueText);
        if (valueNumeric != null) {
            observation.put("valueNumeric", valueNumeric.doubleValue());
        }
        observation.put("unit", unit);
        observation.put("rawRequest", command);
        observation.put("rawResponse", rawResponse);
        return observation;
    }

    @Test
    public void recordedLocationSamplesBuildARecentRoute() throws Exception {
        long id = store.startSession("obd", "00:11", "Adapter");
        locationSample(id, 1000L, 32.70, -117.10, 5.0);
        locationSample(id, 2000L, 32.71, -117.10, 5.0);
        locationSample(id, 3000L, 32.72, -117.10, 5.0);

        JSONArray routes =
                StorageSummaryJson.build(store.getStorageSummaryRecord())
                        .getJSONArray("recentRoutes");
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

        assertEquals(
                0,
                StorageSummaryJson.build(store.getStorageSummaryRecord())
                        .getJSONArray("recentRoutes")
                        .length());
    }

    @Test
    public void routePointsAreReturnedInChronologicalOrder() throws Exception {
        long id = store.startSession("obd", "00:11", "Adapter");
        // inserted out of order; the route must still render oldest-first
        locationSample(id, 3000L, 32.72, -117.10, 5.0);
        locationSample(id, 1000L, 32.70, -117.10, 5.0);
        locationSample(id, 2000L, 32.71, -117.10, 5.0);

        JSONArray points =
                StorageSummaryJson.build(store.getStorageSummaryRecord())
                        .getJSONArray("recentRoutes")
                        .getJSONObject(0)
                        .getJSONArray("points");
        assertEquals(1000L, points.getJSONObject(0).optLong("atMs"));
        assertEquals(2000L, points.getJSONObject(1).optLong("atMs"));
        assertEquals(3000L, points.getJSONObject(2).optLong("atMs"));
    }

    @Test
    public void routePointsCarryAccuracyForRendering() throws Exception {
        long id = store.startSession("obd", "00:11", "Adapter");
        locationSample(id, 1000L, 32.70, -117.10, 7.5);
        locationSample(id, 2000L, 32.71, -117.10, 9.0);

        JSONArray points =
                StorageSummaryJson.build(store.getStorageSummaryRecord())
                        .getJSONArray("recentRoutes")
                        .getJSONObject(0)
                        .getJSONArray("points");
        assertEquals(7.5, points.getJSONObject(0).optDouble("accuracyM"), 0.01);
        assertEquals(9.0, points.getJSONObject(1).optDouble("accuracyM"), 0.01);
    }

    // ---- finalizeSession: session-end + adapter-summary atomicity ------------------

    @Test
    public void finalizeSessionUpdatesSessionAndAdapterSummaryInOneCall() {
        long id = store.startSession("obd", "AA:BB:CC", "Adapter X", 10_000L);

        store.finalizeSession(
                id,
                ObdLocalStore.STATUS_COMPLETE,
                20_000L,
                "0100,0120",
                "AA:BB:CC",
                "Adapter X",
                "obd",
                7,
                "polled HV pack");

        // Session row was ended.
        ObdSessionRecord session = store.getSession(id);
        assertNotNull(session);
        assertEquals(ObdLocalStore.STATUS_COMPLETE, session.status);
        assertEquals(20_000L, session.endedAtMs);
        assertEquals("0100,0120", session.supportedPids);

        // Adapter-history row was upserted.
        java.util.List<AdapterHistoryRecord> history = store.getAdapterHistory(10);
        assertEquals(1, history.size());
        AdapterHistoryRecord adapter = history.get(0);
        assertEquals("AA:BB:CC", adapter.address);
        assertEquals("Adapter X", adapter.name);
        assertEquals("obd", adapter.lastMode);
        assertEquals(ObdLocalStore.STATUS_COMPLETE, adapter.lastStatus);
        assertEquals(id, adapter.lastSessionId);
        assertEquals(7, adapter.sampleCount);
        assertEquals("0100,0120", adapter.supportedPids);
        assertEquals("polled HV pack", adapter.lastEventDetail);
    }

    @Test
    public void finalizeSessionMatchesSeparateFinishAndAdapterSummary() {
        // Run the legacy two-call path against one adapter key, and finalizeSession against
        // an independent adapter key with identical inputs. Both must produce the same end
        // state — finalizeSession is meant to be a pure atomic wrapper, not a new behavior.
        long legacyId = store.startSession("obd", "11:11", "Legacy", 1_000L);
        store.finishSession(legacyId, ObdLocalStore.STATUS_COMPLETE, 2_000L, "0100");
        store.recordAdapterSummary(
                "11:11",
                "Legacy",
                "obd",
                legacyId,
                ObdLocalStore.STATUS_COMPLETE,
                3,
                "0100",
                "trip end");

        long atomicId = store.startSession("obd", "22:22", "Atomic", 1_000L);
        store.finalizeSession(
                atomicId,
                ObdLocalStore.STATUS_COMPLETE,
                2_000L,
                "0100",
                "22:22",
                "Atomic",
                "obd",
                3,
                "trip end");

        ObdSessionRecord legacySession = store.getSession(legacyId);
        ObdSessionRecord atomicSession = store.getSession(atomicId);
        assertEquals(legacySession.status, atomicSession.status);
        assertEquals(legacySession.endedAtMs, atomicSession.endedAtMs);
        assertEquals(legacySession.supportedPids, atomicSession.supportedPids);

        java.util.List<AdapterHistoryRecord> history = store.getAdapterHistory(10);
        assertEquals(2, history.size());
        AdapterHistoryRecord legacy = adapterFor(history, "11:11");
        AdapterHistoryRecord atomic = adapterFor(history, "22:22");
        assertNotNull(legacy);
        assertNotNull(atomic);
        assertEquals(legacy.sampleCount, atomic.sampleCount);
        assertEquals(legacy.connectCount, atomic.connectCount);
        assertEquals(legacy.lastStatus, atomic.lastStatus);
        assertEquals(legacy.lastMode, atomic.lastMode);
        assertEquals(legacy.supportedPids, atomic.supportedPids);
        assertEquals(legacy.lastEventDetail, atomic.lastEventDetail);
    }

    @Test
    public void finalizeSessionRolledBackWhenAdapterWriteThrows() {
        // The adapter-summary INSERT runs inside the same transaction as the session
        // UPDATE. If we drop the adapter_history table mid-flight the INSERT throws and
        // the surrounding transaction must roll back — leaving the session row as it was
        // before finalizeSession ran (active, no ended_at_ms).
        long id = store.startSession("obd", "33:33", "Faulty", 1_000L);
        assertEquals(ObdLocalStore.STATUS_ACTIVE, store.getSession(id).status);

        // Drop the table the second write targets so insertWithOnConflict throws.
        // We reach for the helper via reflection because the test only needs to prove the
        // transaction wrapper rolls back — production code never does this.
        try {
            java.lang.reflect.Field helperField = ObdLocalStore.class.getDeclaredField("helper");
            helperField.setAccessible(true);
            VoltTrackerDb helper = (VoltTrackerDb) helperField.get(store);
            helper.getWritableDatabase()
                    .execSQL("DROP TABLE " + VoltTrackerDb.TABLE_ADAPTER_HISTORY);
        } catch (Exception e) {
            throw new AssertionError("setup: could not drop adapter_history", e);
        }

        boolean threw = false;
        try {
            store.finalizeSession(
                    id,
                    ObdLocalStore.STATUS_COMPLETE,
                    2_000L,
                    "0100",
                    "33:33",
                    "Faulty",
                    "obd",
                    5,
                    "should rollback");
        } catch (RuntimeException expected) {
            threw = true;
        }
        assertTrue("finalizeSession should propagate the failure", threw);

        // Recreate the dropped table so getSession's helper queries don't blow up on the
        // adapter side and so tearDown's clearAllData stays well-formed.
        try {
            java.lang.reflect.Field helperField = ObdLocalStore.class.getDeclaredField("helper");
            helperField.setAccessible(true);
            VoltTrackerDb helper = (VoltTrackerDb) helperField.get(store);
            helper.getWritableDatabase()
                    .execSQL(
                            "CREATE TABLE "
                                    + VoltTrackerDb.TABLE_ADAPTER_HISTORY
                                    + " ("
                                    + "adapter_key TEXT PRIMARY KEY,"
                                    + "address TEXT,"
                                    + "name TEXT,"
                                    + "first_seen_ms INTEGER NOT NULL,"
                                    + "last_seen_ms INTEGER NOT NULL,"
                                    + "connect_count INTEGER NOT NULL DEFAULT 0,"
                                    + "scan_count INTEGER NOT NULL DEFAULT 0,"
                                    + "demo_count INTEGER NOT NULL DEFAULT 0,"
                                    + "sample_count INTEGER NOT NULL DEFAULT 0,"
                                    + "last_session_id INTEGER,"
                                    + "last_mode TEXT,"
                                    + "last_status TEXT,"
                                    + "supported_pids TEXT,"
                                    + "last_event_detail TEXT"
                                    + ")");
        } catch (Exception e) {
            throw new AssertionError("teardown: could not recreate adapter_history", e);
        }

        // The transaction rolled back: the session UPDATE was undone, so status is still
        // active and ended_at_ms is still zero. If finishSession had been called outside a
        // transaction the session would now read STATUS_COMPLETE here — that's the exact
        // bug B3 is fixing.
        ObdSessionRecord after = store.getSession(id);
        assertNotNull(after);
        assertEquals(ObdLocalStore.STATUS_ACTIVE, after.status);
        assertEquals(0L, after.endedAtMs);
    }

    // ---- VIN → vehicles upsert (covers upsertVehicleFromVin + storageSummary.latestVehicle) ---

    @Test
    public void upsertVehicleFromVinWritesRedactedRowAndShowsInStorageSummary() {
        // Synthetic Volt-ish VIN. WMI "1G1" maps to Chevrolet; position-10 char 'J'
        // decodes to year 2018 in the current 30-year window.
        long id = store.upsertVehicleFromVin("1G1ZD5ST8JF202020");

        assertTrue("upsert should return a positive row id", id > 0);
        // vehicleCount on the storage summary picks up the new row.
        assertEquals(
                1L,
                StorageSummaryJson.build(store.getStorageSummaryRecord()).optLong("vehicleCount"));

        // latestVehicleJson surfaces the redacted form (last 4 chars only) + the
        // WMI-derived make / position-10 year — never the full VIN.
        JSONObject latest =
                StorageSummaryJson.build(store.getStorageSummaryRecord())
                        .optJSONObject("latestVehicle");
        assertNotNull(latest);
        assertEquals("Chevrolet", latest.optString("make"));
        assertEquals("Chevrolet", latest.optString("name"));
        assertEquals(2018, latest.optInt("year"));
        assertEquals(2018, latest.optInt("modelYear"));
        assertEquals("…2020", latest.optString("vin"));
        assertTrue(latest.optLong("vehicleId") > 0);
    }

    @Test
    public void upsertVehicleFromVinIsIdempotentOnTheVinHash() {
        long firstId = store.upsertVehicleFromVin("1G1ZD5ST8JF202020");
        long secondId = store.upsertVehicleFromVin("1G1ZD5ST8JF202020");

        // Same VIN → same hash → updates the existing row in place rather than inserting.
        assertEquals(firstId, secondId);
        assertEquals(
                1L,
                StorageSummaryJson.build(store.getStorageSummaryRecord()).optLong("vehicleCount"));
    }

    @Test
    public void upsertVehicleFromVinRejectsWrongLength() {
        assertEquals(0L, store.upsertVehicleFromVin(null));
        assertEquals(0L, store.upsertVehicleFromVin("TOOSHORT"));
        assertEquals(0L, store.upsertVehicleFromVin("ALSO_WAY_TOO_LONG_FOR_A_VIN"));
        assertEquals(
                0L,
                StorageSummaryJson.build(store.getStorageSummaryRecord()).optLong("vehicleCount"));
    }

    @Test
    public void upsertVehicleFromVinWithUnknownWmiOmitsTheMakeField() {
        // "ZZZ" is not in the WMI lookup; make/display_name stay NULL.
        long id = store.upsertVehicleFromVin("ZZZ12345678901234");
        assertTrue(id > 0);
        JSONObject latest =
                StorageSummaryJson.build(store.getStorageSummaryRecord())
                        .optJSONObject("latestVehicle");
        assertNotNull(latest);
        assertEquals("", latest.optString("make"));
        // VIN suffix still surfaces even without manufacturer mapping.
        assertEquals("…1234", latest.optString("vin"));
    }

    @Test
    public void storageSummaryLatestVehicleIsEmptyWhenNoVehiclesRecorded() {
        // No upsert called → vehicles table is empty → latestVehicleJson returns {}.
        JSONObject latest =
                StorageSummaryJson.build(store.getStorageSummaryRecord())
                        .optJSONObject("latestVehicle");
        assertNotNull(latest);
        assertEquals(0, latest.length());
    }

    private static AdapterHistoryRecord adapterFor(
            java.util.List<AdapterHistoryRecord> records, String address) {
        for (AdapterHistoryRecord record : records) {
            if (address.equals(record.address)) {
                return record;
            }
        }
        return null;
    }

    // -- G1 retention -------------------------------------------------------

    @Test
    public void pruneRawDataDeletesOldTelemetryAndKeepsRecent() throws Exception {
        long sessionId = store.startSession("obd", "AA:BB:CC", "Adapter");
        long now = System.currentTimeMillis();
        // Old (>90 days)
        long old = now - 91L * 86_400_000L;
        store.recordTelemetry(sessionId, sample(40, 1500, 10.0, 20.0, old), old);
        // Recent (<1 day)
        long recent = now - 60_000L;
        store.recordTelemetry(sessionId, sample(50, 1700, 10.1, 20.1, recent), recent);
        assertEquals(
                2,
                StorageSummaryJson.build(store.getStorageSummaryRecord())
                        .optInt("rawTelemetryCount"));

        int deleted = store.pruneRawDataOlderThan(60);

        assertTrue("expected to delete the old row", deleted >= 1);
        assertEquals(
                1,
                StorageSummaryJson.build(store.getStorageSummaryRecord())
                        .optInt("rawTelemetryCount"));
    }

    @Test
    public void pruneRawDataIsNoopForNonPositiveDays() throws Exception {
        long sessionId = store.startSession("obd", "AA:BB:CC", "Adapter");
        long old = System.currentTimeMillis() - 91L * 86_400_000L;
        store.recordTelemetry(sessionId, sample(40, 1500, 10.0, 20.0, old), old);

        assertEquals(0, store.pruneRawDataOlderThan(0));
        assertEquals(0, store.pruneRawDataOlderThan(-7));
        assertEquals(
                1,
                StorageSummaryJson.build(store.getStorageSummaryRecord())
                        .optInt("rawTelemetryCount"));
    }

    @Test
    public void pruneRawDataKeepsSessionRowEvenWhenAllTelemetryIsPruned() throws Exception {
        long sessionId = store.startSession("obd", "AA:BB:CC", "Adapter");
        long old = System.currentTimeMillis() - 365L * 86_400_000L;
        store.recordTelemetry(sessionId, sample(40, 1500, 10.0, 20.0, old), old);

        store.pruneRawDataOlderThan(30);

        // session row is summary data — must NOT be pruned by raw-data retention
        assertEquals(
                1,
                StorageSummaryJson.build(store.getStorageSummaryRecord()).optInt("sessionCount"));
        assertEquals(
                0,
                StorageSummaryJson.build(store.getStorageSummaryRecord())
                        .optInt("rawTelemetryCount"));
    }

    @Test
    public void mergeFromImportsAValidDonorBackup() {
        Context context = RuntimeEnvironment.getApplication();
        VoltTrackerDb donorHelper = new VoltTrackerDb(context, "merge-from-donor.db");
        try {
            SQLiteDatabase donor = donorHelper.getWritableDatabase();
            ContentValues session = new ContentValues();
            session.put("mode", "obd");
            session.put("started_at_ms", 123_456L);
            session.put("status", "complete");
            session.put("sample_count", 0);
            session.put("created_at_ms", 123_456L);
            donor.insertOrThrow(VoltTrackerDb.TABLE_SESSIONS, null, session);
        } finally {
            donorHelper.close();
        }
        File donorFile = context.getDatabasePath("merge-from-donor.db");

        DatabaseMerger.MergeResult result = store.mergeFrom(donorFile);

        assertTrue(result.ok);
        assertEquals(1, result.sessionsAdded);
        assertEquals(
                1,
                StorageSummaryJson.build(store.getStorageSummaryRecord()).optInt("sessionCount"));
        context.deleteDatabase("merge-from-donor.db");
    }

    @Test
    public void mergeFromRejectsAMissingFile() {
        Context context = RuntimeEnvironment.getApplication();
        File missing = new File(context.getCacheDir(), "does-not-exist.db");

        DatabaseMerger.MergeResult result = store.mergeFrom(missing);

        assertFalse(result.ok);
    }

    @Test
    public void mergeFromRejectsADifferentSchemaVersion() {
        Context context = RuntimeEnvironment.getApplication();
        File donorFile = new File(context.getCacheDir(), "wrong-version.db");
        SQLiteDatabase donor = SQLiteDatabase.openOrCreateDatabase(donorFile.getPath(), null);
        try {
            donor.setVersion(VoltTrackerDb.DATABASE_VERSION - 1);
        } finally {
            donor.close();
        }

        DatabaseMerger.MergeResult result = store.mergeFrom(donorFile);

        assertFalse(result.ok);
        assertTrue(result.summary().contains("different app version"));
    }
}
