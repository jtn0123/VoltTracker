package com.volttracker.obdpoc

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
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
            activity.publishStatus("blocked", "Stop logging before creating a backup.", true)
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
            activity.publishStatus("blocked", "Stop logging before creating a backup.", true)
            return
        }
        showShareDisclosure { performBackupAndShare(true, passphrase) }
    }

    fun shareDisclosureMessage(): String =
        "This backup is a full copy of Volt Tracker's on-device database and contains:\n" +
            "• Raw OBD session logs and telemetry\n" +
            "• Precise GPS coordinates from your trips\n" +
            "• Bluetooth adapter addresses\n" +
            "• Redacted VIN and vehicle records if your car shared them\n" +
            "\n" +
            "Encrypted backups are protected by your passphrase; plaintext backups are not.\n" +
            "Use a strong, unique passphrase with at least ${DataBackup.MIN_PASSPHRASE_LENGTH} characters. " +
            "Volt Tracker cannot recover it if it is lost.\n" +
            "Only share with people you trust."

    private fun showShareDisclosure(onConfirmed: Runnable) {
        try {
            AlertDialog
                .Builder(activity)
                .setTitle("Share Volt Tracker backup")
                .setMessage(shareDisclosureMessage())
                .setPositiveButton("Share anyway") { _, _ -> onConfirmed.run() }
                .setNegativeButton("Cancel") { _, _ ->
                    activity.publishStatus("ready", "Backup cancelled.", false)
                }.setOnCancelListener {
                    activity.publishStatus("ready", "Backup cancelled.", false)
                }.show()
        } catch (ex: RuntimeException) {
            activity.publishStatus("blocked", "Could not show the backup disclosure.", true)
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
            activity.publishStatus("blocked", "Stop logging before creating a backup.", true)
            return
        }
        activity.publishStatus(
            "ready",
            if (encrypted) "Preparing encrypted data backup..." else "Preparing data backup...",
            false,
        )
        val title = if (encrypted) "Preparing encrypted backup" else "Preparing backup"
        val detail =
            if (encrypted) {
                "Encrypting your on-phone Volt Tracker data before sharing."
            } else {
                "Writing your on-phone Volt Tracker data to a shareable file."
            }
        showRestoreProgress(title, detail, phase = "Preparing backup")
        runBackground("Could not start the backup worker.") {
            val progress = ProgressEmitter(title, detail)
            val backup =
                if (encrypted) {
                    dataBackup.buildEncryptedBackupFile(
                        activity.localStore,
                        passphrase,
                        DataBackup.ProgressListener { snapshot -> progress.onDataBackupProgress(snapshot) },
                    )
                } else {
                    dataBackup.buildBackupFile(
                        activity.localStore,
                        DataBackup.ProgressListener { snapshot -> progress.onDataBackupProgress(snapshot) },
                    )
                }
            activity.runOnUiThread {
                if (backup == null) {
                    showRestoreProgress(
                        "Backup failed",
                        "Could not create the backup file.",
                        busy = false,
                        tone = "blocked",
                    )
                    activity.publishStatus("blocked", "Could not create the backup file.", true)
                    return@runOnUiThread
                }
                try {
                    val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", backup)
                    val share = Intent(Intent.ACTION_SEND)
                    share.type = "application/octet-stream"
                    share.putExtra(Intent.EXTRA_STREAM, uri)
                    share.putExtra(Intent.EXTRA_SUBJECT, backup.name)
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    activity.startActivity(Intent.createChooser(share, "Back up Volt Tracker data"))
                    showRestoreProgress(
                        "Backup ready",
                        if (encrypted) {
                            "Encrypted backup ready. Choose where to save it."
                        } else {
                            "Backup ready. Choose where to save it."
                        },
                        busy = false,
                        tone = "ok",
                        phase = "Ready to share",
                        percent = 100,
                    )
                    activity.publishStatus(
                        "ready",
                        if (encrypted) {
                            "Encrypted backup ready - choose where to save it."
                        } else {
                            "Backup ready - choose where to save it."
                        },
                        false,
                    )
                } catch (ex: RuntimeException) {
                    showRestoreProgress(
                        "Backup failed",
                        "Could not open the share sheet.",
                        busy = false,
                        tone = "blocked",
                    )
                    activity.publishStatus("blocked", "Could not open the share sheet.", true)
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
            activity.publishStatus("ready", "Restore cancelled - no file selected.", false)
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
            activity.publishStatus("blocked", "Enter the backup passphrase first.", true)
            return
        }
        launchRestorePicker(passphrase)
    }

    private fun launchRestorePicker(passphrase: String?) {
        if (activity.isLoggingActive()) {
            activity.publishStatus("blocked", "Stop logging before restoring a backup.", true)
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
            activity.publishStatus("ready", "Choose a Volt Tracker backup file.", false)
            activity.launchRestoreFilePicker(pick)
        } catch (ex: RuntimeException) {
            pendingRestorePassphrase = null
            activity.publishStatus("blocked", "Could not open the file picker.", true)
        }
    }

    private fun restoreFromUri(
        uri: Uri?,
        passphrase: String?,
    ) {
        val encrypted = hasPassphrase(passphrase)
        showRestoreProgress(
            if (encrypted) "Reading encrypted backup" else "Reading backup",
            "Large backup files can take a minute. Keep Volt Tracker open while the file is checked.",
        )
        logRestore("stage_start", mapOf("encrypted" to encrypted))
        activity.publishStatus(
            "ready",
            if (encrypted) {
                "Reading and decrypting selected backup..."
            } else {
                "Reading selected backup..."
            },
            false,
        )
        if (!runBackground("Could not start the restore worker.") {
                val progress =
                    ProgressEmitter(
                        if (encrypted) "Reading encrypted backup" else "Reading backup",
                        if (encrypted) {
                            "Reading and decrypting the selected backup."
                        } else {
                            "Reading and checking the selected backup."
                        },
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
            showRestoreProgress("Restore failed", "Could not start the restore worker.", busy = false, tone = "blocked")
        }
    }

    private fun promptRestoreMode(staged: File) {
        try {
            AlertDialog
                .Builder(activity)
                .setTitle("Restore backup")
                .setMessage(
                    "Merge adds this backup's data to what's already on your phone. " +
                        "Existing sessions are matched so missing route samples can fill in.\n\n" +
                        "Replace erases everything on this phone first, then loads the backup.",
                ).setPositiveButton("Merge") { _, _ -> performMerge(staged) }
                .setNegativeButton("Replace all") { _, _ -> performReplace(staged) }
                .setNeutralButton("Cancel") { _, _ -> cancelStagedRestore(staged) }
                .setOnCancelListener { cancelStagedRestore(staged) }
                .show()
        } catch (ex: RuntimeException) {
            cancelStagedRestore(staged)
            activity.publishStatus("blocked", "Could not show the restore options.", true)
        }
    }

    private fun cancelStagedRestore(staged: File?) {
        DataBackup.deleteIfExists(staged)
        hideRestoreProgress()
        activity.publishStatus("ready", "Restore cancelled.", false)
    }

    private fun performReplace(staged: File) {
        showRestoreProgress(
            "Restoring backup",
            "Replacing the on-phone database. Large backups can take a minute.",
        )
        logRestore("replace_start", emptyMap<String, Any?>())
        activity.publishStatus("ready", "Restoring backup...", false)
        if (!runBackground("Could not start the restore worker.") {
                val progress =
                    ProgressEmitter(
                        "Restoring backup",
                        "Replacing the on-phone database with the selected backup.",
                    )
                val result = applyReplace(staged, progress)
                activity.runOnUiThread {
                    if (result == RestoreResult.OK) {
                        activity.publishDeviceList()
                        activity.publishStorageSummary()
                        showRestoreProgress(
                            "Restore complete",
                            "Backup restored. Reconnect to resume logging.",
                            busy = false,
                            tone = "ok",
                        )
                        logRestore("replace_ok", emptyMap<String, Any?>())
                        activity.publishStatus("ready", "Backup restored - reconnect to resume logging.", false)
                    } else {
                        publishRestoreFailure(result)
                    }
                }
            }
        ) {
            showRestoreProgress("Restore failed", "Could not start the restore worker.", busy = false, tone = "blocked")
        }
    }

    private fun performMerge(staged: File) {
        showRestoreProgress(
            "Merging backup",
            "Adding backup rows and matching existing sessions. Large backups can take a minute.",
        )
        logRestore("merge_start", emptyMap<String, Any?>())
        activity.publishStatus("ready", "Merging backup...", false)
        if (!runBackground("Could not start the restore worker.") {
                val progress =
                    ProgressEmitter(
                        "Merging backup",
                        "Adding backup rows and matching existing sessions.",
                    )
                val outcome = applyMerge(staged, progress)
                activity.runOnUiThread {
                    if (outcome.result == RestoreResult.OK) {
                        activity.publishDeviceList()
                        activity.publishStorageSummary()
                        showRestoreProgress(
                            "Merge complete",
                            (outcome.message ?: "Backup merged.") + " Reconnect to resume logging.",
                            busy = false,
                            tone = "ok",
                        )
                        logRestore("merge_ok", emptyMap<String, Any?>())
                        activity.publishStatus("ready", outcome.message + " Reconnect to resume logging.", false)
                    } else if (outcome.result == RestoreResult.LOGGING_ACTIVE) {
                        publishRestoreFailure(outcome.result)
                    } else {
                        val message = outcome.message ?: "Merge failed - the backup could not be merged."
                        showRestoreProgress("Restore failed", message, busy = false, tone = "blocked")
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
            showRestoreProgress("Restore failed", "Could not start the restore worker.", busy = false, tone = "blocked")
        }
    }

    private fun publishRestoreFailure(result: RestoreResult) {
        val message = restoreFailureMessage(result)
        showRestoreProgress("Restore failed", message, busy = false, tone = "blocked")
        logRestore("apply_failed", mapOf("result" to result.name))
        activity.publishStatus("blocked", message, true)
    }

    private fun publishRestoreStageFailure(outcome: DataBackup.RestoreStageOutcome) {
        val message = restoreStageFailureMessage(outcome.status)
        if (outcome.status == DataBackup.RestoreStageStatus.NO_FILE) {
            hideRestoreProgress()
        } else {
            showRestoreProgress("Restore failed", message, busy = false, tone = "blocked")
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
        parts.add(if (outcome.encrypted) "Encrypted backup verified." else "Backup verified.")
        if (outcome.migrated) {
            parts.add("It was upgraded for this app.")
        }
        parts.add("Choose Merge or Replace all.")
        return parts.joinToString(" ")
    }

    private fun restoreFailureMessage(result: RestoreResult): String =
        if (result == RestoreResult.LOGGING_ACTIVE) {
            "Restore failed - logging is still active. Stop logging and try again."
        } else if (result == RestoreResult.INVALID_FILE) {
            "Restore failed - that file is not a valid Volt Tracker backup."
        } else {
            "Restore failed - could not replace the on-device database."
        }

    private fun restoreStageFailureMessage(status: DataBackup.RestoreStageStatus): String =
        when (status) {
            DataBackup.RestoreStageStatus.NO_FILE -> "Restore cancelled - no file selected."
            DataBackup.RestoreStageStatus.OPEN_FAILED ->
                "Restore failed - Android could not read the selected file."
            DataBackup.RestoreStageStatus.TOO_LARGE ->
                "Restore failed - that backup is larger than the " +
                    "${DataBackup.MAX_RESTORE_MIB} MiB on-phone importer limit."
            DataBackup.RestoreStageStatus.MISSING_PASSPHRASE ->
                "Restore failed - that backup is encrypted. Enter its passphrase first."
            DataBackup.RestoreStageStatus.DECRYPT_FAILED ->
                "Restore failed - the passphrase did not unlock that backup."
            DataBackup.RestoreStageStatus.TOO_NEW ->
                "Restore failed - that backup was created by a newer Volt Tracker app."
            DataBackup.RestoreStageStatus.MIGRATION_FAILED ->
                "Restore failed - the backup could not be upgraded for this app."
            DataBackup.RestoreStageStatus.NOT_A_BACKUP ->
                "Restore failed - that file is not a valid Volt Tracker backup."
            DataBackup.RestoreStageStatus.OK ->
                "Restore failed - the selected backup could not be prepared."
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
                "Copying backup into place",
                "Replacing the on-phone database with the selected backup.",
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

        private fun minimumPassphraseMessage(): String =
            "Enter a backup passphrase with at least ${DataBackup.MIN_PASSPHRASE_LENGTH} characters."

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
            } catch (ignored: Exception) {
                if (ignored !is IOException && ignored !is RuntimeException) {
                    throw ignored
                }
            }
        }
    }
}
