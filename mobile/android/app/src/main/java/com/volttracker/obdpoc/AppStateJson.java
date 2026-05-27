package com.volttracker.obdpoc;

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
        return new AppStatePayload(
                        version,
                        bluetoothReady,
                        locationGranted,
                        notificationsGranted,
                        lastAddress,
                        lastName,
                        telemetry,
                        status,
                        storage)
                .toJson()
                .toString();
    }
}
