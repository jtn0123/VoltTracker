package com.volttracker.obdpoc.data

/**
 * Best-effort bookkeeping of completed share-sheet exports into the `exports` table. Extends the
 * [ObdSessionStore]/[ObdQueryStore] convention of narrow capability interfaces — callers reach
 * this via [ObdLocalStore.exportLog] instead of flat methods on the facade.
 */
interface ObdExportLogStore {
    /**
     * Records a completed per-trip GPX/CSV export into the `exports` table (one row per share).
     * Best-effort: returns the new row id, or -1 if the insert failed (recording must never break
     * the share). See [ObdStoreWriter.recordExport].
     */
    fun recordExport(
        routeKey: String?,
        exportType: String,
        fileName: String,
        mimeType: String,
        bytes: Long,
    ): Long

    /**
     * Records a completed bulk all-trips CSV export (M6) into the `exports` table. The export spans
     * every trip rather than one session, so it has no single session id / time range. Best-effort:
     * returns the new row id, or -1 if the insert failed (recording must never break the share).
     */
    fun recordAllTripsExport(
        exportType: String,
        fileName: String,
        mimeType: String,
        bytes: Long,
    ): Long
}
