package com.volttracker.obdpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class TelemetryPayloadTest {

    @Test
    public void fromJsonPinsCommonTelemetryFieldsBeforeSerialization() throws Exception {
        JSONObject source =
                new JSONObject()
                        .put("source", "obd")
                        .put("connected", true)
                        .put("updatedAt", 1234L)
                        .put("speedKph", 42);

        TelemetryPayload payload = TelemetryPayload.fromJson(source);

        assertFalse(payload.isEmpty());
        assertEquals("obd", payload.source());
        assertTrue(payload.connected());
        assertEquals(1234L, payload.updatedAt());
        assertSame(source, payload.toJson());
        assertEquals(42, payload.toJson().getInt("speedKph"));
    }

    @Test
    public void nullTelemetryBecomesEmptyPayload() {
        TelemetryPayload payload = TelemetryPayload.fromJson(null);

        assertTrue(payload.isEmpty());
        assertEquals("", payload.source());
        assertFalse(payload.connected());
        assertEquals(0L, payload.updatedAt());
    }
}
