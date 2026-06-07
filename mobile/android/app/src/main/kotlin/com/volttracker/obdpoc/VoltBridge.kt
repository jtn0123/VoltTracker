package com.volttracker.obdpoc

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.core.net.toUri
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The [JavascriptInterface] surface the dashboard WebView calls into.
 */
class VoltBridge(
    private val activity: DashboardHost,
) {
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
    ) {
        val cleanAddress = safe(address, MAX_ADDRESS_LEN)
        val cleanName = safe(name, MAX_NAME_LEN)
        activity.runOnUiThread {
            activity.rememberDevice(cleanAddress, cleanName)
            if (!validBluetoothAddress(cleanAddress)) {
                activity.publishStatus("blocked", "Choose a valid Bluetooth adapter.", true)
                return@runOnUiThread
            }
            activity.startObdService(ObdService.ACTION_CONNECT, cleanAddress, cleanName)
        }
    }

    @JavascriptInterface
    fun scan(
        address: String?,
        name: String?,
    ) {
        val cleanAddress = safe(address, MAX_ADDRESS_LEN)
        val cleanName = safe(name, MAX_NAME_LEN)
        activity.runOnUiThread {
            activity.rememberDevice(cleanAddress, cleanName)
            if (!validBluetoothAddress(cleanAddress)) {
                activity.publishStatus("blocked", "Choose a valid Bluetooth adapter.", true)
                return@runOnUiThread
            }
            activity.startObdService(ObdService.ACTION_SCAN, cleanAddress, cleanName)
        }
    }

    @JavascriptInterface
    fun tpmsScan(
        address: String?,
        name: String?,
    ) {
        detailProbe(address, name, EnhancedPidProfiles.STAGE_TIRES)
    }

    @JavascriptInterface
    fun detailProbe(
        address: String?,
        name: String?,
        stage: String?,
    ) {
        val cleanAddress = safe(address, MAX_ADDRESS_LEN)
        val cleanName = safe(name, MAX_NAME_LEN)
        val cleanStage = EnhancedPidProfiles.normalizeStage(safe(stage, MAX_STAGE_LEN))
        activity.runOnUiThread {
            activity.rememberDevice(cleanAddress, cleanName)
            if (!validBluetoothAddress(cleanAddress)) {
                activity.publishStatus("blocked", "Choose a valid Bluetooth adapter.", true)
                return@runOnUiThread
            }
            activity.startObdService(ObdService.ACTION_TPMS_SCAN, cleanAddress, cleanName, cleanStage)
        }
    }

    @JavascriptInterface
    fun getLastDevice(): String = activity.requireDeviceCatalog().getLastDeviceJson()

    @JavascriptInterface
    fun getDeviceHistory(): String = activity.requireDeviceCatalog().getDeviceHistoryJson()

    @JavascriptInterface
    fun getStorageSummary(): String = activity.getStorageSummaryJson()

    @JavascriptInterface
    fun exportDebugBundle(): String =
        activity.requireDataBackup().exportDebugBundle(activity.getAppStateJson(), activity.getStorageSummaryJson())

    @JavascriptInterface
    fun shareBackup() {
        activity.runOnUiThread(activity.requireBackupController()::launchShare)
    }

    @JavascriptInterface
    fun shareEncryptedBackup(passphrase: String?) {
        val cleanPassphrase = safe(passphrase, MAX_PASSPHRASE_LEN)
        activity.runOnUiThread {
            activity.requireBackupController().launchEncryptedShare(cleanPassphrase)
        }
    }

    @JavascriptInterface
    fun restoreBackup() {
        activity.runOnUiThread(activity.requireBackupController()::launchRestorePicker)
    }

    @JavascriptInterface
    fun restoreEncryptedBackup(passphrase: String?) {
        val cleanPassphrase = safe(passphrase, MAX_PASSPHRASE_LEN)
        activity.runOnUiThread {
            activity.requireBackupController().launchEncryptedRestorePicker(cleanPassphrase)
        }
    }

    @JavascriptInterface
    fun getTrips(): String = activity.getTripsJson()

    @JavascriptInterface
    fun getInsights(): String = activity.getInsightsJson()

    /**
     * Full route geometry for a logged trip.
     */
    @JavascriptInterface
    fun getTripRoute(sessionId: String?): String = activity.getTripRouteJson(safe(sessionId, MAX_LABEL_LEN))

    @JavascriptInterface
    fun clearStoredData() {
        if (activity.isLoggingActive()) {
            activity.runOnUiThread {
                activity.publishStatus("blocked", "Stop logging before clearing stored data.", true)
            }
            return
        }
        activity.runOnBackground {
            if (activity.isLoggingActive()) {
                activity.runOnUiThread {
                    activity.publishStatus("blocked", "Stop logging before clearing stored data.", true)
                }
                return@runOnBackground
            }
            try {
                activity.localStore?.clearAllData()
            } catch (ex: RuntimeException) {
                Log.w(TAG, "clearStoredData failed", ex)
                activity.runOnUiThread {
                    activity.publishStatus("blocked", "Could not clear the local OBD database.", true)
                }
                return@runOnBackground
            }
            activity.runOnUiThread {
                activity.publishStorageSummary()
                activity.publishStatus("ready", "On-phone OBD database cleared.", false)
            }
        }
    }

    @JavascriptInterface
    fun rememberDevice(
        address: String?,
        name: String?,
    ) {
        val cleanAddress = safe(address, MAX_ADDRESS_LEN)
        val cleanName = safe(name, MAX_NAME_LEN)
        activity.runOnUiThread {
            activity.rememberDevice(cleanAddress, cleanName)
        }
    }

    @JavascriptInterface
    fun connectLast() {
        val device = activity.requireDeviceCatalog().getLastOrCandidateDevice()
        val address = safe(device.optString("address", ""), MAX_ADDRESS_LEN)
        val name = safe(device.optString("name", ""), MAX_NAME_LEN)
        activity.runOnUiThread {
            if (!validBluetoothAddress(address)) {
                activity.publishStatus("blocked", "No remembered adapter yet. Connect once to save it.", true)
                return@runOnUiThread
            }
            activity.rememberDevice(address, name)
            activity.startObdService(ObdService.ACTION_CONNECT, address, name)
        }
    }

    @JavascriptInterface
    fun clearVehicleDtcCodes() {
        val device = activity.requireDeviceCatalog().getLastOrCandidateDevice()
        val address = safe(device.optString("address", ""), MAX_ADDRESS_LEN)
        val name = safe(device.optString("name", ""), MAX_NAME_LEN)
        activity.runOnUiThread {
            if (!validBluetoothAddress(address)) {
                activity.publishStatus("blocked", "No remembered adapter yet. Connect once to save it.", true)
                return@runOnUiThread
            }
            activity.rememberDevice(address, name)
            activity.startObdService(ObdService.ACTION_CLEAR_DTC, address, name)
        }
    }

    @JavascriptInterface
    fun openExternalSearch(dtc: String?) {
        val code = safe(dtc, MAX_DTC_LEN)
        if (code.isEmpty()) return
        activity.runOnUiThread {
            try {
                val query = URLEncoder.encode(code + " Chevy Volt DTC", StandardCharsets.UTF_8.name())
                val uri = "https://www.google.com/search?q=$query".toUri()
                val intent = Intent(Intent.ACTION_VIEW, uri)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
            } catch (ex: Exception) {
                Log.w(TAG, "openExternalSearch failed", ex)
                activity.publishStatus("blocked", "Could not open the browser for DTC lookup.", true)
            }
        }
    }

    @JavascriptInterface
    fun scanLast() {
        val device = activity.requireDeviceCatalog().getLastOrCandidateDevice()
        val address = safe(device.optString("address", ""), MAX_ADDRESS_LEN)
        val name = safe(device.optString("name", ""), MAX_NAME_LEN)
        activity.runOnUiThread {
            if (address.isEmpty()) {
                activity.publishStatus("blocked", "No remembered adapter yet. Connect once to save it.", true)
                return@runOnUiThread
            }
            activity.rememberDevice(address, name)
            activity.startObdService(ObdService.ACTION_SCAN, address, name)
        }
    }

    @JavascriptInterface
    fun tpmsScanLast() {
        detailProbeLast(EnhancedPidProfiles.STAGE_TIRES)
    }

    @JavascriptInterface
    fun detailProbeLast(stage: String?) {
        val device = activity.requireDeviceCatalog().getLastOrCandidateDevice()
        val address = safe(device.optString("address", ""), MAX_ADDRESS_LEN)
        val name = safe(device.optString("name", ""), MAX_NAME_LEN)
        val cleanStage = EnhancedPidProfiles.normalizeStage(safe(stage, MAX_STAGE_LEN))
        activity.runOnUiThread {
            if (!validBluetoothAddress(address)) {
                activity.publishStatus("blocked", "No remembered adapter yet. Connect once to save it.", true)
                return@runOnUiThread
            }
            activity.rememberDevice(address, name)
            activity.startObdService(ObdService.ACTION_TPMS_SCAN, address, name, cleanStage)
        }
    }

    @JavascriptInterface
    fun exportDetailedSignalLog(id: String?): String {
        val rowId = parsePositiveId(id)
        val store = activity.localStore
        if (rowId <= 0L || store == null) {
            return "{\"ok\":false,\"error\":\"invalid_id\",\"message\":\"Choose a saved detailed signal log.\"}"
        }
        return store.getEnhancedCapabilityExportJson(rowId).toString()
    }

    @JavascriptInterface
    fun exportDetailedSignalLogs(): String {
        val store = activity.localStore
        if (store == null) {
            return "{\"ok\":false,\"error\":\"storage_unavailable\",\"message\":\"Local storage is not ready.\"}"
        }
        return store.getEnhancedCapabilitiesExportJson(250).toString()
    }

    @JavascriptInterface
    fun deleteDetailedSignalLog(id: String?) {
        val rowId = parsePositiveId(id)
        if (rowId <= 0L) {
            activity.runOnUiThread {
                activity.publishStatus("blocked", "Choose a saved detailed signal log.", true)
            }
            return
        }
        activity.runOnBackground {
            var deleted = 0
            try {
                deleted = activity.localStore?.deleteEnhancedCapability(rowId) ?: 0
            } catch (ex: RuntimeException) {
                Log.w(TAG, "deleteDetailedSignalLog failed", ex)
            }
            val deletedRows = deleted
            activity.runOnUiThread {
                activity.publishStorageSummary()
                activity.publishStatus(
                    if (deletedRows > 0) "ready" else "blocked",
                    if (deletedRows > 0) {
                        "Detailed signal log removed."
                    } else {
                        "Detailed signal log was already gone."
                    },
                    deletedRows <= 0,
                )
            }
        }
    }

    @JavascriptInterface
    fun demo() {
        activity.runOnUiThread {
            activity.startObdService(ObdService.ACTION_DEMO, null, "Demo stream")
        }
    }

    @JavascriptInterface
    fun disconnect() {
        activity.runOnUiThread(activity::stopObdService)
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
    fun forceStopPackage(packageName: String?): Boolean {
        val pkg = safe(packageName, MAX_NAME_LEN)
        if (pkg.isEmpty()) {
            return false
        }
        return activity.forceStopPackageFromBridge(pkg)
    }

    @JavascriptInterface
    fun cancelRetry() {
        activity.runOnUiThread(activity::cancelRetryFromBridge)
    }

    @JavascriptInterface
    fun tryReconnectNow() {
        val device = activity.requireDeviceCatalog().getLastOrCandidateDevice()
        val address = safe(device.optString("address", ""), MAX_ADDRESS_LEN)
        val name = safe(device.optString("name", ""), MAX_NAME_LEN)
        activity.runOnUiThread {
            if (address.isEmpty()) {
                activity.publishStatus("blocked", "No remembered adapter yet. Pick one and try Connect.", true)
                return@runOnUiThread
            }
            activity.rememberDevice(address, name)
            activity.startObdService(ObdService.ACTION_CONNECT, address, name)
        }
    }

    @JavascriptInterface
    fun openBluetoothSettings() {
        activity.runOnUiThread(activity::openBluetoothSettingsFromBridge)
    }

    @JavascriptInterface
    fun getRecentSessions(n: Int): String = activity.getRecentSessionsJson(n)

    @JavascriptInterface
    fun shareDiagnostics() {
        activity.runOnUiThread(activity::shareDiagnosticsFromBridge)
    }

    @JavascriptInterface
    fun startTestConnection() {
        activity.runOnUiThread(activity::startTestConnectionFromBridge)
    }

    @JavascriptInterface
    fun scheduleAdapterReadyNotify(mins: Int) {
        val clamped = maxOf(1, minOf(30, mins))
        activity.runOnUiThread {
            activity.scheduleAdapterReadyNotifyFromBridge(clamped)
        }
    }

    @JavascriptInterface
    fun cancelAdapterReadyNotify() {
        activity.runOnUiThread(activity::cancelAdapterReadyNotifyFromBridge)
    }

    companion object {
        private const val TAG = "VoltTracker"
        private const val MAX_ADDRESS_LEN = 64
        private const val MAX_NAME_LEN = 256
        private const val MAX_LABEL_LEN = 128
        private const val MAX_STAGE_LEN = 32
        private const val MAX_DETAIL_LEN = 4096
        private const val MAX_DTC_LEN = 16
        private const val MAX_PASSPHRASE_LEN = 256
        private const val LOG_CLIENT_ERROR_RATE_PER_SEC = 5
        private const val LOG_CLIENT_ERROR_BURST = 10

        private fun safe(
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

        private fun validBluetoothAddress(address: String?): Boolean =
            address != null && BluetoothAdapter.checkBluetoothAddress(address.trim())

        private fun parsePositiveId(value: String?): Long =
            try {
                val parsed = safe(value, MAX_LABEL_LEN).toLong()
                if (parsed > 0L) parsed else -1L
            } catch (ex: RuntimeException) {
                -1L
            }
    }
}
