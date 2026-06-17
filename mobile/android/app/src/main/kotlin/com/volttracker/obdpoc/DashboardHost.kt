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
 * User-controlled, battery-safe automatic reconnect policy for the last adapter.
 */
interface AutoConnectCommands {
    fun getAutoConnectStateJson(): String

    fun setAutoConnectEnabledFromBridge(enabled: Boolean)
}

/**
 * User-controlled, native-owned event-notification + auto-scan settings (M1 + M3). The dashboard
 * reads the current toggle state and flips each toggle through the bridge, mirroring auto-connect.
 *
 * This cluster is exposed on [DashboardHost] as a single `eventNotifications()` accessor returning
 * this interface (backed by [EventNotificationHostDelegate]) rather than as N flat host overrides on
 * the Activity: the bridge calls `host.eventNotifications().setNewDtcEnabled(...)`. Folding the
 * group behind one accessor keeps `MainActivity` from accreting a one-line forward per toggle (the
 * growth-by-override sink the architecture audit flagged) — the delegate already holds every body.
 */
interface EventNotificationCommands {
    fun getEventNotificationStateJson(): String

    fun setChargeCompleteEnabled(enabled: Boolean)

    fun setNewDtcEnabled(enabled: Boolean)

    fun setLowSocEnabled(
        enabled: Boolean,
        thresholdPct: Double,
    )

    fun setHighPackTempEnabled(
        enabled: Boolean,
        thresholdC: Double,
    )

    fun setChargeTargetSoc(targetPct: Double)

    fun setAutoScanOnConnectEnabled(enabled: Boolean)

    fun setMaintenanceDueEnabled(enabled: Boolean)
}

/**
 * Read-only app state and backup collaborators needed by bridge export paths. Keeping this seam
 * separate lets export behavior be exercised without mocking the whole Activity.
 */
interface BridgeStateProvider {
    fun requireDataBackup(): DataBackup

    fun requireBackupController(): BackupController

    fun getAppStateJson(): String

    fun getStorageSummaryJson(): String
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

    fun shareDiagnosticsDigestFromBridge()

    fun startTestConnectionFromBridge()

    fun scheduleAdapterReadyNotifyFromBridge(mins: Int)

    fun cancelAdapterReadyNotifyFromBridge()

    /** Re-opens the guided first-run setup walkthrough (M7 "Setup guide" affordance). */
    fun openSetupGuideFromBridge()
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

    fun publishRestoreProgress(
        visible: Boolean,
        busy: Boolean,
        title: String?,
        detail: String?,
        tone: String?,
        phase: String? = null,
        bytesDone: Long = -1L,
        bytesTotal: Long = -1L,
        rowsDone: Long = -1L,
        rowsTotal: Long = -1L,
        percent: Int = -1,
        etaSeconds: Long = -1L,
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

    /**
     * Exports the trip identified by [routeKey] as `format` (`gpx`/`csv`): reads the route, writes a
     * cache file, records the export into the `exports` table, and launches the share sheet. Returns
     * the JSON result the bridge hands back to the dashboard (matches the other export methods'
     * `{ ok, … }` shape). The share itself is posted to the UI thread.
     */
    fun exportTripFromBridge(
        routeKey: String?,
        format: String?,
    ): String

    /** Route projection for the in-progress session, so the dashboard can rehydrate the live
     *  track after a mid-drive WebView teardown. Empty JSON when nothing is recording. */
    fun getCurrentSessionRouteJson(): String

    /** Battery-health snapshots (oldest-first JSON array) for the pack-health trend chart. */
    fun getBatterySohHistoryJson(): String
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
    AutoConnectCommands,
    DiagnosticsCommands,
    DashboardStatePublisher,
    SessionDataReader,
    BridgeStateProvider {
    /** Hands an [Intent] to the platform; used for the external DTC search. */
    fun startActivity(intent: Intent?)

    /**
     * The event-notification + auto-scan settings seam (M1/M3), exposed as one accessor instead of
     * mixing its toggles into the host's flat override surface. The bridge calls e.g.
     * `eventNotifications().setNewDtcEnabled(...)`. `MainActivity` returns its
     * [EventNotificationHostDelegate] here, so the seven toggle bodies live entirely on the delegate
     * and the Activity exposes the cluster with a single member.
     */
    fun eventNotifications(): EventNotificationCommands
}
