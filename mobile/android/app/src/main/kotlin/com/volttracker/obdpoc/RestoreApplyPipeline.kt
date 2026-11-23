package com.volttracker.obdpoc

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.volttracker.obdpoc.data.DatabaseMerger
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.data.ObdStoreMaintenance
import java.io.File
import java.io.IOException

/**
 * Applies a staged, verified restore file to the live database for [BackupController]:
 * the additive merge ([applyMerge]) and the destructive replace ([applyReplace]) with its
 * staging/rollback ladder (stage to `.restore-new`, keep a `.restore-backup` safety copy,
 * roll the original back on rename failure or when the restored file fails its first open and
 * quick check, and preserve the safety copy if that rollback itself fails).
 *
 * Extracted from [BackupController] (LargeClass ratchet).
 * The cancellation gate is a lambda because the controller owns the disposed flag and the
 * pipeline must also observe the worker thread's interrupt state at call time.
 */
class RestoreApplyPipeline<A>(
    private val activity: A,
    private val isCancelled: () -> Boolean,
) where A : Context, A : BackupHost {
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
            if (!closeLiveStoreForReplace()) {
                return Result.OTHER
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
                activity.localStore = openRestoredStore(dbFile)
            } catch (ex: RuntimeException) {
                val rolledBack = restoreOriginalDatabase(dbFile, restoreBackup)
                preserveRestoreBackup = !rolledBack
                if (rolledBack) activity.localStore = ObdLocalStore(activity)
                throw ex
            }
            return Result.OK
        } catch (ex: Exception) {
            if (ex is IOException || ex is RuntimeException) {
                reopenStoreAfterFailedReplace(preserveRestoreBackup)
                return Result.OTHER
            }
            throw ex
        } finally {
            databaseLease.close()
            DataBackup.deleteIfExists(restoreTemp)
            cleanUpRestoreSafetyCopy(restoreBackup, preserveRestoreBackup)
            DataBackup.deleteIfExists(staged)
        }
    }

    /**
     * Opens and quick-checks the swapped-in file before the safety copy may go: constructing
     * [ObdLocalStore] alone never opens the file. The check runs on a connection of its own whose
     * corruption handler does nothing, so a file SQLite rejects never reaches the live database's
     * handler, which would set it aside as damaged history and tell the owner their history was
     * reset. The store then opens its writable database (running any migration) inside the same
     * rollback window. Throws to trigger the rollback.
     */
    private fun openRestoredStore(dbFile: File): ObdLocalStore {
        var detached: SQLiteDatabase? = null
        val integrity =
            try {
                ObdStoreMaintenance.quickCheck(ObdStoreMaintenance.MAX_QUICK_CHECK_PROBLEMS) {
                    // Leaves the file alone: the rollback below puts the original back over it.
                    val keepFile = DatabaseErrorHandler { _ -> }
                    SQLiteDatabase
                        .openDatabase(dbFile.path, null, DETACHED_OPEN_FLAGS, keepFile)
                        .also { detached = it }
                }
            } finally {
                detached?.close()
            }
        check(integrity.ok) { "restored database failed its quick check: ${integrity.problems}" }
        val store = ObdLocalStore(activity)
        if (!store.checkpoint()) {
            store.close()
            throw IllegalStateException("restored database did not open")
        }
        return store
    }

    /** Checkpoints and closes the live store so its file can be swapped; false aborts the replace. */
    private fun closeLiveStoreForReplace(): Boolean {
        val activeStore = activity.localStore ?: return true
        if (!activeStore.checkpoint()) {
            Log.w(AppPrefs.LOG_TAG, "replace restore aborted: live WAL checkpoint stayed incomplete")
            return false
        }
        activeStore.close()
        activity.localStore = null
        return true
    }

    /** After a failed replace, reopen whatever database is in place unless a safety copy must stay. */
    private fun reopenStoreAfterFailedReplace(preserveRestoreBackup: Boolean) {
        if (activity.localStore != null || isCancelled() || preserveRestoreBackup) {
            return
        }
        try {
            activity.localStore = ObdLocalStore(activity)
        } catch (ignored: RuntimeException) {
            // Nothing more we can do; the next launch will recreate it.
        }
    }

    private fun cleanUpRestoreSafetyCopy(
        restoreBackup: File?,
        preserveRestoreBackup: Boolean,
    ) {
        if (shouldDeleteRestoreSafetyCopy(preserveRestoreBackup)) {
            DataBackup.deleteIfExists(restoreBackup)
        } else {
            Log.e(AppPrefs.LOG_TAG, "preserving restore safety copy after rollback failure: $restoreBackup")
        }
    }

    private fun stopLoggingForRestore(): Boolean {
        if (isCancelled()) return false
        if (activity.isLoggingActive() || DatabaseOperationLease.hasPersistenceOwners()) {
            try {
                activity.stopObdService()
            } catch (ex: RuntimeException) {
                return false
            }
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
        val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
        return !activity.isLoggingActive() && DatabaseOperationLease.awaitPersistenceQuiescence(remaining, isCancelled)
    }

    private fun ensureActive() {
        if (isCancelled()) throw RestoreCancelledException()
    }

    private class RestoreCancelledException : RuntimeException("Restore cancelled during teardown")

    companion object {
        private const val OPERATION_RESTORE = "restore"
        private const val RESTORE_STOP_TIMEOUT_MS = 30_000L
        private const val DETACHED_OPEN_FLAGS =
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING

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
