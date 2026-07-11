package com.volttracker.obdpoc.data

/**
 * [ObdExportLogStore] implementation: parses the route key back into the session window and
 * records the share through [ObdStoreWriter.recordExport].
 */
internal class ObdStoreExportLog(
    private val writer: ObdStoreWriter,
) : ObdExportLogStore {
    override fun recordExport(
        routeKey: String?,
        exportType: String,
        fileName: String,
        mimeType: String,
        bytes: Long,
    ): Long {
        val parsed = DriveWindowDetector.parseRouteKey(routeKey)
        return writer.recordExport(
            parsed?.sessionId ?: -1L,
            exportType,
            fileName,
            mimeType,
            bytes,
            parsed?.startedAtMs ?: -1L,
            parsed?.endedAtMs ?: -1L,
        )
    }

    override fun recordAllTripsExport(
        exportType: String,
        fileName: String,
        mimeType: String,
        bytes: Long,
    ): Long = writer.recordExport(-1L, exportType, fileName, mimeType, bytes, -1L, -1L)
}
