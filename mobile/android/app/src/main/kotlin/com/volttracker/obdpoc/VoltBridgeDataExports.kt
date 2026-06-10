package com.volttracker.obdpoc

import android.util.Log
import org.json.JSONObject

internal class VoltBridgeDataExports(
    private val activity: DashboardHost,
    private val stateProvider: BridgeStateProvider,
) {
    fun exportDebugBundle(): String =
        stateProvider.requireDataBackup().exportDebugBundle(
            stateProvider.getAppStateJson(),
            stateProvider.getStorageSummaryJson(),
        )

    fun shareBackup() {
        activity.runOnUiThread(stateProvider.requireBackupController()::launchShare)
    }

    fun shareEncryptedBackup(passphrase: String?) {
        val cleanPassphrase = VoltBridge.safe(passphrase, VoltBridge.MAX_PASSPHRASE_LEN)
        activity.runOnUiThread {
            stateProvider.requireBackupController().launchEncryptedShare(cleanPassphrase)
        }
    }

    fun restoreBackup() {
        activity.runOnUiThread(stateProvider.requireBackupController()::launchRestorePicker)
    }

    fun restoreEncryptedBackup(passphrase: String?) {
        val cleanPassphrase = VoltBridge.safe(passphrase, VoltBridge.MAX_PASSPHRASE_LEN)
        activity.runOnUiThread {
            stateProvider.requireBackupController().launchEncryptedRestorePicker(cleanPassphrase)
        }
    }

    fun clearStoredData() {
        if (activity.isLoggingActive()) {
            activity.runOnUiThread {
                activity.publishStatus("blocked", "Stop logging before clearing stored data.", true)
            }
            return
        }
        activity.confirmBridgeAction(
            "Clear stored data?",
            "This permanently deletes local OBD sessions, samples, routes, diagnostics, and storage rollups from this phone.",
            "Clear data",
        ) {
            clearStoredDataConfirmed()
        }
    }

    private fun clearStoredDataConfirmed() {
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
                Log.w(MainActivity.TAG, "clearStoredData failed", ex)
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

    fun exportDetailedSignalLog(id: String?): String {
        val rowId = VoltBridge.parsePositiveId(id)
        val store = activity.localStore
        if (rowId <= 0L || store == null) {
            return errorPayload("invalid_id", "Choose a saved detailed signal log.")
        }
        return store.getEnhancedCapabilityExportJson(rowId).toString()
    }

    fun exportDetailedSignalLogs(): String {
        val store = activity.localStore
        if (store == null) {
            return errorPayload("storage_unavailable", "Local storage is not ready.")
        }
        return store.getEnhancedCapabilitiesExportJson(250).toString()
    }

    fun deleteDetailedSignalLog(id: String?) {
        val rowId = VoltBridge.parsePositiveId(id)
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
                Log.w(MainActivity.TAG, "deleteDetailedSignalLog failed", ex)
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

    private fun errorPayload(
        error: String,
        message: String,
    ): String =
        JSONObject()
            .put("ok", false)
            .put("error", error)
            .put("message", message)
            .toString()

    fun markTripNotTrip(routeKey: String?) {
        val cleanRouteKey = VoltBridge.safe(routeKey, VoltBridge.MAX_LABEL_LEN)
        if (cleanRouteKey.isEmpty()) {
            activity.runOnUiThread {
                activity.publishStatus("blocked", "Choose a stored map trip to mark as not a trip.", true)
            }
            return
        }
        activity.confirmBridgeAction(
            "Mark as not a trip?",
            "This hides the selected route from Maps and Trips. Raw samples stay on the phone for diagnostics and backups.",
            "Mark not trip",
        ) {
            markTripNotTripConfirmed(cleanRouteKey)
        }
    }

    private fun markTripNotTripConfirmed(routeKey: String) {
        activity.runOnBackground {
            val changed =
                try {
                    activity.localStore?.markTripNotTrip(routeKey) == true
                } catch (ex: RuntimeException) {
                    Log.w(MainActivity.TAG, "markTripNotTrip failed", ex)
                    false
                }
            activity.runOnUiThread {
                activity.publishStorageSummary()
                activity.publishStatus(
                    if (changed) "ready" else "blocked",
                    if (changed) {
                        "Trip marked as not a trip. Raw data was kept."
                    } else {
                        "That map row could not be marked as not a trip."
                    },
                    !changed,
                )
            }
        }
    }
}
