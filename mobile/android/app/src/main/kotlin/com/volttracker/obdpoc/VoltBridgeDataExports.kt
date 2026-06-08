package com.volttracker.obdpoc

import android.util.Log

internal class VoltBridgeDataExports(
    private val activity: DashboardHost,
) {
    fun exportDebugBundle(): String =
        activity.requireDataBackup().exportDebugBundle(activity.getAppStateJson(), activity.getStorageSummaryJson())

    fun shareBackup() {
        activity.runOnUiThread(activity.requireBackupController()::launchShare)
    }

    fun shareEncryptedBackup(passphrase: String?) {
        val cleanPassphrase = VoltBridge.safe(passphrase, VoltBridge.MAX_PASSPHRASE_LEN)
        activity.runOnUiThread {
            activity.requireBackupController().launchEncryptedShare(cleanPassphrase)
        }
    }

    fun restoreBackup() {
        activity.runOnUiThread(activity.requireBackupController()::launchRestorePicker)
    }

    fun restoreEncryptedBackup(passphrase: String?) {
        val cleanPassphrase = VoltBridge.safe(passphrase, VoltBridge.MAX_PASSPHRASE_LEN)
        activity.runOnUiThread {
            activity.requireBackupController().launchEncryptedRestorePicker(cleanPassphrase)
        }
    }

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
            return "{\"ok\":false,\"error\":\"invalid_id\",\"message\":\"Choose a saved detailed signal log.\"}"
        }
        return store.getEnhancedCapabilityExportJson(rowId).toString()
    }

    fun exportDetailedSignalLogs(): String {
        val store = activity.localStore
        if (store == null) {
            return "{\"ok\":false,\"error\":\"storage_unavailable\",\"message\":\"Local storage is not ready.\"}"
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
}
