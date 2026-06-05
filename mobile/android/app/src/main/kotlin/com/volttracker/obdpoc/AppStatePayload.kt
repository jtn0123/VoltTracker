package com.volttracker.obdpoc

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Typed builder for the dashboard's app-state snapshot payload. */
class AppStatePayload(
    version: String?,
    @JvmField val bluetoothReady: Boolean,
    @JvmField val locationGranted: Boolean,
    @JvmField val notificationsGranted: Boolean,
    @JvmField val lastAddress: String?,
    @JvmField val lastName: String?,
    telemetry: JSONObject?,
    status: JSONObject?,
    storage: JSONObject?,
) {
    @JvmField val version: String = version ?: ""

    @JvmField val telemetry: JSONObject = telemetry ?: JSONObject()

    @JvmField val status: JSONObject = status ?: JSONObject()

    @JvmField val storage: JSONObject = storage ?: JSONObject()

    fun toJson(): JSONObject {
        val payload = JSONObject()
        try {
            payload.put("app", appJson())
            payload.put("permissions", permissionsJson())
            payload.put("adapter", adapterJson())
            payload.put("session", sessionJson())
            payload.put("vehicle", vehicleJson())
            payload.put("gps", gpsJson())
            payload.put("lifecycle", lifecycleJson())
            payload.put("latestTelemetry", telemetry)
            payload.put("storage", storage)
        } catch (ignored: JSONException) {
            // Static field names and local primitive values are safe.
        }
        return payload
    }

    @Throws(JSONException::class)
    private fun appJson(): JSONObject = JSONObject().put("version", version ?: "").put("schemaVersion", 4)

    @Throws(JSONException::class)
    private fun permissionsJson(): JSONObject =
        JSONObject()
            .put("bluetooth", bluetoothReady)
            .put("location", locationGranted)
            .put("notifications", notificationsGranted)

    @Throws(JSONException::class)
    private fun adapterJson(): JSONObject =
        JSONObject()
            .put(
                "name",
                MainActivityUtils.coalesce(
                    telemetry.optString("adapter", ""),
                    status.optString("adapter", ""),
                    lastName,
                ),
            ).put("address", MainActivityUtils.redactAddress(lastAddress))
            .put("remembered", !lastAddress.isNullOrBlank())
            .put("connected", MainActivityUtils.isConnectedState(status.optString("state", "")))

    @Throws(JSONException::class)
    private fun sessionJson(): JSONObject =
        JSONObject()
            .put("mode", telemetry.optString("source", ""))
            .put("state", status.optString("state", "idle"))
            .put("detail", status.optString("detail", ""))
            .put("sampleCount", telemetry.optInt("sampleCount", 0))
            .put("sessionMs", telemetry.optLong("sessionMs", 0L))
            .put("backgroundSampleCount", telemetry.optInt("backgroundSampleCount", 0))
            .put("sampleGapCount", telemetry.optInt("sampleGapCount", 0))
            .put("maxSampleGapMs", telemetry.optLong("maxSampleGapMs", 0L))

    @Throws(JSONException::class)
    private fun vehicleJson(): JSONObject {
        val vehicle =
            JSONObject()
                .put("state", telemetry.optString("vehicleState", "unknown"))
                .put(
                    "confidence",
                    telemetry.optString(
                        "vehicleStateConfidence",
                        if (telemetry.has("vehicleState")) "observed" else "unknown",
                    ),
                )
        val reasons = telemetry.optJSONArray("vehicleStateReasons")
        vehicle.put("reasons", reasons ?: JSONArray())
        val latestVehicle = storage.optJSONObject("latestVehicle")
        val hasVehicleRow = latestVehicle != null && latestVehicle.length() > 0
        vehicle.put("vinStored", hasVehicleRow)
        if (hasVehicleRow) {
            val keys = latestVehicle.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                vehicle.put(key, latestVehicle.opt(key))
            }
        }
        val liveVin = telemetry.optString("vin", "")
        if (liveVin.isNotEmpty()) {
            vehicle.put("vin", liveVin)
            vehicle.put("vinStored", true)
        }
        if (telemetry.has("odometerKm")) {
            vehicle.put("odometerKm", telemetry.optDouble("odometerKm"))
        }
        if (telemetry.has("odometerMiles")) {
            vehicle.put("odometerMiles", telemetry.optDouble("odometerMiles"))
        }
        return vehicle
    }

    @Throws(JSONException::class)
    private fun gpsJson(): JSONObject {
        val gps = JSONObject()
        val hasLocation = telemetry.has("latitude") && telemetry.has("longitude")
        gps.put(
            "state",
            if (hasLocation) {
                "locked"
            } else if (locationGranted) {
                "waiting"
            } else {
                "blocked"
            },
        )
        if (telemetry.has("accuracyM")) {
            gps.put("accuracyM", telemetry.optDouble("accuracyM"))
        }
        if (telemetry.has("locationAgeMs")) {
            gps.put("ageMs", telemetry.optLong("locationAgeMs"))
        }
        return gps
    }

    @Throws(JSONException::class)
    private fun lifecycleJson(): JSONObject =
        JSONObject()
            .put("appForeground", telemetry.optBoolean("appForeground", true))
            .put("foregroundServiceActive", telemetry.optBoolean("foregroundServiceActive", false))
            .put("backgroundSampleCount", telemetry.optInt("backgroundSampleCount", 0))
            .put("sampleGapCount", telemetry.optInt("sampleGapCount", 0))
            .put("lastSampleGapMs", telemetry.optLong("lastSampleGapMs", 0L))
            .put("maxSampleGapMs", telemetry.optLong("maxSampleGapMs", 0L))
}
