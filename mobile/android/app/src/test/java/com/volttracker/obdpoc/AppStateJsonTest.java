package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}
