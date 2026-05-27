package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/** Verifies the {@code setAppState} payload shape assembled by {@link AppStateJson}. */
public class AppStateJsonTest {

    @Test
    public void buildFillsDefaultsWhenSnapshotsAreEmpty() throws JSONException {
        JSONObject payload =
                new JSONObject(
                        AppStateJson.build(
                                "1.2.3",
                                false,
                                false,
                                false,
                                "",
                                "",
                                new JSONObject(),
                                new JSONObject(),
                                new JSONObject()));

        assertEquals("1.2.3", payload.getJSONObject("app").getString("version"));
        JSONObject permissions = payload.getJSONObject("permissions");
        assertFalse(permissions.getBoolean("bluetooth"));
        assertFalse(permissions.getBoolean("location"));
        assertEquals("idle", payload.getJSONObject("session").getString("state"));
        assertEquals("unknown", payload.getJSONObject("vehicle").getString("state"));
        // No location permission and no fix -> the dashboard should show "blocked".
        assertEquals("blocked", payload.getJSONObject("gps").getString("state"));
        assertFalse(payload.getJSONObject("adapter").getBoolean("remembered"));
    }

    @Test
    public void buildReportsLockedGpsWhenTelemetryHasCoordinates() throws JSONException {
        JSONObject telemetry = new JSONObject();
        telemetry.put("latitude", 42.0);
        telemetry.put("longitude", -71.0);
        telemetry.put("vehicleState", "driving");

        JSONObject payload =
                new JSONObject(
                        AppStateJson.build(
                                "9.9",
                                true,
                                true,
                                true,
                                "00:11:22:33:44:55",
                                "OBDLink",
                                telemetry,
                                new JSONObject(),
                                new JSONObject()));

        assertEquals("locked", payload.getJSONObject("gps").getString("state"));
        assertEquals("driving", payload.getJSONObject("vehicle").getString("state"));
        assertEquals("observed", payload.getJSONObject("vehicle").getString("confidence"));
    }

    @Test
    public void buildForwardsClassifierReasonsArray() throws JSONException {
        // A1: vehicle.reasons mirrors the classifier output so the dashboard can
        // explain *why* a state was picked without re-running the rules in JS.
        JSONObject telemetry = new JSONObject();
        telemetry.put("vehicleState", "driving_ev");
        telemetry.put("vehicleStateConfidence", "observed");
        telemetry.put("vehicleStateReasons", new JSONArray().put("moving").put("engine_quiet"));

        JSONObject payload =
                new JSONObject(
                        AppStateJson.build(
                                "1.0",
                                true,
                                true,
                                true,
                                "",
                                "",
                                telemetry,
                                new JSONObject(),
                                new JSONObject()));

        JSONArray reasons = payload.getJSONObject("vehicle").getJSONArray("reasons");
        assertEquals(2, reasons.length());
        assertEquals("moving", reasons.getString(0));
        assertEquals("engine_quiet", reasons.getString(1));
    }

    @Test
    public void buildEmitsEmptyReasonsArrayWhenAbsent() throws JSONException {
        JSONObject payload =
                new JSONObject(
                        AppStateJson.build(
                                "1.0",
                                false,
                                false,
                                false,
                                "",
                                "",
                                new JSONObject(),
                                new JSONObject(),
                                new JSONObject()));

        assertEquals(0, payload.getJSONObject("vehicle").getJSONArray("reasons").length());
    }

    @Test
    public void buildMarksAdapterRememberedAndConnectedFromStatus() throws JSONException {
        JSONObject status = new JSONObject();
        status.put("state", "connected");

        JSONObject payload =
                new JSONObject(
                        AppStateJson.build(
                                "1.0",
                                true,
                                true,
                                true,
                                "00:11:22:33:44:55",
                                "OBDLink",
                                new JSONObject(),
                                status,
                                new JSONObject()));

        JSONObject adapter = payload.getJSONObject("adapter");
        assertTrue(adapter.getBoolean("remembered"));
        assertTrue(adapter.getBoolean("connected"));
        assertEquals("...44:55", adapter.getString("address"));
    }

    @Test
    public void appStatePayloadExposesTypedConstructorFieldsBeforeSerialization()
            throws JSONException {
        JSONObject telemetry = new JSONObject().put("source", "demo");
        AppStatePayload payload =
                new AppStatePayload(
                        "2.0",
                        true,
                        false,
                        true,
                        "00:11:22:33:44:55",
                        "OBDLink",
                        telemetry,
                        new JSONObject().put("state", "demo"),
                        new JSONObject());

        assertEquals("2.0", payload.version);
        assertTrue(payload.bluetoothReady);
        assertFalse(payload.locationGranted);
        assertTrue(payload.notificationsGranted);
        assertEquals("00:11:22:33:44:55", payload.lastAddress);

        JSONObject json = payload.toJson();
        assertEquals("demo", json.getJSONObject("session").getString("state"));
        assertEquals("demo", json.getJSONObject("session").getString("mode"));
    }
}
