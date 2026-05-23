package com.volttracker.obdpoc;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Assembles the {@code setAppState} JSON payload pushed to the dashboard from the latest telemetry,
 * status, and storage snapshots. Extracted from {@link MainActivity} as a pure function so the
 * payload shape stays unit-testable.
 */
final class AppStateJson {

    private AppStateJson() {}

    static String build(
            String version,
            boolean bluetoothReady,
            boolean locationGranted,
            boolean notificationsGranted,
            String lastAddress,
            String lastName,
            JSONObject telemetry,
            JSONObject status,
            JSONObject storage) {
        JSONObject payload = new JSONObject();
        try {
            JSONObject app = new JSONObject();
            app.put("version", version);
            app.put("schemaVersion", 4);
            payload.put("app", app);

            JSONObject permissions = new JSONObject();
            permissions.put("bluetooth", bluetoothReady);
            permissions.put("location", locationGranted);
            permissions.put("notifications", notificationsGranted);
            payload.put("permissions", permissions);

            JSONObject adapter = new JSONObject();
            adapter.put(
                    "name",
                    MainActivity.coalesce(
                            telemetry.optString("adapter", ""),
                            status.optString("adapter", ""),
                            lastName));
            adapter.put("address", MainActivity.redactAddress(lastAddress));
            adapter.put("remembered", lastAddress != null && !lastAddress.trim().isEmpty());
            adapter.put("connected", MainActivity.isConnectedState(status.optString("state", "")));
            payload.put("adapter", adapter);

            JSONObject session = new JSONObject();
            session.put("mode", telemetry.optString("source", ""));
            session.put("state", status.optString("state", "idle"));
            session.put("detail", status.optString("detail", ""));
            session.put("sampleCount", telemetry.optInt("sampleCount", 0));
            session.put("sessionMs", telemetry.optLong("sessionMs", 0L));
            session.put("backgroundSampleCount", telemetry.optInt("backgroundSampleCount", 0));
            session.put("sampleGapCount", telemetry.optInt("sampleGapCount", 0));
            session.put("maxSampleGapMs", telemetry.optLong("maxSampleGapMs", 0L));
            payload.put("session", session);

            JSONObject vehicle = new JSONObject();
            vehicle.put("state", telemetry.optString("vehicleState", "unknown"));
            vehicle.put(
                    "confidence",
                    telemetry.optString(
                            "vehicleStateConfidence",
                            telemetry.has("vehicleState") ? "observed" : "unknown"));
            vehicle.put("vinStored", false);
            payload.put("vehicle", vehicle);

            JSONObject gps = new JSONObject();
            boolean hasLocation = telemetry.has("latitude") && telemetry.has("longitude");
            gps.put("state", hasLocation ? "locked" : (locationGranted ? "waiting" : "blocked"));
            if (telemetry.has("accuracyM")) {
                gps.put("accuracyM", telemetry.optDouble("accuracyM"));
            }
            if (telemetry.has("locationAgeMs")) {
                gps.put("ageMs", telemetry.optLong("locationAgeMs"));
            }
            payload.put("gps", gps);

            JSONObject lifecycle = new JSONObject();
            lifecycle.put("appForeground", telemetry.optBoolean("appForeground", true));
            lifecycle.put(
                    "foregroundServiceActive",
                    telemetry.optBoolean("foregroundServiceActive", false));
            lifecycle.put("backgroundSampleCount", telemetry.optInt("backgroundSampleCount", 0));
            lifecycle.put("sampleGapCount", telemetry.optInt("sampleGapCount", 0));
            lifecycle.put("lastSampleGapMs", telemetry.optLong("lastSampleGapMs", 0L));
            lifecycle.put("maxSampleGapMs", telemetry.optLong("maxSampleGapMs", 0L));
            payload.put("lifecycle", lifecycle);

            payload.put("latestTelemetry", telemetry);
            payload.put("storage", storage);
        } catch (JSONException ignored) {
            // Local state values are safe.
        }
        return payload.toString();
    }
}
