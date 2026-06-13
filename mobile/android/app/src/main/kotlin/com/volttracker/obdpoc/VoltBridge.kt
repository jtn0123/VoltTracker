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
    private val clientErrorLock = Any()
    private var clientErrorTokens = LOG_CLIENT_ERROR_BURST.toDouble()
    private var clientErrorLastRefillMs = 0L
    private var clientErrorDroppedCount = 0L

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

    @JavascriptInterface
    fun deleteDetailedSignalLog(id: String?) {
        dataExports.deleteDetailedSignalLog(id)
    }

    @JavascriptInterface
    fun markTripNotTrip(routeKey: String?) {
        dataExports.markTripNotTrip(routeKey)
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
        val dropped = acquireClientErrorTokenOrCountDrop()
        if (dropped < 0) {
            return
        }
        val suffix = if (dropped > 0) " (suppressed $dropped over-rate calls)" else ""
        Log.e(
            TAG,
            "dashboard client error [${safe(label, MAX_LABEL_LEN)}]: ${safe(detail, MAX_DETAIL_LEN)}$suffix",
        )
    }

    private fun acquireClientErrorTokenOrCountDrop(): Long =
        synchronized(clientErrorLock) {
            val now = System.currentTimeMillis()
            if (clientErrorLastRefillMs == 0L) {
                clientErrorLastRefillMs = now
            }
            val elapsed = maxOf(0L, now - clientErrorLastRefillMs)
            if (elapsed > 0L) {
                val refill = elapsed / 1000.0 * LOG_CLIENT_ERROR_RATE_PER_SEC
                clientErrorTokens = minOf(LOG_CLIENT_ERROR_BURST.toDouble(), clientErrorTokens + refill)
                clientErrorLastRefillMs = now
            }
            if (clientErrorTokens < 1.0) {
                clientErrorDroppedCount++
                -1L
            } else {
                clientErrorTokens -= 1.0
                val dropped = clientErrorDroppedCount
                clientErrorDroppedCount = 0L
                dropped
            }
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

    @JavascriptInterface
    fun getRecentSessions(n: Int): String = diagnostics.getRecentSessions(n)

    @JavascriptInterface
    fun shareDiagnostics() {
        diagnostics.shareDiagnostics()
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
        private const val TAG = "VoltTracker"
        internal const val MAX_ADDRESS_LEN = 64
        internal const val MAX_NAME_LEN = 256
        internal const val MAX_LABEL_LEN = 128
        internal const val MAX_STAGE_LEN = 32
        internal const val MAX_DETAIL_LEN = 4096
        internal const val MAX_DTC_LEN = 16
        internal const val MAX_PASSPHRASE_LEN = 256
        private const val LOG_CLIENT_ERROR_RATE_PER_SEC = 5
        private const val LOG_CLIENT_ERROR_BURST = 10

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
