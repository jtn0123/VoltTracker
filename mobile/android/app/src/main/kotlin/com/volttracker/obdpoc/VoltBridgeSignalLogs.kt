package com.volttracker.obdpoc

import android.util.Log

/**
 * Detailed-signal-log (enhanced-capability probe) bridge implementation: single/bulk export reads
 * and per-row deletion. [VoltBridge] owns the `@JavascriptInterface` wrappers; this class is
 * plumbing only.
 */
internal class VoltBridgeSignalLogs(
    private val activity: DashboardHost,
) {
    fun exportDetailedSignalLog(id: String?): String {
        val rowId = parseBridgePositiveId(id)
        // isOpen guard: same teardown-race contract as DashboardStorageReader — a store
        // closed by onDestroy must degrade to an error payload, not throw into the bridge.
        val store = activity.localStore?.takeIf { it.isOpen }
        if (rowId <= 0L || store == null) {
            return BridgeJsonResult.error("invalid_id", "Choose a saved detailed signal log.").serialize()
        }
        return BridgeJsonResult.obj(store.getEnhancedCapabilityExportJson(rowId)).serialize()
    }

    fun exportDetailedSignalLogs(): String {
        val store = activity.localStore?.takeIf { it.isOpen }
        if (store == null) {
            return BridgeJsonResult.error("storage_unavailable", "Local storage is not ready.").serialize()
        }
        return BridgeJsonResult.obj(store.getEnhancedCapabilitiesExportJson(MAX_BULK_EXPORT_ROWS)).serialize()
    }

    fun deleteDetailedSignalLog(id: String?) {
        val rowId = parseBridgePositiveId(id)
        if (rowId <= 0L) {
            activity.runOnUiThread {
                activity.publishStatus("blocked", "Choose a saved detailed signal log.", true)
            }
            return
        }
        activity.runOnBackground {
            val deleted =
                try {
                    activity.localStore?.deleteEnhancedCapability(rowId) ?: 0
                } catch (ex: RuntimeException) {
                    Log.w(AppPrefs.LOG_TAG, "deleteDetailedSignalLog failed", ex)
                    0
                }
            activity.runOnUiThread {
                activity.publishStorageSummary()
                activity.publishStatus(
                    if (deleted > 0) "ready" else "blocked",
                    if (deleted > 0) {
                        "Detailed signal log removed."
                    } else {
                        "Detailed signal log was already gone."
                    },
                    deleted <= 0,
                )
            }
        }
    }

    private companion object {
        /** Cap on detailed-signal-log rows returned to the dashboard in one bulk export. */
        const val MAX_BULK_EXPORT_ROWS = 250
    }
}
