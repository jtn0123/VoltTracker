package com.volttracker.obdpoc

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.volttracker.obdpoc.data.ObdLocalStore
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

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
        if (!hasPassphrase(passphrase)) {
            activity.publishStatus("blocked", "Enter a backup passphrase first.", true)
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
            "Use a strong, unique passphrase. Volt Tracker cannot recover it if it is lost.\n" +
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
        runBackground("Could not start the backup worker.") {
            val backup =
                if (encrypted) {
                    dataBackup.buildEncryptedBackupFile(activity.localStore, passphrase)
                } else {
                    dataBackup.buildBackupFile(activity.localStore)
                }
            activity.runOnUiThread {
                if (backup == null) {
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
                val outcome = dataBackup.stageRestoreFileWithStatus(uri, passphrase)
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
                    "Merge adds this backup's trips to what's already on your phone " +
                        "(duplicate trips are skipped).\n\n" +
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
                val result = applyReplace(staged)
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
            "Adding the backup rows and skipping duplicate sessions. Large backups can take a minute.",
        )
        logRestore("merge_start", emptyMap<String, Any?>())
        activity.publishStatus("ready", "Merging backup...", false)
        if (!runBackground("Could not start the restore worker.") {
                val outcome = applyMerge(staged)
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
    ) {
        activity.publishRestoreProgress(true, busy, title, detail, tone)
    }

    private fun hideRestoreProgress() {
        activity.publishRestoreProgress(false, false, null, null, "idle")
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

    private class MergeOutcome(
        val result: RestoreResult,
        val message: String?,
    )

    private fun applyMerge(staged: File): MergeOutcome {
        try {
            val store = activity.localStore
            if (store == null) {
                return MergeOutcome(RestoreResult.OTHER, null)
            }
            if (!stopLoggingForRestore()) {
                return MergeOutcome(RestoreResult.LOGGING_ACTIVE, null)
            }
            val merged = store.mergeFrom(staged)
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

    private fun applyReplace(staged: File): RestoreResult {
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
            DataBackup.copyFile(staged, restoreTemp)
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

        private fun hasPassphrase(passphrase: String?): Boolean = !passphrase?.trim().isNullOrEmpty()

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
