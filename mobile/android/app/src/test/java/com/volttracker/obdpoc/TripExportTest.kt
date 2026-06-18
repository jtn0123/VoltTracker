package com.volttracker.obdpoc

import android.content.Context
import com.volttracker.obdpoc.data.ObdLocalStore
import com.volttracker.obdpoc.data.VoltTrackerDb
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Robolectric coverage for the file/share/recording half of the per-trip export: that
 * [TripExportShareIntent.writeExport] writes a real cache file (and degrades gracefully on an empty
 * route), and that [ObdLocalStore.recordExport] inserts a well-formed row into the `exports` table
 * — the table that was scaffolded-but-unwired until this feature.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripExportTest {
    private lateinit var context: Context
    private lateinit var store: ObdLocalStore
    private lateinit var dbHelper: VoltTrackerDb

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        store = ObdLocalStore(context)
        dbHelper = VoltTrackerDb(context)
        wipe(File(context.cacheDir, "trip-exports"))
    }

    @After
    fun tearDown() {
        store.close()
        dbHelper.close()
        wipe(File(context.cacheDir, "trip-exports"))
    }

    @Test
    fun writeExportWritesGpxFileToCacheDir() {
        val export = TripExportShareIntent.writeExport(context, sampleRoute(), TripExportShareIntent.Format.GPX)

        assertNotNull("a non-empty route must produce a file", export)
        val written = export!!
        assertTrue("file exists on disk", written.file.exists())
        assertTrue("file lands in the trip-exports cache dir", written.file.parentFile?.name == "trip-exports")
        assertTrue("file uses the .gpx extension", written.name.endsWith(".gpx"))
        assertEquals(3, written.pointCount)
        assertTrue("byte size is reported", written.bytes > 0L)
        val text = written.file.readText()
        assertTrue("content is GPX", text.contains("<gpx version=\"1.1\""))
        assertEquals("application/gpx+xml", written.format.mime)
    }

    @Test
    fun writeExportWritesCsvFileToCacheDir() {
        val export = TripExportShareIntent.writeExport(context, sampleRoute(), TripExportShareIntent.Format.CSV)

        assertNotNull(export)
        assertTrue(export!!.name.endsWith(".csv"))
        assertTrue(export.file.readText().startsWith("index,timestamp_iso"))
    }

    @Test
    fun writeExportReturnsNullForAnEmptyRoute() {
        val empty = JSONObject().put("session", JSONObject().put("id", "1:0:0")).put("points", JSONArray())

        assertNull(
            "an empty route must not write a file",
            TripExportShareIntent.writeExport(context, empty, TripExportShareIntent.Format.GPX),
        )
    }

    @Test
    fun formatFromKeyParsesGpxAndCsvAndRejectsOthers() {
        assertEquals(TripExportShareIntent.Format.GPX, TripExportShareIntent.Format.fromKey(" GPX "))
        assertEquals(TripExportShareIntent.Format.CSV, TripExportShareIntent.Format.fromKey("csv"))
        assertNull(TripExportShareIntent.Format.fromKey("kml"))
        assertNull(TripExportShareIntent.Format.fromKey(null))
    }

    @Test
    fun recordExportInsertsARowIntoTheExportsTable() {
        // The exports.session_id FK references obd_sessions(_id); the real flow always exports an
        // existing logged trip, so seed the parent session before recording.
        seedSession(7L)
        val before = exportRowCount()

        val rowId =
            store.recordExport(
                "7:1000:2000",
                "gpx",
                "volt-trip-2024-05-01-0930-7.gpx",
                "application/gpx+xml",
                512L,
            )

        assertTrue("a row id is returned", rowId > 0L)
        assertEquals("exactly one row was inserted", before + 1, exportRowCount())

        val row = readExportRow(rowId)
        assertEquals(7L, row.sessionId)
        assertEquals("gpx", row.exportType)
        assertEquals("volt-trip-2024-05-01-0930-7.gpx", row.fileName)
        assertEquals(512L, row.bytes)
        assertEquals("completed", row.status)
        assertEquals(1000L, row.rangeStartMs)
        assertEquals(2000L, row.rangeEndMs)
        assertEquals("precise-location flag is set for a GPS track export", 1, row.includePreciseLocation)
    }

    @Test
    fun recordExportForABareSessionIdLeavesRangeNull() {
        seedSession(42L)
        val rowId = store.recordExport("42", "csv", "volt-trip.csv", "text/csv", 64L)

        val row = readExportRow(rowId)
        assertEquals(42L, row.sessionId)
        assertNull("a bare session id has no time range", row.rangeStartMs)
        assertNull(row.rangeEndMs)
    }

    // Inserts a session row with an explicit _id so the exports.session_id FK is satisfiable. Uses
    // the same on-disk DB file the store writes to.
    private fun seedSession(sessionId: Long) {
        val values = android.content.ContentValues()
        values.put("_id", sessionId)
        values.put("mode", ObdLocalStore.MODE_OBD)
        values.put("started_at_ms", 1_000L)
        values.put("status", "complete")
        values.put("sample_count", 0)
        values.put("created_at_ms", 1_000L)
        dbHelper.writableDatabase.insertOrThrow(VoltTrackerDb.TABLE_SESSIONS, null, values)
    }

    private fun exportRowCount(): Long {
        readDb().rawQuery("SELECT COUNT(*) FROM ${VoltTrackerDb.TABLE_EXPORTS}", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    // A second helper over the same DB file reads back what the store wrote (the store keeps its
    // own helper internal). Robolectric serves both from one on-disk SQLite file.
    private fun readDb() = dbHelper.readableDatabase

    private fun readExportRow(rowId: Long): ExportRow {
        readDb()
            .rawQuery(
                "SELECT session_id, export_type, file_name, bytes, status, range_start_ms, range_end_ms, " +
                    "include_precise_location FROM ${VoltTrackerDb.TABLE_EXPORTS} WHERE _id = ?",
                arrayOf(rowId.toString()),
            ).use { cursor ->
                assertTrue("row $rowId must exist", cursor.moveToFirst())
                return ExportRow(
                    sessionId = if (cursor.isNull(0)) null else cursor.getLong(0),
                    exportType = cursor.getString(1),
                    fileName = cursor.getString(2),
                    bytes = cursor.getLong(3),
                    status = cursor.getString(4),
                    rangeStartMs = if (cursor.isNull(5)) null else cursor.getLong(5),
                    rangeEndMs = if (cursor.isNull(6)) null else cursor.getLong(6),
                    includePreciseLocation = cursor.getInt(7),
                )
            }
    }

    private class ExportRow(
        val sessionId: Long?,
        val exportType: String?,
        val fileName: String?,
        val bytes: Long,
        val status: String?,
        val rangeStartMs: Long?,
        val rangeEndMs: Long?,
        val includePreciseLocation: Int,
    )

    private companion object {
        private fun sampleRoute(): JSONObject {
            val points = JSONArray()
            points.put(
                JSONObject()
                    .put("atMs", 10_000L)
                    .put("lat", 34.05)
                    .put("lng", -118.25)
                    .put("speedMps", 10.0),
            )
            points.put(
                JSONObject()
                    .put("atMs", 20_000L)
                    .put("lat", 34.16)
                    .put("lng", -118.32)
                    .put("speedMps", 11.0),
            )
            points.put(
                JSONObject()
                    .put("atMs", 30_000L)
                    .put("lat", 34.0)
                    .put("lng", -118.32)
                    .put("speedMps", 12.0),
            )
            return JSONObject()
                .put(
                    "session",
                    JSONObject().put("id", "1:10000:30000").put("sessionId", 1).put("adapterName", "OBDLink"),
                ).put("points", points)
        }

        private fun wipe(dir: File) {
            if (dir.isDirectory) {
                dir.listFiles()?.forEach { it.delete() }
                dir.delete()
            }
        }
    }
}
