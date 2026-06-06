package com.volttracker.obdpoc

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the `setAppState` payload shape assembled by [AppStateJson]. */
class AppStateJsonTest {
    @Test
    fun buildFillsDefaultsWhenSnapshotsAreEmpty() {
        val payload =
            JSONObject(
                AppStateJson.build(
                    "1.2.3",
                    false,
                    false,
                    false,
                    "",
                    "",
                    JSONObject(),
                    JSONObject(),
                    JSONObject(),
                ),
            )

        assertEquals("1.2.3", payload.getJSONObject("app").getString("version"))
        val permissions = payload.getJSONObject("permissions")
        assertFalse(permissions.getBoolean("bluetooth"))
        assertFalse(permissions.getBoolean("location"))
        assertEquals("idle", payload.getJSONObject("session").getString("state"))
        assertEquals("unknown", payload.getJSONObject("vehicle").getString("state"))
        // No location permission and no fix -> the dashboard should show "blocked".
        assertEquals("blocked", payload.getJSONObject("gps").getString("state"))
        assertFalse(payload.getJSONObject("adapter").getBoolean("remembered"))
    }

    @Test
    fun buildReportsLockedGpsWhenTelemetryHasCoordinates() {
        val telemetry = JSONObject()
        telemetry.put("latitude", 42.0)
        telemetry.put("longitude", -71.0)
        telemetry.put("vehicleState", "driving")

        val payload =
            JSONObject(
                AppStateJson.build(
                    "9.9",
                    true,
                    true,
                    true,
                    "00:11:22:33:44:55",
                    "OBDLink",
                    telemetry,
                    JSONObject(),
                    JSONObject(),
                ),
            )

        assertEquals("locked", payload.getJSONObject("gps").getString("state"))
        assertEquals("driving", payload.getJSONObject("vehicle").getString("state"))
        assertEquals("observed", payload.getJSONObject("vehicle").getString("confidence"))
    }

    @Test
    fun buildForwardsClassifierReasonsArray() {
        // A1: vehicle.reasons mirrors the classifier output so the dashboard can
        // explain *why* a state was picked without re-running the rules in JS.
        val telemetry = JSONObject()
        telemetry.put("vehicleState", "driving_ev")
        telemetry.put("vehicleStateConfidence", "observed")
        telemetry.put("vehicleStateReasons", JSONArray().put("moving").put("engine_quiet"))

        val payload =
            JSONObject(
                AppStateJson.build(
                    "1.0",
                    true,
                    true,
                    true,
                    "",
                    "",
                    telemetry,
                    JSONObject(),
                    JSONObject(),
                ),
            )

        val reasons = payload.getJSONObject("vehicle").getJSONArray("reasons")
        assertEquals(2, reasons.length())
        assertEquals("moving", reasons.getString(0))
        assertEquals("engine_quiet", reasons.getString(1))
    }

    @Test
    fun buildCopiesLiveRedactedVinIntoVehicle() {
        val telemetry = JSONObject()
        telemetry.put("vin", "…2020")

        val payload =
            JSONObject(
                AppStateJson.build(
                    "1.0",
                    true,
                    true,
                    true,
                    "",
                    "",
                    telemetry,
                    JSONObject(),
                    JSONObject(),
                ),
            )

        val vehicle = payload.getJSONObject("vehicle")
        assertEquals("…2020", vehicle.getString("vin"))
        assertTrue(vehicle.getBoolean("vinStored"))
    }

    @Test
    fun buildEmitsEmptyReasonsArrayWhenAbsent() {
        val payload =
            JSONObject(
                AppStateJson.build(
                    "1.0",
                    false,
                    false,
                    false,
                    "",
                    "",
                    JSONObject(),
                    JSONObject(),
                    JSONObject(),
                ),
            )

        assertEquals(0, payload.getJSONObject("vehicle").getJSONArray("reasons").length())
    }

    @Test
    fun buildMarksAdapterRememberedAndConnectedFromStatus() {
        val status = JSONObject()
        status.put("state", "connected")

        val payload =
            JSONObject(
                AppStateJson.build(
                    "1.0",
                    true,
                    true,
                    true,
                    "00:11:22:33:44:55",
                    "OBDLink",
                    JSONObject(),
                    status,
                    JSONObject(),
                ),
            )

        val adapter = payload.getJSONObject("adapter")
        assertTrue(adapter.getBoolean("remembered"))
        assertTrue(adapter.getBoolean("connected"))
        assertEquals("...44:55", adapter.getString("address"))
    }

    @Test
    fun vehiclePayloadIncludesTelemetryOdometerWhenPresent() {
        val telemetry =
            JSONObject().put("odometerKm", 123456.7).put("odometerMiles", 76712.4)

        val payload =
            JSONObject(
                AppStateJson.build(
                    "1.0",
                    true,
                    true,
                    true,
                    "00:11:22:33:44:55",
                    "OBDLink",
                    telemetry,
                    JSONObject(),
                    JSONObject(),
                ),
            )

        val vehicle = payload.getJSONObject("vehicle")
        assertEquals(123456.7, vehicle.getDouble("odometerKm"), 0.01)
        assertEquals(76712.4, vehicle.getDouble("odometerMiles"), 0.01)
    }

    @Test
    fun appStatePayloadExposesTypedConstructorFieldsBeforeSerialization() {
        val telemetry = JSONObject().put("source", "demo")
        val payload =
            AppStatePayload(
                "2.0",
                true,
                false,
                true,
                "00:11:22:33:44:55",
                "OBDLink",
                telemetry,
                JSONObject().put("state", "demo"),
                JSONObject(),
            )

        assertEquals("2.0", payload.version)
        assertTrue(payload.bluetoothReady)
        assertFalse(payload.locationGranted)
        assertTrue(payload.notificationsGranted)
        assertEquals("00:11:22:33:44:55", payload.lastAddress)

        val json = payload.toJson()
        assertEquals("demo", json.getJSONObject("session").getString("state"))
        assertEquals("demo", json.getJSONObject("session").getString("mode"))
    }
}
