package com.volttracker.obdpoc

import org.json.JSONObject

/**
 * Assembles the `setAppState` JSON payload pushed to the dashboard from the latest telemetry,
 * status, and storage snapshots. Extracted from [MainActivity] as a pure function so the payload
 * shape stays unit-testable.
 */
object AppStateJson {
    @JvmStatic
    fun build(
        version: String?,
        bluetoothReady: Boolean,
        bluetoothPermissionGranted: Boolean,
        bluetoothEnabled: Boolean,
        locationGranted: Boolean,
        notificationsGranted: Boolean,
        lastAddress: String?,
        lastName: String?,
        telemetry: JSONObject?,
        status: JSONObject?,
        storage: JSONObject?,
    ): String =
        AppStatePayload(
            version,
            bluetoothReady,
            bluetoothPermissionGranted,
            bluetoothEnabled,
            locationGranted,
            notificationsGranted,
            lastAddress,
            lastName,
            telemetry,
            status,
            storage,
        ).toJson().toString()
}
