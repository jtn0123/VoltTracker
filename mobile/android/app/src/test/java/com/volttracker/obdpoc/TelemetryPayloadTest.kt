package com.volttracker.obdpoc

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryPayloadTest {
    @Test
    fun fromJsonPinsCommonTelemetryFieldsBeforeSerialization() {
        val source =
            JSONObject()
                .put("source", "obd")
                .put("connected", true)
                .put("updatedAt", 1234L)
                .put("speedKph", 42)

        val payload = TelemetryPayload.fromJson(source)

        assertFalse(payload.isEmpty())
        assertEquals("obd", payload.source())
        assertTrue(payload.connected())
        assertEquals(1234L, payload.updatedAt())
        assertEquals(42, payload.toJson().getInt("speedKph"))
    }

    @Test
    fun fromJsonPreservesGpsFieldsThroughSerialization() {
        // GPS reaches the dashboard as preserved telemetry extras (TelemetryPayload has no typed
        // lat/lng fields). This is the channel the live GPS readout + the synthetic demo GPS both
        // ride on, so a regression that dropped unknown fields would silently kill route/GPS UI.
        val source =
            JSONObject()
                .put("source", "demo")
                .put("connected", true)
                .put("latitude", 32.7157)
                .put("longitude", -117.1611)
                .put("accuracyM", 6.0)
                .put("gpsSpeedMps", 14.2)
                .put("bearingDeg", 90.0)

        val serialized = TelemetryPayload.fromJson(source).toJson()

        assertEquals(32.7157, serialized.getDouble("latitude"), 1e-6)
        assertEquals(-117.1611, serialized.getDouble("longitude"), 1e-6)
        assertEquals(6.0, serialized.getDouble("accuracyM"), 1e-6)
        assertEquals(14.2, serialized.getDouble("gpsSpeedMps"), 1e-6)
        assertEquals(90.0, serialized.getDouble("bearingDeg"), 1e-6)
    }

    @Test
    fun fromJsonPreservesDiagnosticExtrasButDecouplesMutableSource() {
        val source =
            JSONObject()
                .put("source", "obd")
                .put("connected", true)
                .put("vehicleState", "driving")
                .put("vehicleStateReasons", JSONArray().put("speed"))
                .put("speedRejectedKph", 255)

        val payload = TelemetryPayload.fromJson(source)
        source.put("source", "changed")
        source.getJSONArray("vehicleStateReasons").put("mutated")
        source.put("speedRejectedKph", 0)

        val serialized = payload.toJson()
        assertEquals("obd", serialized.getString("source"))
        assertEquals("driving", serialized.getString("vehicleState"))
        assertEquals(1, serialized.getJSONArray("vehicleStateReasons").length())
        assertEquals("speed", serialized.getJSONArray("vehicleStateReasons").getString(0))
        assertEquals(255, serialized.getInt("speedRejectedKph"))
    }

    @Test
    fun builderCreatesTypedTelemetryPayload() {
        val payload =
            TelemetryPayload
                .builder()
                .source("demo")
                .connected(true)
                .updatedAt(2000L)
                .speedKph(64)
                .rpm(1200)
                .voltage(13.8)
                .vehicleState("driving")
                .extra("customPid", "22434F")
                .build()

        val serialized = payload.toJson()
        assertEquals("demo", payload.source())
        assertTrue(payload.connected())
        assertEquals(2000L, payload.updatedAt())
        assertEquals(64, serialized.getInt("speedKph"))
        assertEquals(1200, serialized.getInt("rpm"))
        assertEquals(13.8, serialized.getDouble("voltage"), 0.001)
        assertEquals("driving", serialized.getString("vehicleState"))
        assertEquals("22434F", serialized.getString("customPid"))
    }

    @Test
    fun nullTelemetryBecomesEmptyPayload() {
        val payload = TelemetryPayload.fromJson(null)

        assertTrue(payload.isEmpty())
        assertEquals("", payload.source())
        assertFalse(payload.connected())
        assertEquals(0L, payload.updatedAt())
    }
}
