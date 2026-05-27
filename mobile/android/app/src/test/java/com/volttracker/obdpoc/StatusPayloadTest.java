package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class StatusPayloadTest {

    @Test
    public void toJsonKeepsDashboardStatusShapeAndLetsExtrasOverride() throws Exception {
        JSONObject extras = new JSONObject().put("failureClass", "caller_override");
        StatusPayload payload =
                new StatusPayload(
                        "connecting",
                        "Trying adapter",
                        false,
                        "OBDLink",
                        1234L,
                        "session.jsonl",
                        FailureClass.CONNECT_TIMEOUT,
                        12.4d,
                        "Torque,Car Scanner",
                        extras);

        assertEquals("connecting", payload.state);
        assertFalse(payload.blocked);

        JSONObject json = payload.toJson();
        assertEquals("connecting", json.getString("state"));
        assertEquals("Trying adapter", json.getString("detail"));
        assertFalse(json.getBoolean("blocked"));
        assertEquals("OBDLink", json.getString("adapter"));
        assertEquals(1234L, json.getLong("updatedAt"));
        assertEquals("session.jsonl", json.getString("logFile"));
        assertEquals("caller_override", json.getString("failureClass"));
        assertEquals(12.4d, json.getDouble("lastVoltage"), 0.001d);
        assertEquals("Torque,Car Scanner", json.getString("competingApps"));
    }

    @Test
    public void toJsonOmitsOptionalFieldsWhenTheyAreAbsent() {
        JSONObject json =
                new StatusPayload("idle", "Stopped", true, "", 99L, null, null, null, "", null)
                        .toJson();

        assertEquals("idle", json.optString("state"));
        assertTrue(json.optBoolean("blocked"));
        assertFalse(json.has("logFile"));
        assertFalse(json.has("failureClass"));
        assertFalse(json.has("lastVoltage"));
        assertFalse(json.has("competingApps"));
    }
}
