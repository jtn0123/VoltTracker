package com.volttracker.obdpoc

import android.util.Log
import android.webkit.JavascriptInterface

/**
 * The [JavascriptInterface] surface the dashboard WebView calls into. The method count is
 * intentionally high because each dashboard bridge entry point must stay as a tiny public ABI
 * wrapper; implementation work is delegated to helper classes below.
 */
@Suppress("TooManyFunctions")
class VoltBridge(
    private val activity: DashboardHost,
) : VoltBridgeNotifications(activity) {
    private val connections = VoltBridgeConnections(activity)
    private val dataExports = VoltBridgeDataExports(activity, activity)
    private val diagnostics = VoltBridgeDiagnostics(activity)
    private val storage = VoltBridgeStorage(activity)
    private val clientErrorRateLimiter = ClientErrorRateLimiter()

    @JavascriptInterface
    fun dashboardReady() {
        StartupTrace.mark("bridge_dashboard_ready_called")
        activity.runOnUiThread(activity::onDashboardReady)
    }

    @JavascriptInterface
    fun startupMark(name: String?) {
        val clean = bridgeSafe(name, BRIDGE_MAX_LABEL_LEN).replace(STARTUP_MARK_SANITIZER, "_")
        StartupTrace.mark("js:$clean")
    }

    @JavascriptInterface
    fun listDevices(): String = activity.requireDeviceCatalog().getBondedDevicesJson()

    @JavascriptInterface
    fun requestPermissions() {
        activity.runOnUiThread(activity.requirePermissionGate()::ensureGranted)
    }

    @JavascriptInterface
    fun refreshDevices() {
        StartupTrace.mark("bridge_refresh_devices_called")
        activity.runOnUiThread {
            StartupTrace.mark("bridge_refresh_devices_ui_start")
            activity.publishDeviceList()
            activity.publishStorageSummary()
            StartupTrace.mark("bridge_refresh_devices_ui_end")
        }
    }

    @JavascriptInterface
    fun connect(
        address: String?,
        name: String?,
    ) = connections.connect(address, name)

    @JavascriptInterface
    fun scan(
        address: String?,
        name: String?,
    ) = connections.scan(address, name)

    @JavascriptInterface
    fun quickScan(
        address: String?,
        name: String?,
    ) = connections.scan(address, name, DiagnosticScanProfile.QUICK.wireName)

    @JavascriptInterface
    fun tpmsScan(
        address: String?,
        name: String?,
    ) {
        connections.detailProbe(address, name, EnhancedPidProfiles.STAGE_TIRES)
    }

    @JavascriptInterface
    fun detailProbe(
        address: String?,
        name: String?,
        stage: String?,
    ) = connections.detailProbe(address, name, stage)

    @JavascriptInterface
    fun getLastDevice(): String = activity.requireDeviceCatalog().getLastDeviceJson()

    @JavascriptInterface
    fun getDeviceHistory(): String = activity.requireDeviceCatalog().getDeviceHistoryJson()

    @JavascriptInterface
    fun getAutoConnectState(): String = activity.getAutoConnectStateJson()

    @JavascriptInterface
    fun setAutoConnectEnabled(enabled: Boolean) {
        // State-mutating entry point: like its siblings, marshal off the WebView JavaBridge
        // thread before touching Activity state (publishStatus/maybeAutoConnect/startObdService).
        activity.runOnUiThread {
            activity.setAutoConnectEnabledFromBridge(enabled)
        }
    }

    @JavascriptInterface
    fun getStorageSummary(): String = storage.getStorageSummary()

    @JavascriptInterface
    fun requestStorageSummary(): Boolean = storage.requestStorageSummary()

    @JavascriptInterface
    fun getStorageDetails(): String = storage.getStorageDetails()

    @JavascriptInterface
    fun requestStorageDetails(): Boolean = storage.requestStorageDetails()

    @JavascriptInterface
    fun exportDebugBundle(): String = dataExports.exportDebugBundle()

    @JavascriptInterface
    fun shareBackup() {
        dataExports.shareBackup()
    }

    @JavascriptInterface
    fun shareEncryptedBackup(passphrase: String?) = dataExports.shareEncryptedBackup(passphrase)

    @JavascriptInterface
    fun restoreBackup() {
        dataExports.restoreBackup()
    }

    @JavascriptInterface
    fun restoreEncryptedBackup(passphrase: String?) = dataExports.restoreEncryptedBackup(passphrase)

    @JavascriptInterface
    fun getTrips(): String = storage.getTrips()

    @JavascriptInterface
    fun getInsights(): String = storage.getInsights()

    @JavascriptInterface
    fun requestTrips(): Boolean = storage.requestTrips()

    @JavascriptInterface
    fun requestInsights(): Boolean = storage.requestInsights()

    /**
     * Full route geometry for a logged trip.
     */
    @JavascriptInterface
    fun getTripRoute(sessionId: String?): String = storage.getTripRoute(sessionId)

    @JavascriptInterface
    fun requestTripRoute(sessionId: String?): Boolean = storage.requestTripRoute(sessionId)

    /**
     * Route geometry for the in-progress session, so the dashboard can rehydrate the live track
     * after a mid-drive WebView teardown/recreate. Empty JSON when nothing is recording.
     */
    @JavascriptInterface
    fun getCurrentSessionRoute(): String = storage.getCurrentSessionRoute()

    @JavascriptInterface
    fun requestCurrentSessionRoute(): Boolean = storage.requestCurrentSessionRoute()

    /** Battery-health snapshot history (JSON array) for the dashboard's pack-health trend chart. */
    @JavascriptInterface
    fun getBatterySohHistory(): String = storage.getBatterySohHistory()

    @JavascriptInterface
    fun requestBatterySohHistory(): Boolean = storage.requestBatterySohHistory()

    @JavascriptInterface
    fun clearStoredData() {
        dataExports.clearStoredData()
    }

    @JavascriptInterface
    fun rememberDevice(
        address: String?,
        name: String?,
    ) = connections.rememberDevice(address, name)

    @JavascriptInterface
    fun connectLast() {
        connections.connectLast()
    }

    @JavascriptInterface
    fun clearVehicleDtcCodes() {
        diagnostics.clearVehicleDtcCodes()
    }

    @JavascriptInterface
    fun openExternalSearch(dtc: String?) {
        diagnostics.openExternalSearch(dtc)
    }

    @JavascriptInterface
    fun scanLast() {
        connections.scanLast()
    }

    @JavascriptInterface
    fun quickScanLast() {
        connections.scanLast(DiagnosticScanProfile.QUICK.wireName)
    }

    @JavascriptInterface
    fun tpmsScanLast() {
        connections.detailProbeLast(EnhancedPidProfiles.STAGE_TIRES)
    }

    @JavascriptInterface
    fun detailProbeLast(stage: String?) {
        connections.detailProbeLast(stage)
    }

    @JavascriptInterface
    fun exportDetailedSignalLog(id: String?): String = dataExports.exportDetailedSignalLog(id)

    @JavascriptInterface
    fun exportDetailedSignalLogs(): String = dataExports.exportDetailedSignalLogs()

    /** Exports a single logged trip as a GPX track and opens the share sheet. */
    @JavascriptInterface
    fun exportTripGpx(routeKeyOrSessionId: String?): String = dataExports.exportTripGpx(routeKeyOrSessionId)

    /** Exports a single logged trip as a CSV sample log and opens the share sheet. */
    @JavascriptInterface
    fun exportTripCsv(routeKeyOrSessionId: String?): String = dataExports.exportTripCsv(routeKeyOrSessionId)

    /** Exports every logged trip as one combined CSV (M6) and opens the share sheet. */
    @JavascriptInterface
    fun exportAllTripsCsv(): String = dataExports.exportAllTripsCsv()

    /**
     * Exports the charge history as one CSV (M1) and opens the share sheet. [pricePerKwh] (the user's
     * electricity rate, as a string) adds an estimated-cost column when it parses as a positive rate.
     */
    @JavascriptInterface
    fun exportChargeSessionsCsv(pricePerKwh: String?): String = dataExports.exportChargeSessionsCsv(pricePerKwh)

    /** Shareable drive-summary card (PNG): route outline + the trip-detail sheet's stat strings. */
    @JavascriptInterface
    fun shareTripCard(cardJson: String?): String = dataExports.shareTripCard(cardJson)

    @JavascriptInterface
    fun deleteDetailedSignalLog(id: String?) {
        dataExports.deleteDetailedSignalLog(id)
    }

    @JavascriptInterface
    fun markTripNotTrip(routeKey: String?) {
        dataExports.markTripNotTrip(routeKey)
    }

    /** Sets or clears (empty/null label) the user label for a stored trip (M4). */
    @JavascriptInterface
    fun setTripLabel(
        routeKey: String?,
        label: String?,
    ) {
        dataExports.setTripLabel(routeKey, label)
    }

    /** Sets or clears the user "favorite" flag for a stored trip (M4 favorites half). */
    @JavascriptInterface
    fun setTripFavorite(
        routeKey: String?,
        favorite: Boolean,
    ) {
        dataExports.setTripFavorite(routeKey, favorite)
    }

    /** Records a maintenance-log entry from the Insights add-entry form (M5). */
    @JavascriptInterface
    fun addMaintenanceEntry(json: String?) {
        dataExports.addMaintenanceEntry(json)
    }

    /** Newest-first maintenance log as a JSON array (M5). */
    @JavascriptInterface
    fun getMaintenanceLog(): String = dataExports.getMaintenanceLog()

    /** Deletes one maintenance-log entry by id (M5). */
    @JavascriptInterface
    fun deleteMaintenanceEntry(id: String?) {
        dataExports.deleteMaintenanceEntry(id)
    }

    @JavascriptInterface
    fun demo() {
        connections.demo()
    }

    @JavascriptInterface
    fun disconnect() {
        connections.disconnect()
    }

    @JavascriptInterface
    fun logClientError(
        label: String?,
        detail: String?,
    ) {
        val dropped = clientErrorRateLimiter.acquireOrCountDrop()
        if (dropped < 0) {
            return
        }
        val suffix = if (dropped > 0) " (suppressed $dropped over-rate calls)" else ""
        Log.e(
            AppPrefs.LOG_TAG,
            "dashboard client error [${bridgeSafe(label, BRIDGE_MAX_LABEL_LEN)}]: " +
                "${bridgeSafe(detail, BRIDGE_MAX_DETAIL_LEN)}$suffix",
        )
    }

    @JavascriptInterface
    fun forceStopPackage(packageName: String?): Boolean = diagnostics.forceStopPackage(packageName)

    @JavascriptInterface
    fun cancelRetry() {
        connections.cancelRetry()
    }

    @JavascriptInterface
    fun tryReconnectNow() {
        connections.tryReconnectNow()
    }

    @JavascriptInterface
    fun openBluetoothSettings() {
        connections.openBluetoothSettings()
    }

    /** Re-opens the guided first-run setup walkthrough on demand (M7 "Setup guide" affordance). */
    @JavascriptInterface
    fun openSetupGuide() {
        diagnostics.openSetupGuide()
    }

    @JavascriptInterface
    fun getRecentSessions(n: Int): String = diagnostics.getRecentSessions(n)

    @JavascriptInterface
    fun shareDiagnostics() {
        diagnostics.shareDiagnostics()
    }

    @JavascriptInterface
    fun shareDiagnosticsDigest() {
        diagnostics.shareDiagnosticsDigest()
    }

    @JavascriptInterface
    fun startTestConnection() {
        diagnostics.startTestConnection()
    }

    @JavascriptInterface
    fun scheduleAdapterReadyNotify(mins: Int) {
        diagnostics.scheduleAdapterReadyNotify(mins)
    }

    @JavascriptInterface
    fun cancelAdapterReadyNotify() {
        diagnostics.cancelAdapterReadyNotify()
    }

    private companion object {
        val STARTUP_MARK_SANITIZER = Regex("[^A-Za-z0-9_.:-]")
    }
}
