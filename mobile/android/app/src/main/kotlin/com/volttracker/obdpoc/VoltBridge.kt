package com.volttracker.obdpoc

import android.bluetooth.BluetoothAdapter
import android.util.Log
import android.webkit.JavascriptInterface

/**
 * The [JavascriptInterface] surface the dashboard WebView calls into.
 */
class VoltBridge(
    private val activity: DashboardHost,
) {
    private val connections = VoltBridgeConnections(activity)
    private val dataExports = VoltBridgeDataExports(activity, activity)
    private val diagnostics = VoltBridgeDiagnostics(activity)
    private val clientErrorRateLimiter = ClientErrorRateLimiter()

    @JavascriptInterface
    fun dashboardReady() {
        activity.runOnUiThread(activity::onDashboardReady)
    }

    @JavascriptInterface
    fun listDevices(): String = activity.requireDeviceCatalog().getBondedDevicesJson()

    @JavascriptInterface
    fun requestPermissions() {
        activity.runOnUiThread(activity.requirePermissionGate()::ensureGranted)
    }

    @JavascriptInterface
    fun refreshDevices() {
        activity.runOnUiThread {
            activity.publishDeviceList()
            activity.publishStorageSummary()
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
    fun getEventNotificationState(): String = activity.eventNotifications().getEventNotificationStateJson()

    @JavascriptInterface
    fun setChargeCompleteNotify(enabled: Boolean) {
        // State-mutating entry point: marshal off the WebView JavaBridge thread before touching
        // Activity-owned settings, mirroring setAutoConnectEnabled. The event-notification toggles
        // route through the host's eventNotifications() seam (the delegate holds the bodies).
        activity.runOnUiThread { activity.eventNotifications().setChargeCompleteEnabled(enabled) }
    }

    @JavascriptInterface
    fun setNewDtcNotify(enabled: Boolean) {
        activity.runOnUiThread { activity.eventNotifications().setNewDtcEnabled(enabled) }
    }

    @JavascriptInterface
    fun setLowSocNotify(
        enabled: Boolean,
        thresholdPct: Double,
    ) {
        activity.runOnUiThread { activity.eventNotifications().setLowSocEnabled(enabled, thresholdPct) }
    }

    @JavascriptInterface
    fun setHighPackTempNotify(
        enabled: Boolean,
        thresholdC: Double,
    ) {
        activity.runOnUiThread { activity.eventNotifications().setHighPackTempEnabled(enabled, thresholdC) }
    }

    @JavascriptInterface
    fun setChargeTargetSoc(targetPct: Double) {
        activity.runOnUiThread { activity.eventNotifications().setChargeTargetSoc(targetPct) }
    }

    @JavascriptInterface
    fun setAutoScanOnConnect(enabled: Boolean) {
        activity.runOnUiThread { activity.eventNotifications().setAutoScanOnConnectEnabled(enabled) }
    }

    @JavascriptInterface
    fun setMaintenanceDueNotify(enabled: Boolean) {
        activity.runOnUiThread { activity.eventNotifications().setMaintenanceDueEnabled(enabled) }
    }

    @JavascriptInterface
    fun getStorageSummary(): String = activity.getStorageSummaryJson()

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
    fun getTrips(): String = activity.getTripsJson()

    @JavascriptInterface
    fun getInsights(): String = activity.getInsightsJson()

    /**
     * Full route geometry for a logged trip.
     */
    @JavascriptInterface
    fun getTripRoute(sessionId: String?): String = activity.getTripRouteJson(safe(sessionId, MAX_LABEL_LEN))

    /**
     * Route geometry for the in-progress session, so the dashboard can rehydrate the live track
     * after a mid-drive WebView teardown/recreate. Empty JSON when nothing is recording.
     */
    @JavascriptInterface
    fun getCurrentSessionRoute(): String = activity.getCurrentSessionRouteJson()

    /** Battery-health snapshot history (JSON array) for the dashboard's pack-health trend chart. */
    @JavascriptInterface
    fun getBatterySohHistory(): String = activity.getBatterySohHistoryJson()

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
            "dashboard client error [${safe(label, MAX_LABEL_LEN)}]: ${safe(detail, MAX_DETAIL_LEN)}$suffix",
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

    companion object {
        internal const val MAX_ADDRESS_LEN = 64
        internal const val MAX_NAME_LEN = 256

        // General-purpose cap for short identifier-ish bridge inputs (e.g. route keys, session ids,
        // short labels and numeric id strings). The TRIP-LABEL path does NOT use this — it references
        // the data layer's ObdTripLabels.MAX_LABEL_LEN so the user-visible label cap is defined once.
        internal const val MAX_LABEL_LEN = 128
        internal const val MAX_STAGE_LEN = 32
        internal const val MAX_DETAIL_LEN = 4096
        internal const val MAX_DTC_LEN = 16
        internal const val MAX_PASSPHRASE_LEN = 256

        internal fun safe(
            value: String?,
            maxLen: Int,
        ): String {
            val trimmed = value?.trim() ?: return ""
            if (trimmed.length <= maxLen) {
                return trimmed
            }
            var cut = maxLen
            if (cut > 0 && Character.isHighSurrogate(trimmed[cut - 1])) {
                cut -= 1
            }
            return trimmed.substring(0, cut)
        }

        internal fun validBluetoothAddress(address: String?): Boolean =
            address != null && BluetoothAdapter.checkBluetoothAddress(address.trim())

        internal fun parsePositiveId(value: String?): Long =
            try {
                val parsed = safe(value, MAX_LABEL_LEN).toLong()
                if (parsed > 0L) parsed else -1L
            } catch (ex: RuntimeException) {
                -1L
            }
    }
}
