package com.volttracker.obdpoc

import android.content.Intent
import com.volttracker.obdpoc.data.ObdLocalStore

/**
 * Lifecycle/threading hooks the [VoltBridge] posts work onto.
 *
 * [runOnUiThread] is satisfied by the inherited `android.app.Activity` method; [runOnBackground]
 * hops to the owning Activity's background executor.
 */
interface BridgeThreading {
    fun runOnUiThread(action: Runnable)

    fun runOnBackground(task: Runnable)

    fun confirmBridgeAction(
        title: String,
        message: String,
        positiveLabel: String,
        onConfirmed: Runnable,
    )
}

/**
 * Connection/scan commands plus the device catalog the bridge reads adapter history from.
 */
interface DeviceCommands {
    fun requireDeviceCatalog(): DeviceCatalog

    fun rememberDevice(
        address: String?,
        name: String?,
    )

    fun startObdService(
        action: String?,
        address: String?,
        name: String?,
    )

    fun startObdService(
        action: String?,
        address: String?,
        name: String?,
        detailStage: String?,
    )

    fun stopObdService()

    fun isLoggingActive(): Boolean
}

/**
 * Runtime-permission entry point the dashboard's "grant permissions" affordance calls into.
 */
interface PermissionCommands {
    fun requirePermissionGate(): PermissionGate
}

/**
 * Backup/restore commands. The bridge drives the export builder directly and forwards share/restore
 * launches to the backup controller.
 */
interface BackupCommands {
    fun requireDataBackup(): DataBackup

    fun requireBackupController(): BackupController
}

/**
 * Troubleshooter forwarders plus the diagnostics-oriented session reads they pair with.
 */
interface DiagnosticsCommands {
    fun forceStopPackageFromBridge(packageName: String?): Boolean

    fun cancelRetryFromBridge()

    fun openBluetoothSettingsFromBridge()

    fun getRecentSessionsJson(n: Int): String

    fun shareDiagnosticsFromBridge()

    fun startTestConnectionFromBridge()

    fun scheduleAdapterReadyNotifyFromBridge(mins: Int)

    fun cancelAdapterReadyNotifyFromBridge()
}

/**
 * Pushes device/storage/status state back out to the dashboard WebView.
 */
interface DashboardStatePublisher {
    fun onDashboardReady()

    fun publishDeviceList()

    fun publishStorageSummary()

    fun publishStatus(
        state: String?,
        detail: String?,
        blocked: Boolean,
    )
}

/**
 * Read-only JSON projections of stored session data the dashboard requests on demand. [localStore]
 * is the raw store the bridge touches for detailed-signal-log export/delete; it is null before the
 * owning Activity's `onCreate` has run (and after teardown).
 */
interface SessionDataReader {
    val localStore: ObdLocalStore?

    fun getStorageSummaryJson(): String

    fun getAppStateJson(): String

    fun getTripsJson(): String

    fun getInsightsJson(): String

    fun getTripRouteJson(routeKey: String?): String
}

/**
 * Typed seam between the [VoltBridge] JavaScript interface and the Activity that owns the dashboard.
 *
 * The bridge marshals dashboard calls onto the right thread and delegates every command/query to
 * the Activity. Depending on this composed role interface (rather than the concrete `MainActivity`)
 * caps the bridge's native surface to exactly the members declared here and lets it be exercised
 * against a small fake instead of a fully constructed Activity. `MainActivity` already implements
 * every member, so it satisfies this interface by adding `override` modifiers only.
 */
interface DashboardHost :
    BridgeThreading,
    DeviceCommands,
    PermissionCommands,
    BackupCommands,
    DiagnosticsCommands,
    DashboardStatePublisher,
    SessionDataReader {
    /** Hands an [Intent] to the platform; used for the external DTC search. */
    fun startActivity(intent: Intent?)
}
