package com.volttracker.obdpoc

import android.content.Context
import android.os.Bundle
import com.volttracker.obdpoc.data.ObdLocalStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * A [MainActivity] with recording overrides for exactly the data-bridge seams, shared by the
 * per-feature data-bridge unit tests ([VoltBridgeBackupsTest], [VoltBridgeSignalLogsTest],
 * [VoltBridgeTripExportsTest], [VoltBridgeTripEditsTest], [VoltBridgeMaintenanceTest]) and
 * modeled on [VoltBridgeDispatchTest.RecordingActivity]. Extracted from the former monolithic
 * VoltBridgeDataExportsTest when that sub-bridge was split into focused units (audit item A3).
 * `super.onCreate()` is skipped so no WebView or foreground service starts; only the recording
 * store + the confirm/status/storage hooks are wired up.
 */
class DataBridgeRecordingActivity : MainActivity() {
    lateinit var store: DataBridgeRecordingStore

    var loggingActive = false
    var activateLoggingBeforeConfirmed = false
    var storageSummaryCalls = 0

    var lastConfirmationTitle: String? = null

    var lastStatusState: String? = null
    var lastStatusDetail: String? = null
    var lastStatusBlocked = false
    var lastExportRouteKey: String? = null
    var lastExportFormat: String? = null
    var exportPayload = JSONObject().put("ok", true).toString()
    var appStatePayload = JSONObject().put("state", "idle").toString()
    var storageJson = JSONObject().put("sessions", 0).toString()

    override fun onCreate(savedInstanceState: Bundle?) {
        store = DataBridgeRecordingStore(this)
        localStore = store
        dataBackup = DataBackup(this)
        backupController = BackupController(this, dataBackup!!, null)
    }

    override fun runOnBackground(task: Runnable) {
        task.run()
    }

    override fun isLoggingActive(): Boolean = loggingActive

    override fun publishStatus(
        state: String?,
        detail: String?,
        blocked: Boolean,
    ) {
        lastStatusState = state
        lastStatusDetail = detail
        lastStatusBlocked = blocked
    }

    override fun publishStorageSummary() {
        storageSummaryCalls += 1
    }

    override fun exportTripFromBridge(
        routeKey: String?,
        format: String?,
    ): String {
        lastExportRouteKey = routeKey
        lastExportFormat = format
        return exportPayload
    }

    override fun getAppStateJson(): String = appStatePayload

    override fun getStorageSummaryJson(): String = storageJson

    override fun confirmBridgeAction(
        title: String,
        message: String,
        positiveLabel: String,
        onConfirmed: Runnable,
    ) {
        lastConfirmationTitle = title
        if (activateLoggingBeforeConfirmed) {
            loggingActive = true
        }
        onConfirmed.run()
    }
}

/** Records the store mutations the data bridges drive and serves canned export payloads. */
class DataBridgeRecordingStore(
    context: Context,
) : ObdLocalStore(context) {
    var open = true

    var clearAllDataCalls = 0
    var throwClearAllData = false

    var deleteReturn = 0
    var throwDeleteEnhancedCapability = false
    var lastDeletedId = Long.MIN_VALUE

    var singleExport = JSONObject()
    var lastSingleExportId = Long.MIN_VALUE

    var bulkExport = JSONObject()
    var lastBulkExportLimit = Int.MIN_VALUE

    var markTripReturn = false
    var throwMarkTrip = false
    var lastMarkedTripRouteKey: String? = null
    var restoreTripReturn = false
    var lastRestoredTripRouteKey: String? = null

    var setTripLabelReturn = false
    var throwSetTripLabel = false
    var lastLabelRouteKey: String? = null
    var lastLabelValue: String? = null

    var setTripFavoriteReturn = false
    var throwSetTripFavorite = false
    var lastFavoriteRouteKey: String? = null
    var lastFavoriteValue: Boolean? = null

    var addMaintenanceReturn = 7L
    var lastMaintenanceCreatedAtMs = Long.MIN_VALUE
    var lastMaintenanceOdometerKm: Double? = Double.NaN
    var lastMaintenanceType: String? = null
    var lastMaintenanceNote: String? = null
    var lastMaintenanceIntervalKm: Double? = Double.NaN
    var lastMaintenanceIntervalMonths: Int? = Int.MIN_VALUE
    var throwAddMaintenance = false

    var maintenanceLog = JSONArray()
    var throwMaintenanceLog = false
    var lastMaintenanceLogLimit = Int.MIN_VALUE

    var deleteMaintenanceReturn = 0
    var throwDeleteMaintenance = false
    var lastDeletedMaintenanceId = Long.MIN_VALUE

    override val isOpen: Boolean
        get() = open

    override fun clearAllData() {
        clearAllDataCalls += 1
        if (throwClearAllData) {
            throw IllegalStateException("clear failed")
        }
    }

    override fun deleteEnhancedCapability(id: Long): Int {
        lastDeletedId = id
        if (throwDeleteEnhancedCapability) {
            throw IllegalStateException("delete failed")
        }
        return deleteReturn
    }

    override fun getEnhancedCapabilityExportJson(id: Long): JSONObject {
        lastSingleExportId = id
        return singleExport
    }

    override fun getEnhancedCapabilitiesExportJson(limit: Int): JSONObject {
        lastBulkExportLimit = limit
        return bulkExport
    }

    override fun setTripHidden(
        routeKey: String?,
        hidden: Boolean,
    ): Boolean {
        if (hidden) {
            lastMarkedTripRouteKey = routeKey
            if (throwMarkTrip) {
                throw IllegalStateException("mark failed")
            }
            return markTripReturn
        }
        lastRestoredTripRouteKey = routeKey
        return restoreTripReturn
    }

    override fun setTripLabel(
        routeKey: String?,
        label: String?,
    ): Boolean {
        lastLabelRouteKey = routeKey
        lastLabelValue = label
        if (throwSetTripLabel) {
            throw IllegalStateException("label failed")
        }
        return setTripLabelReturn
    }

    override fun setTripFavorite(
        routeKey: String?,
        favorite: Boolean,
    ): Boolean {
        lastFavoriteRouteKey = routeKey
        lastFavoriteValue = favorite
        if (throwSetTripFavorite) {
            throw IllegalStateException("favorite failed")
        }
        return setTripFavoriteReturn
    }

    override fun addMaintenanceEntry(
        createdAtMs: Long,
        odometerKm: Double?,
        type: String?,
        note: String?,
        intervalKm: Double?,
        intervalMonths: Int?,
    ): Long {
        lastMaintenanceCreatedAtMs = createdAtMs
        lastMaintenanceOdometerKm = odometerKm
        lastMaintenanceType = type
        lastMaintenanceNote = note
        lastMaintenanceIntervalKm = intervalKm
        lastMaintenanceIntervalMonths = intervalMonths
        if (throwAddMaintenance) {
            throw IllegalStateException("maintenance failed")
        }
        return addMaintenanceReturn
    }

    override fun getMaintenanceLogJson(limit: Int): JSONArray {
        if (throwMaintenanceLog) {
            throw IllegalStateException("maintenance log failed")
        }
        lastMaintenanceLogLimit = limit
        return maintenanceLog
    }

    override fun deleteMaintenanceEntry(id: Long): Int {
        lastDeletedMaintenanceId = id
        if (throwDeleteMaintenance) {
            throw IllegalStateException("delete failed")
        }
        return deleteMaintenanceReturn
    }
}
