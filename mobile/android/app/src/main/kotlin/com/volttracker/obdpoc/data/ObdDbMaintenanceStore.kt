package com.volttracker.obdpoc.data

import java.io.File

/**
 * Whole-database maintenance the backup/restore and startup paths drive: integrity checks,
 * donor-file merges, and startup prune+vacuum. Implemented by [ObdStoreMaintenance]; callers reach
 * it via [ObdLocalStore.dbMaintenance]. Extends the [ObdSessionStore]/[ObdQueryStore] convention
 * of narrow capability interfaces. (Routine per-row upkeep — [ObdSessionStore.checkpoint],
 * [ObdSessionStore.pruneRawDataOlderThan], [ObdSessionStore.clearAllData] — stays on the write
 * interface the recording paths already depend on.)
 */
interface ObdDbMaintenanceStore {
    /**
     * Runs `PRAGMA quick_check` (a faster `integrity_check` that skips index-content
     * verification) and reports up to [maxProblems] problem rows. Never throws — any
     * failure to even run the check is reported as a non-ok result.
     */
    fun quickCheck(maxProblems: Int = ObdStoreMaintenance.MAX_QUICK_CHECK_PROBLEMS): ObdStoreMaintenance.IntegrityResult

    /** Merges a restored donor database file into the live database. See [DatabaseMerger]. */
    fun mergeFrom(
        donorDbFile: File?,
        progressListener: DatabaseMerger.ProgressListener? = null,
    ): DatabaseMerger.MergeResult

    /**
     * Startup maintenance: prunes raw rows older than [keepDays], then VACUUMs when enough
     * freed pages have accumulated. Returns the pruned row count.
     */
    fun runStartupMaintenance(keepDays: Int): Int
}
