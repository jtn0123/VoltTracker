package com.volttracker.obdpoc

import android.content.Intent
import com.volttracker.obdpoc.data.ObdLocalStore

/** Where the data and connection tools report a one-line result ("Backup ready", "No adapter yet"). */
interface StatusSink {
    fun publishStatus(
        state: String?,
        detail: String?,
        blocked: Boolean,
    )
}

/**
 * What [TroubleshooterBridge] (test connection, wait for adapter, send diagnostics) needs from the
 * Activity that hosts it. Both dashboards implement it: the classic [MainActivity] and the native
 * [ComposeDashboardActivity].
 */
interface TroubleshooterHost :
    DeviceCommands,
    StatusSink

/**
 * What backup and restore ([BackupController], [BackupRestoreProgressPresenter],
 * [RestoreApplyPipeline]) need from the Activity that hosts them. Both dashboards implement it,
 * so either one can run a backup or a restore.
 */
interface BackupHost : StatusSink {
    /** The open database, or null when none is open. A replace restore swaps it for a new one. */
    var localStore: ObdLocalStore?

    fun isLoggingActive(): Boolean

    fun stopObdService()

    /** Satisfied by `Activity.runOnUiThread`. */
    fun runOnUiThread(action: Runnable)

    fun publishRestoreProgress(
        visible: Boolean,
        busy: Boolean,
        title: String?,
        detail: String?,
        tone: String?,
        phase: String?,
        bytesDone: Long,
        bytesTotal: Long,
        rowsDone: Long,
        rowsTotal: Long,
        percent: Int,
        etaSeconds: Long,
        operation: String?,
    )

    /** A result the classic dashboard renders (the backup receipt); other hosts may ignore it. */
    fun publishDashboardPayload(
        functionName: String,
        jsonPayload: String?,
    )

    fun publishDeviceList()

    fun publishStorageSummary()

    fun getStorageSummaryJson(): String

    fun launchRestoreFilePicker(intent: Intent)
}

/**
 * What [TripExportController] needs: the open database, a hop to the UI thread, and a way to show
 * the share sheet and report the result. [DashboardHost] extends it, and the native dashboard
 * implements it, so both can export.
 */
interface TripExportHost : StatusSink {
    val localStore: ObdLocalStore?

    fun runOnUiThread(action: Runnable)

    fun startActivity(intent: Intent?)
}
