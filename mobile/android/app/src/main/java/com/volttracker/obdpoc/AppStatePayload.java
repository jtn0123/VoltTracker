package com.volttracker.obdpoc;

import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Typed builder for the dashboard's app-state snapshot payload. */
final class AppStatePayload {

    final String version;
    final boolean bluetoothReady;
    final boolean locationGranted;
    final boolean notificationsGranted;
    final String lastAddress;
    final String lastName;
    final JSONObject telemetry;
    final JSONObject status;
    final JSONObject storage;

    AppStatePayload(
            String version,
            boolean bluetoothReady,
            boolean locationGranted,
            boolean notificationsGranted,
            String lastAddress,
            String lastName,
            JSONObject telemetry,
            JSONObject status,
            JSONObject storage) {
        this.version = version == null ? "" : version;
        this.bluetoothReady = bluetoothReady;
        this.locationGranted = locationGranted;
        this.notificationsGranted = notificationsGranted;
        this.lastAddress = lastAddress;
        this.lastName = lastName;
        this.telemetry = telemetry == null ? new JSONObject() : telemetry;
        this.status = status == null ? new JSONObject() : status;
        this.storage = storage == null ? new JSONObject() : storage;
    }

    JSONObject toJson() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("app", appJson());
            payload.put("permissions", permissionsJson());
            payload.put("adapter", adapterJson());
            payload.put("session", sessionJson());
            payload.put("vehicle", vehicleJson());
            payload.put("gps", gpsJson());
            payload.put("lifecycle", lifecycleJson());
            payload.put("latestTelemetry", telemetry);
            payload.put("storage", storage);
        } catch (JSONException ignored) {
            // Static field names and local primitive values are safe.
        }
        return payload;
    }

    private JSONObject appJson() throws JSONException {
        return new JSONObject().put("version", version).put("schemaVersion", 4);
    }

    private JSONObject permissionsJson() throws JSONException {
        return new JSONObject()
                .put("bluetooth", bluetoothReady)
                .put("location", locationGranted)
                .put("notifications", notificationsGranted);
    }

    private JSONObject adapterJson() throws JSONException {
        return new JSONObject()
                .put(
                        "name",
                        MainActivityUtils.coalesce(
                                telemetry.optString("adapter", ""),
                                status.optString("adapter", ""),
                                lastName))
                .put("address", MainActivityUtils.redactAddress(lastAddress))
                .put("remembered", lastAddress != null && !lastAddress.trim().isEmpty())
                .put(
                        "connected",
                        MainActivityUtils.isConnectedState(status.optString("state", "")));
    }

    private JSONObject sessionJson() throws JSONException {
        return new JSONObject()
                .put("mode", telemetry.optString("source", ""))
                .put("state", status.optString("state", "idle"))
                .put("detail", status.optString("detail", ""))
                .put("sampleCount", telemetry.optInt("sampleCount", 0))
                .put("sessionMs", telemetry.optLong("sessionMs", 0L))
                .put("backgroundSampleCount", telemetry.optInt("backgroundSampleCount", 0))
                .put("sampleGapCount", telemetry.optInt("sampleGapCount", 0))
                .put("maxSampleGapMs", telemetry.optLong("maxSampleGapMs", 0L));
    }

    private JSONObject vehicleJson() throws JSONException {
        JSONObject vehicle =
                new JSONObject()
                        .put("state", telemetry.optString("vehicleState", "unknown"))
                        .put(
                                "confidence",
                                telemetry.optString(
                                        "vehicleStateConfidence",
                                        telemetry.has("vehicleState") ? "observed" : "unknown"));
        JSONArray reasons = telemetry.optJSONArray("vehicleStateReasons");
        vehicle.put("reasons", reasons == null ? new JSONArray() : reasons);
        JSONObject latestVehicle = storage.optJSONObject("latestVehicle");
        boolean hasVehicleRow = latestVehicle != null && latestVehicle.length() > 0;
        vehicle.put("vinStored", hasVehicleRow);
        if (hasVehicleRow) {
            Iterator<String> keys = latestVehicle.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                vehicle.put(key, latestVehicle.opt(key));
            }
        }
        String liveVin = telemetry.optString("vin", "");
        if (!liveVin.isEmpty()) {
            vehicle.put("vin", liveVin);
            vehicle.put("vinStored", true);
        }
        if (telemetry.has("odometerKm")) {
            vehicle.put("odometerKm", telemetry.optDouble("odometerKm"));
        }
        if (telemetry.has("odometerMiles")) {
            vehicle.put("odometerMiles", telemetry.optDouble("odometerMiles"));
        }
        return vehicle;
    }

    private JSONObject gpsJson() throws JSONException {
        JSONObject gps = new JSONObject();
        boolean hasLocation = telemetry.has("latitude") && telemetry.has("longitude");
        gps.put("state", hasLocation ? "locked" : (locationGranted ? "waiting" : "blocked"));
        if (telemetry.has("accuracyM")) {
            gps.put("accuracyM", telemetry.optDouble("accuracyM"));
        }
        if (telemetry.has("locationAgeMs")) {
            gps.put("ageMs", telemetry.optLong("locationAgeMs"));
        }
        return gps;
    }

    private JSONObject lifecycleJson() throws JSONException {
        return new JSONObject()
                .put("appForeground", telemetry.optBoolean("appForeground", true))
                .put(
                        "foregroundServiceActive",
                        telemetry.optBoolean("foregroundServiceActive", false))
                .put("backgroundSampleCount", telemetry.optInt("backgroundSampleCount", 0))
                .put("sampleGapCount", telemetry.optInt("sampleGapCount", 0))
                .put("lastSampleGapMs", telemetry.optLong("lastSampleGapMs", 0L))
                .put("maxSampleGapMs", telemetry.optLong("maxSampleGapMs", 0L));
    }
}
