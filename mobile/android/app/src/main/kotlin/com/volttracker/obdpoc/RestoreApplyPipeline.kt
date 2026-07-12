package com.volttracker.obdpoc

import android.util.Log
import com.volttracker.obdpoc.data.DatabaseMerger
import com.volttracker.obdpoc.data.ObdLocalStore
import java.io.File
import java.io.IOException

/**
 * Applies a staged, verified restore file to the live database for [BackupController]:
 * the additive merge ([applyMerge]) and the destructive replace ([applyReplace]) with its
 * staging/rollback ladder (stage to `.restore-new`, keep a `.restore-backup` safety copy,
 * roll the original back on rename/open failure, and preserve the safety copy if that
 * rollback itself fails).
 *
 * Extracted verbatim from [BackupController] (LargeClass ratchet); behavior is identical.
 * The cancellation gate is a lambda because the controller owns the disposed flag and the
 * pipeline must also observe the worker thread's interrupt state at call time.
 */
class RestoreApplyPipeline(
    private val activity: MainActivity,
    private val isCancelled: () -> Boolean,
) {
    enum class Result {
        OK,
        INVALID_FILE,
        LOGGING_ACTIVE,
        OTHER,
    }

    class MergeOutcome(
        val result: Result,
        val message: String?,
    )

    fun applyMerge(
        staged: File,
        progress: BackupRestoreProgressPresenter.Emitter,
    ): MergeOutcome {
        val databaseLease =
            DatabaseOperationLease.tryAcquire(OPERATION_RESTORE)
                ?: return MergeOutcome(Result.OTHER, null)
        try {
            ensureActive()
            val store = activity.localStore
            if (store == null) {
                return MergeOutcome(Result.OTHER, null)
            }
            if (!stopLoggingForRestore()) {
                return MergeOutcome(Result.LOGGING_ACTIVE, null)
            }
            ensureActive()
            val merged =
                store.dbMaintenance.mergeFrom(
                    staged,
                    DatabaseMerger.ProgressListener { phase, rowsDone, rowsTotal ->
                        ensureActive()
                        progress.onMergeProgress(phase, rowsDone, rowsTotal)
                    },
                )
            if (!merged.ok) {
                return MergeOutcome(Result.OTHER, merged.summary())
            }
            return MergeOutcome(Result.OK, merged.summary())
        } catch (ex: RuntimeException) {
            return MergeOutcome(Result.OTHER, null)
        } finally {
            databaseLease.close()
            DataBackup.deleteIfExists(staged)
        }
    }

    fun applyReplace(
        staged: File,
        progress: BackupRestoreProgressPresenter.Emitter,
    ): Result {
        var restoreTemp: File? = null
        var restoreBackup: File? = null
        var preserveRestoreBackup = false
        val databaseLease = DatabaseOperationLease.tryAcquire(OPERATION_RESTORE) ?: return Result.OTHER
        try {
            ensureActive()
            val dbFile = activity.localStore?.getDatabaseFile() ?: return Result.OTHER
            if (!stopLoggingForRestore()) {
                return Result.LOGGING_ACTIVE
            }
            ensureActive()
            val activeStore = activity.localStore
            if (activeStore != null) {
                if (!activeStore.checkpoint()) {
                    Log.w(AppPrefs.LOG_TAG, "replace restore aborted: live WAL checkpoint stayed incomplete")
                    return Result.OTHER
                }
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
            ensureActive()
            DataBackup.deleteIfExists(File(dbFile.path + "-wal"))
            DataBackup.deleteIfExists(File(dbFile.path + "-shm"))
            if (dbFile.exists()) {
                DataBackup.renameFile(dbFile, restoreBackup)
            }
            try {
                DataBackup.renameFile(restoreTemp, dbFile)
            } catch (ex: IOException) {
                preserveRestoreBackup = !restoreOriginalDatabase(dbFile, restoreBackup)
                throw ex
            } catch (ex: RuntimeException) {
                preserveRestoreBackup = !restoreOriginalDatabase(dbFile, restoreBackup)
                throw ex
            }
            try {
                activity.localStore = ObdLocalStore(activity)
            } catch (ex: RuntimeException) {
                val rolledBack = restoreOriginalDatabase(dbFile, restoreBackup)
                preserveRestoreBackup = !rolledBack
                if (rolledBack) activity.localStore = ObdLocalStore(activity)
                throw ex
            }
            return Result.OK
        } catch (ex: Exception) {
            if (ex is IOException || ex is RuntimeException) {
                if (activity.localStore == null && !isCancelled() && !preserveRestoreBackup) {
                    try {
                        activity.localStore = ObdLocalStore(activity)
                    } catch (ignored: RuntimeException) {
                        // Nothing more we can do; the next launch will recreate it.
                    }
                }
                return Result.OTHER
            }
            throw ex
        } finally {
            databaseLease.close()
            DataBackup.deleteIfExists(restoreTemp)
            if (shouldDeleteRestoreSafetyCopy(preserveRestoreBackup)) {
                DataBackup.deleteIfExists(restoreBackup)
            } else {
                Log.e(AppPrefs.LOG_TAG, "preserving restore safety copy after rollback failure: $restoreBackup")
            }
            DataBackup.deleteIfExists(staged)
        }
    }

    private fun stopLoggingForRestore(): Boolean {
        if (isCancelled()) return false
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

    private fun ensureActive() {
        if (isCancelled()) throw RestoreCancelledException()
    }

    private class RestoreCancelledException : RuntimeException("Restore cancelled during teardown")

    companion object {
        private const val OPERATION_RESTORE = "restore"
        private const val RESTORE_STOP_TIMEOUT_MS = 30_000L

        internal fun shouldDeleteRestoreSafetyCopy(rollbackFailed: Boolean): Boolean = !rollbackFailed

        private fun restoreOriginalDatabase(
            dbFile: File,
            restoreBackup: File?,
        ): Boolean {
            if (restoreBackup == null || !restoreBackup.exists()) {
                return true
            }
            try {
                DataBackup.deleteIfExists(dbFile)
                DataBackup.renameFile(restoreBackup, dbFile)
                DataBackup.deleteIfExists(File(dbFile.path + "-wal"))
                DataBackup.deleteIfExists(File(dbFile.path + "-shm"))
                return true
            } catch (ex: Exception) {
                if (ex !is IOException && ex !is RuntimeException) {
                    throw ex
                }
                Log.w(AppPrefs.LOG_TAG, "restoreOriginalDatabase failed; original DB may be gone", ex)
                return false
            }
        }
    }
}
