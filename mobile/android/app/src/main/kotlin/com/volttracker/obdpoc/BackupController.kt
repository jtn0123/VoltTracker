package com.volttracker.obdpoc

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.volttracker.obdpoc.data.DatabaseMerger
import com.volttracker.obdpoc.data.ObdLocalStore
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import kotlin.math.ceil

/** Drives the Activity-facing backup/restore user flows. */
class BackupController(
    private val activity: MainActivity,
    private val dataBackup: DataBackup,
    private val executor: ExecutorService?,
) {
    private var pendingRestorePassphrase: String? = null
    private val restoreLog = LogcatMirror(RollingAppLog(File(activity.filesDir, "app-log")))

    private enum class RestoreResult {
        OK,
        INVALID_FILE,
        LOGGING_ACTIVE,
        OTHER,
    }

    fun launchShare() {
        if (activity.isLoggingActive()) {
            activity.publishStatus("blocked", activity.getString(R.string.status_stop_logging_before_backup), true)
            return
        }
        showShareDisclosure { performBackupAndShare() }
    }

    fun launchEncryptedShare(passphrase: String?) {
        if (!DataBackup.hasMinimumPassphrase(passphrase)) {
            activity.publishStatus("blocked", minimumPassphraseMessage(), true)
            return
        }
        if (activity.isLoggingActive()) {
            activity.publishStatus("blocked", activity.getString(R.string.status_stop_logging_before_backup), true)
            return
        }
        showShareDisclosure { performBackupAndShare(true, passphrase) }
    }

    fun shareDisclosureMessage(): String =
        activity.getString(R.string.backup_disclosure_message, DataBackup.MIN_PASSPHRASE_LENGTH)

    private fun showShareDisclosure(onConfirmed: Runnable) {
        try {
            AlertDialog
                .Builder(activity)
                .setTitle(R.string.dialog_backup_disclosure_title)
                .setMessage(shareDisclosureMessage())
                .setPositiveButton(R.string.dialog_share_anyway) { _, _ -> onConfirmed.run() }
                .setNegativeButton(R.string.dialog_cancel) { _, _ ->
                    activity.publishStatus("ready", activity.getString(R.string.status_backup_cancelled), false)
                }.setOnCancelListener {
                    activity.publishStatus("ready", activity.getString(R.string.status_backup_cancelled), false)
                }.show()
        } catch (ex: RuntimeException) {
            activity.publishStatus("blocked", activity.getString(R.string.status_backup_disclosure_failed), true)
        }
    }

    private fun performBackupAndShare() {
        performBackupAndShare(false, null)
    }

    private fun performBackupAndShare(
        encrypted: Boolean,
        passphrase: String?,
    ) {
        if (activity.isLoggingActive()) {
            activity.publishStatus("blocked", activity.getString(R.string.status_stop_logging_before_backup), true)
            return
        }
        activity.publishStatus(
            "ready",
            activity.getString(
                if (encrypted) R.string.status_preparing_encrypted_backup else R.string.status_preparing_backup,
            ),
            false,
        )
        val title =
            activity.getString(
                if (encrypted) {
                    R.string.progress_preparing_encrypted_backup_title
                } else {
                    R.string.progress_preparing_backup_title
                },
            )
        val detail =
            activity.getString(
                if (encrypted) {
                    R.string.progress_preparing_encrypted_backup_detail
                } else {
                    R.string.progress_preparing_backup_detail
                },
            )
        showRestoreProgress(title, detail, phase = activity.getString(R.string.progress_preparing_backup_title))
        runBackground(activity.getString(R.string.status_backup_worker_failed)) {
            val progress = ProgressEmitter(title, detail)
            // Set when DataBackup's pre-export quick_check reports problems. The backup still
            // proceeds (a possibly damaged backup beats no backup), but the final success status
            // must tell the user instead of burying the warning in logcat.
            var integrityWarning = false
            val listener =
                DataBackup.ProgressListener { snapshot ->
                    if (snapshot.warning) {
                        integrityWarning = true
                    }
                    progress.onDataBackupProgress(snapshot)
                }
            val backup =
                if (encrypted) {
                    dataBackup.buildEncryptedBackupFile(activity.localStore, passphrase, listener)
                } else {
                    dataBackup.buildBackupFile(activity.localStore, listener)
                }
            activity.runOnUiThread {
                if (backup == null) {
                    showRestoreProgress(
                        activity.getString(R.string.progress_backup_failed_title),
                        activity.getString(R.string.status_backup_create_failed),
                        busy = false,
                        tone = "blocked",
                    )
                    activity.publishStatus("blocked", activity.getString(R.string.status_backup_create_failed), true)
                    return@runOnUiThread
                }
                val warningSuffix =
                    if (integrityWarning) {
                        " " + activity.getString(R.string.status_backup_integrity_warning)
                    } else {
                        ""
                    }
                try {
                    val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", backup)
                    val share = Intent(Intent.ACTION_SEND)
                    share.type = "application/octet-stream"
                    share.putExtra(Intent.EXTRA_STREAM, uri)
                    share.putExtra(Intent.EXTRA_SUBJECT, backup.name)
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    activity.startActivity(
                        Intent.createChooser(share, activity.getString(R.string.chooser_backup)),
                    )
                    showRestoreProgress(
                        activity.getString(R.string.progress_backup_ready_title),
                        activity.getString(
                            if (encrypted) {
                                R.string.progress_encrypted_backup_ready_detail
                            } else {
                                R.string.progress_backup_ready_detail
                            },
                        ) + warningSuffix,
                        busy = false,
                        tone = "ok",
                        phase = activity.getString(R.string.progress_phase_ready_to_share),
                        percent = 100,
                    )
                    activity.publishStatus(
                        "ready",
                        activity.getString(
                            if (encrypted) R.string.status_encrypted_backup_ready else R.string.status_backup_ready,
                        ) + warningSuffix,
                        false,
                    )
                } catch (ex: RuntimeException) {
                    showRestoreProgress(
                        activity.getString(R.string.progress_backup_failed_title),
                        activity.getString(R.string.status_share_sheet_failed),
                        busy = false,
                        tone = "blocked",
                    )
                    activity.publishStatus("blocked", activity.getString(R.string.status_share_sheet_failed), true)
                }
            }
        }
    }

    fun onRestorePickerResult(
        resultCode: Int,
        data: Intent?,
    ) {
        if (resultCode == Activity.RESULT_OK && data?.data != null) {
            val passphrase = pendingRestorePassphrase
            pendingRestorePassphrase = null
            restoreFromUri(data.data, passphrase)
        } else {
            pendingRestorePassphrase = null
            activity.publishStatus("ready", activity.getString(R.string.status_restore_cancelled_no_file), false)
        }
    }

    fun launchRestorePicker() {
        launchRestorePicker(null)
    }

    fun launchEncryptedRestorePicker(passphrase: String?) {
        // Restore accepts any non-empty passphrase: the minimum length only gates NEW backups,
        // and older backups may have been created with a shorter passphrase that must stay
        // restorable.
        if (!hasPassphrase(passphrase)) {
            activity.publishStatus("blocked", activity.getString(R.string.status_enter_passphrase_first), true)
            return
        }
        launchRestorePicker(passphrase)
    }

    private fun launchRestorePicker(passphrase: String?) {
        if (activity.isLoggingActive()) {
            activity.publishStatus("blocked", activity.getString(R.string.status_stop_logging_before_restore), true)
            return
        }
        try {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
            pick.addCategory(Intent.CATEGORY_OPENABLE)
            pick.type = "application/octet-stream"
            pick.putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/octet-stream", "application/vnd.sqlite3", "application/x-sqlite3"),
            )
            pendingRestorePassphrase = passphrase
            activity.publishStatus("ready", activity.getString(R.string.status_choose_backup_file), false)
            activity.launchRestoreFilePicker(pick)
        } catch (ex: RuntimeException) {
            pendingRestorePassphrase = null
            activity.publishStatus("blocked", activity.getString(R.string.status_file_picker_failed), true)
        }
    }

    private fun restoreFromUri(
        uri: Uri?,
        passphrase: String?,
    ) {
        val encrypted = hasPassphrase(passphrase)
        showRestoreProgress(
            readingBackupTitle(encrypted),
            activity.getString(R.string.progress_reading_backup_detail),
        )
        logRestore("stage_start", mapOf("encrypted" to encrypted))
        activity.publishStatus(
            "ready",
            activity.getString(
                if (encrypted) R.string.status_reading_encrypted_backup else R.string.status_reading_backup,
            ),
            false,
        )
        if (!runBackground(activity.getString(R.string.status_restore_worker_failed)) {
                val progress =
                    ProgressEmitter(
                        readingBackupTitle(encrypted),
                        activity.getString(
                            if (encrypted) {
                                R.string.progress_reading_encrypted_backup_worker_detail
                            } else {
                                R.string.progress_reading_backup_worker_detail
                            },
                        ),
                    )
                val outcome =
                    dataBackup.stageRestoreFileWithStatus(
                        uri,
                        passphrase,
                        DataBackup.ProgressListener { snapshot -> progress.onDataBackupProgress(snapshot) },
                    )
                activity.runOnUiThread {
                    val staged = outcome.file
                    if (!outcome.ok || staged == null) {
                        publishRestoreStageFailure(outcome)
                        return@runOnUiThread
                    }
                    logRestore(
                        "stage_ok",
                        mapOf(
                            "encrypted" to outcome.encrypted,
                            "migrated" to outcome.migrated,
                            "bytes" to outcome.bytesRead,
                        ),
                    )
                    hideRestoreProgress()
                    activity.publishStatus("ready", restoreVerifiedMessage(outcome), false)
                    promptRestoreMode(staged)
                }
            }
        ) {
            showRestoreProgress(
                restoreFailedTitle(),
                activity.getString(R.string.status_restore_worker_failed),
                busy = false,
                tone = "blocked",
            )
        }
    }

    private fun promptRestoreMode(staged: File) {
        try {
            AlertDialog
                .Builder(activity)
                .setTitle(R.string.dialog_restore_title)
                .setMessage(activity.getString(R.string.dialog_restore_message))
                .setPositiveButton(R.string.dialog_restore_merge) { _, _ -> performMerge(staged) }
                .setNegativeButton(R.string.dialog_restore_replace_all) { _, _ -> performReplace(staged) }
                .setNeutralButton(R.string.dialog_cancel) { _, _ -> cancelStagedRestore(staged) }
                .setOnCancelListener { cancelStagedRestore(staged) }
                .show()
        } catch (ex: RuntimeException) {
            cancelStagedRestore(staged)
            activity.publishStatus("blocked", activity.getString(R.string.status_restore_options_failed), true)
        }
    }

    private fun cancelStagedRestore(staged: File?) {
        DataBackup.deleteIfExists(staged)
        hideRestoreProgress()
        activity.publishStatus("ready", activity.getString(R.string.status_restore_cancelled), false)
    }

    private fun performReplace(staged: File) {
        showRestoreProgress(
            activity.getString(R.string.progress_restoring_title),
            activity.getString(R.string.progress_restoring_detail),
        )
        logRestore("replace_start", emptyMap<String, Any?>())
        activity.publishStatus("ready", activity.getString(R.string.status_restoring), false)
        if (!runBackground(activity.getString(R.string.status_restore_worker_failed)) {
                val progress =
                    ProgressEmitter(
                        activity.getString(R.string.progress_restoring_title),
                        activity.getString(R.string.progress_replace_worker_detail),
                    )
                val result = applyReplace(staged, progress)
                activity.runOnUiThread {
                    if (result == RestoreResult.OK) {
                        activity.publishDeviceList()
                        activity.publishStorageSummary()
                        showRestoreProgress(
                            activity.getString(R.string.progress_restore_complete_title),
                            activity.getString(R.string.progress_restore_complete_detail),
                            busy = false,
                            tone = "ok",
                        )
                        logRestore("replace_ok", emptyMap<String, Any?>())
                        activity.publishStatus(
                            "ready",
                            activity.getString(R.string.status_backup_restored),
                            false,
                        )
                    } else {
                        publishRestoreFailure(result)
                    }
                }
            }
        ) {
            showRestoreProgress(
                restoreFailedTitle(),
                activity.getString(R.string.status_restore_worker_failed),
                busy = false,
                tone = "blocked",
            )
        }
    }

    private fun performMerge(staged: File) {
        showRestoreProgress(
            activity.getString(R.string.progress_merging_title),
            activity.getString(R.string.progress_merging_detail),
        )
        logRestore("merge_start", emptyMap<String, Any?>())
        activity.publishStatus("ready", activity.getString(R.string.status_merging), false)
        if (!runBackground(activity.getString(R.string.status_restore_worker_failed)) {
                val progress =
                    ProgressEmitter(
                        activity.getString(R.string.progress_merging_title),
                        activity.getString(R.string.progress_merge_worker_detail),
                    )
                val outcome = applyMerge(staged, progress)
                activity.runOnUiThread {
                    if (outcome.result == RestoreResult.OK) {
                        activity.publishDeviceList()
                        activity.publishStorageSummary()
                        val mergeMessage = outcome.message ?: activity.getString(R.string.merge_default_message)
                        showRestoreProgress(
                            activity.getString(R.string.progress_merge_complete_title),
                            activity.getString(R.string.merge_reconnect_suffix, mergeMessage),
                            busy = false,
                            tone = "ok",
                        )
                        logRestore("merge_ok", emptyMap<String, Any?>())
                        activity.publishStatus(
                            "ready",
                            activity.getString(R.string.merge_reconnect_suffix, outcome.message),
                            false,
                        )
                    } else if (outcome.result == RestoreResult.LOGGING_ACTIVE) {
                        publishRestoreFailure(outcome.result)
                    } else {
                        val message = outcome.message ?: activity.getString(R.string.status_merge_failed_generic)
                        showRestoreProgress(restoreFailedTitle(), message, busy = false, tone = "blocked")
                        logRestore("merge_failed", mapOf("message" to message))
                        activity.publishStatus(
                            "blocked",
                            message,
                            true,
                        )
                    }
                }
            }
        ) {
            showRestoreProgress(
                restoreFailedTitle(),
                activity.getString(R.string.status_restore_worker_failed),
                busy = false,
                tone = "blocked",
            )
        }
    }

    private fun publishRestoreFailure(result: RestoreResult) {
        val message = restoreFailureMessage(result)
        showRestoreProgress(restoreFailedTitle(), message, busy = false, tone = "blocked")
        logRestore("apply_failed", mapOf("result" to result.name))
        activity.publishStatus("blocked", message, true)
    }

    private fun publishRestoreStageFailure(outcome: DataBackup.RestoreStageOutcome) {
        val message = restoreStageFailureMessage(outcome.status)
        if (outcome.status == DataBackup.RestoreStageStatus.NO_FILE) {
            hideRestoreProgress()
        } else {
            showRestoreProgress(restoreFailedTitle(), message, busy = false, tone = "blocked")
        }
        logRestore(
            "stage_failed",
            mapOf(
                "status" to outcome.status.name,
                "encrypted" to outcome.encrypted,
                "bytes" to outcome.bytesRead,
            ),
        )
        activity.publishStatus(
            if (outcome.status == DataBackup.RestoreStageStatus.NO_FILE) "ready" else "blocked",
            message,
            outcome.status != DataBackup.RestoreStageStatus.NO_FILE,
        )
    }

    private fun restoreVerifiedMessage(outcome: DataBackup.RestoreStageOutcome): String {
        val parts = ArrayList<String>()
        parts.add(
            activity.getString(
                if (outcome.encrypted) R.string.restore_verified_encrypted else R.string.restore_verified_plain,
            ),
        )
        if (outcome.migrated) {
            parts.add(activity.getString(R.string.restore_verified_migrated))
        }
        parts.add(activity.getString(R.string.restore_verified_choose))
        return parts.joinToString(" ")
    }

    private fun minimumPassphraseMessage(): String =
        activity.getString(R.string.status_passphrase_minimum, DataBackup.MIN_PASSPHRASE_LENGTH)

    private fun restoreFailedTitle(): String = activity.getString(R.string.progress_restore_failed_title)

    private fun readingBackupTitle(encrypted: Boolean): String =
        activity.getString(
            if (encrypted) R.string.progress_reading_encrypted_backup_title else R.string.progress_reading_backup_title,
        )

    private fun restoreFailureMessage(result: RestoreResult): String =
        activity.getString(
            when (result) {
                RestoreResult.LOGGING_ACTIVE -> R.string.restore_failed_logging_active
                RestoreResult.INVALID_FILE -> R.string.restore_failed_invalid_file
                else -> R.string.restore_failed_other
            },
        )

    private fun restoreStageFailureMessage(status: DataBackup.RestoreStageStatus): String =
        when (status) {
            DataBackup.RestoreStageStatus.NO_FILE ->
                activity.getString(R.string.status_restore_cancelled_no_file)
            DataBackup.RestoreStageStatus.OPEN_FAILED ->
                activity.getString(R.string.restore_stage_open_failed)
            DataBackup.RestoreStageStatus.TOO_LARGE ->
                activity.getString(R.string.restore_stage_too_large, DataBackup.MAX_RESTORE_MIB)
            DataBackup.RestoreStageStatus.MISSING_PASSPHRASE ->
                activity.getString(R.string.restore_stage_missing_passphrase)
            DataBackup.RestoreStageStatus.DECRYPT_FAILED ->
                activity.getString(R.string.restore_stage_decrypt_failed)
            DataBackup.RestoreStageStatus.TOO_NEW ->
                activity.getString(R.string.restore_stage_too_new)
            DataBackup.RestoreStageStatus.MIGRATION_FAILED ->
                activity.getString(R.string.restore_stage_migration_failed)
            DataBackup.RestoreStageStatus.NOT_A_BACKUP ->
                activity.getString(R.string.restore_failed_invalid_file)
            DataBackup.RestoreStageStatus.OK ->
                activity.getString(R.string.restore_stage_not_prepared)
        }

    private fun showRestoreProgress(
        title: String,
        detail: String,
        busy: Boolean = true,
        tone: String = "busy",
        phase: String? = null,
        bytesDone: Long = -1L,
        bytesTotal: Long = -1L,
        rowsDone: Long = -1L,
        rowsTotal: Long = -1L,
        percent: Int = -1,
        etaSeconds: Long = -1L,
    ) {
        val resolvedPercent =
            if (percent >= 0) {
                percent.coerceIn(0, 100)
            } else if (!busy && tone == "ok") {
                100
            } else {
                progressPercent(bytesDone, bytesTotal, rowsDone, rowsTotal)
            }
        activity.publishRestoreProgress(
            true,
            busy,
            title,
            detail,
            tone,
            phase,
            bytesDone,
            bytesTotal,
            rowsDone,
            rowsTotal,
            resolvedPercent,
            etaSeconds,
        )
    }

    private fun hideRestoreProgress() {
        activity.publishRestoreProgress(false, false, null, null, "idle", null, -1L, -1L, -1L, -1L, -1, -1L)
    }

    private fun logRestore(
        event: String,
        fields: Map<String, *>,
    ) {
        val suffix =
            if (fields.isEmpty()) {
                ""
            } else {
                fields.entries.joinToString(prefix = " ") { (key, value) -> "$key=${OBDLog.format(value)}" }
            }
        restoreLog.i(MainActivity.TAG, "backup_restore_$event$suffix")
    }

    private fun runBackground(
        unavailableMessage: String,
        task: Runnable,
    ): Boolean {
        val worker = executor
        if (worker == null) {
            activity.publishStatus("blocked", unavailableMessage, true)
            return false
        }
        try {
            worker.execute(task)
            return true
        } catch (ex: RejectedExecutionException) {
            activity.publishStatus("blocked", unavailableMessage, true)
        } catch (ex: RuntimeException) {
            activity.publishStatus("blocked", unavailableMessage, true)
        }
        return false
    }

    private inner class ProgressEmitter(
        private val title: String,
        private val fallbackDetail: String,
    ) {
        private val startedAtMs = System.currentTimeMillis()
        private var lastPublishedAtMs = 0L

        fun onDataBackupProgress(snapshot: DataBackup.ProgressSnapshot) {
            publish(
                phase = snapshot.phase,
                detail = snapshot.detail,
                bytesDone = snapshot.bytesDone,
                bytesTotal = snapshot.bytesTotal,
                rowsDone = snapshot.rowsDone,
                rowsTotal = snapshot.rowsTotal,
            )
        }

        fun onMergeProgress(
            phase: String,
            rowsDone: Long,
            rowsTotal: Long,
        ) {
            publish(
                phase = phase,
                detail = fallbackDetail,
                rowsDone = rowsDone,
                rowsTotal = rowsTotal,
            )
        }

        private fun publish(
            phase: String?,
            detail: String?,
            bytesDone: Long = -1L,
            bytesTotal: Long = -1L,
            rowsDone: Long = -1L,
            rowsTotal: Long = -1L,
        ) {
            val now = System.currentTimeMillis()
            val percent = progressPercent(bytesDone, bytesTotal, rowsDone, rowsTotal)
            val complete = percent >= 100
            if (lastPublishedAtMs > 0L &&
                now - lastPublishedAtMs < PROGRESS_UPDATE_INTERVAL_MS &&
                !complete
            ) {
                return
            }
            lastPublishedAtMs = now
            val etaSeconds = estimateEtaSeconds(bytesDone, bytesTotal, rowsDone, rowsTotal, startedAtMs, now)
            activity.runOnUiThread {
                showRestoreProgress(
                    title,
                    detail ?: fallbackDetail,
                    phase = phase,
                    bytesDone = bytesDone,
                    bytesTotal = bytesTotal,
                    rowsDone = rowsDone,
                    rowsTotal = rowsTotal,
                    percent = percent,
                    etaSeconds = etaSeconds,
                )
            }
        }
    }

    private class MergeOutcome(
        val result: RestoreResult,
        val message: String?,
    )

    private fun applyMerge(
        staged: File,
        progress: ProgressEmitter,
    ): MergeOutcome {
        try {
            val store = activity.localStore
            if (store == null) {
                return MergeOutcome(RestoreResult.OTHER, null)
            }
            if (!stopLoggingForRestore()) {
                return MergeOutcome(RestoreResult.LOGGING_ACTIVE, null)
            }
            val merged =
                store.mergeFrom(
                    staged,
                    DatabaseMerger.ProgressListener { phase, rowsDone, rowsTotal ->
                        progress.onMergeProgress(phase, rowsDone, rowsTotal)
                    },
                )
            if (!merged.ok) {
                return MergeOutcome(RestoreResult.OTHER, merged.summary())
            }
            return MergeOutcome(RestoreResult.OK, merged.summary())
        } catch (ex: RuntimeException) {
            return MergeOutcome(RestoreResult.OTHER, null)
        } finally {
            DataBackup.deleteIfExists(staged)
        }
    }

    private fun applyReplace(
        staged: File,
        progress: ProgressEmitter,
    ): RestoreResult {
        var restoreTemp: File? = null
        var restoreBackup: File? = null
        try {
            val dbFile = activity.localStore?.getDatabaseFile() ?: return RestoreResult.OTHER
            if (!stopLoggingForRestore()) {
                return RestoreResult.LOGGING_ACTIVE
            }
            val activeStore = activity.localStore
            if (activeStore != null) {
                activeStore.checkpoint()
                activeStore.close()
                activity.localStore = null
            }
            restoreTemp = File(dbFile.path + ".restore-new")
            restoreBackup = File(dbFile.path + ".restore-backup")
            DataBackup.deleteIfExists(restoreTemp)
            DataBackup.deleteIfExists(restoreBackup)
            DataBackup.copyFile(
                staged,
                restoreTemp,
                DataBackup.ProgressListener { snapshot -> progress.onDataBackupProgress(snapshot) },
                activity.getString(R.string.progress_phase_copying_backup),
                activity.getString(R.string.progress_replace_worker_detail),
            )
            DataBackup.deleteIfExists(File(dbFile.path + "-wal"))
            DataBackup.deleteIfExists(File(dbFile.path + "-shm"))
            if (dbFile.exists()) {
                DataBackup.renameFile(dbFile, restoreBackup)
            }
            try {
                DataBackup.renameFile(restoreTemp, dbFile)
            } catch (ex: Exception) {
                if (ex is IOException || ex is RuntimeException) {
                    restoreOriginalDatabase(dbFile, restoreBackup)
                    throw ex
                }
                throw ex
            }
            try {
                activity.localStore = ObdLocalStore(activity)
            } catch (ex: RuntimeException) {
                restoreOriginalDatabase(dbFile, restoreBackup)
                activity.localStore = ObdLocalStore(activity)
                throw ex
            }
            return RestoreResult.OK
        } catch (ex: Exception) {
            if (ex is IOException || ex is RuntimeException) {
                if (activity.localStore == null) {
                    try {
                        activity.localStore = ObdLocalStore(activity)
                    } catch (ignored: RuntimeException) {
                        // Nothing more we can do; the next launch will recreate it.
                    }
                }
                return RestoreResult.OTHER
            }
            throw ex
        } finally {
            DataBackup.deleteIfExists(restoreTemp)
            DataBackup.deleteIfExists(restoreBackup)
            staged.delete()
        }
    }

    private fun stopLoggingForRestore(): Boolean {
        if (!activity.isLoggingActive()) {
            return true
        }
        try {
            activity.stopObdService()
        } catch (ex: RuntimeException) {
            return false
        }
        val deadline = System.currentTimeMillis() + RESTORE_STOP_TIMEOUT_MS
        while (activity.isLoggingActive() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50L)
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return !activity.isLoggingActive()
    }

    companion object {
        const val REQUEST_RESTORE = 4202
        private const val RESTORE_STOP_TIMEOUT_MS = 30_000L
        private const val PROGRESS_UPDATE_INTERVAL_MS = 250L

        private fun hasPassphrase(passphrase: String?): Boolean = !passphrase?.trim().isNullOrEmpty()

        private fun progressPercent(
            bytesDone: Long,
            bytesTotal: Long,
            rowsDone: Long,
            rowsTotal: Long,
        ): Int {
            val done: Long
            val total: Long
            if (bytesTotal > 0L && bytesDone >= 0L) {
                done = bytesDone
                total = bytesTotal
            } else if (rowsTotal > 0L && rowsDone >= 0L) {
                done = rowsDone
                total = rowsTotal
            } else {
                return -1
            }
            return ((done.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        }

        private fun estimateEtaSeconds(
            bytesDone: Long,
            bytesTotal: Long,
            rowsDone: Long,
            rowsTotal: Long,
            startedAtMs: Long,
            nowMs: Long,
        ): Long {
            val done: Long
            val total: Long
            if (bytesTotal > 0L && bytesDone >= 0L) {
                done = bytesDone
                total = bytesTotal
            } else if (rowsTotal > 0L && rowsDone >= 0L) {
                done = rowsDone
                total = rowsTotal
            } else {
                return -1L
            }
            if (done >= total) {
                return 0L
            }
            val elapsedMs = nowMs - startedAtMs
            if (done <= 0L || elapsedMs < 500L) {
                return -1L
            }
            val unitsPerMs = done.toDouble() / elapsedMs.toDouble()
            if (unitsPerMs <= 0.0) {
                return -1L
            }
            val remainingMs = (total - done).toDouble() / unitsPerMs
            return ceil(remainingMs / 1000.0).toLong().coerceAtLeast(1L)
        }

        private fun restoreOriginalDatabase(
            dbFile: File,
            restoreBackup: File?,
        ) {
            if (restoreBackup == null || !restoreBackup.exists()) {
                return
            }
            try {
                DataBackup.deleteIfExists(dbFile)
                DataBackup.renameFile(restoreBackup, dbFile)
                DataBackup.deleteIfExists(File(dbFile.path + "-wal"))
                DataBackup.deleteIfExists(File(dbFile.path + "-shm"))
            } catch (ex: Exception) {
                if (ex !is IOException && ex !is RuntimeException) {
                    throw ex
                }
                Log.w(MainActivity.TAG, "restoreOriginalDatabase failed; original DB may be gone", ex)
            }
        }
    }
}
