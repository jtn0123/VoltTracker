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
            locationGranted,
            notificationsGranted,
            lastAddress,
            lastName,
            telemetry,
            status,
            storage,
        ).toJson().toString()
}
