package com.volttracker.obdpoc

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Covers the FileProvider/Intent half of the trip export that [TripExportTest] left untested (report
 * item D4): that [TripExportShareIntent.buildShareIntent] yields a `content://` share Intent with the
 * read-grant flag and the format's MIME, resolved against the manifest's `${applicationId}.fileprovider`
 * authority. A FileProvider misconfig (authority / file_paths.xml) is the classic "share crashes on
 * device, all unit tests pass" failure this pins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripExportShareIntentTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        wipe(File(context.cacheDir, "trip-exports"))
        // FileProvider caches a PathStrategy keyed to the first cacheDir it saw; a strategy cached by
        // an earlier test points at a stale root and makes getUriForFile throw. Clear it per test.
        resetFileProviderCache()
    }

    @After
    fun tearDown() {
        wipe(File(context.cacheDir, "trip-exports"))
    }

    private fun writtenGpx(): TripExportShareIntent.TripExportFile {
        val export = TripExportShareIntent.writeExport(context, sampleRoute(), TripExportShareIntent.Format.GPX)
        assertNotNull("a non-empty route must produce a file to share", export)
        return export!!
    }

    @Test
    fun buildShareIntentGrantsReadOnAContentUriForGpx() {
        val intent = TripExportShareIntent.buildShareIntent(context, writtenGpx())

        assertNotNull("a written export must yield a share intent", intent)
        val share = intent!!
        assertEquals(Intent.ACTION_SEND, share.action)
        assertEquals("the GPX MIME is set on the intent", "application/gpx+xml", share.type)

        val uri = share.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        assertNotNull("the export rides as an EXTRA_STREAM uri", uri)
        assertEquals("the file is shared via a content:// FileProvider uri", "content", uri!!.scheme)
        assertEquals(
            "the uri resolves against the manifest fileprovider authority",
            context.packageName + ".fileprovider",
            uri.authority,
        )

        assertTrue(
            "the read-grant flag is set so the receiving app can open the file",
            share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
    }

    @Test
    fun buildShareIntentUsesCsvMimeForACsvExport() {
        val csv = TripExportShareIntent.writeExport(context, sampleRoute(), TripExportShareIntent.Format.CSV)
        assertNotNull(csv)
        val intent = TripExportShareIntent.buildShareIntent(context, csv!!)

        assertNotNull(intent)
        assertEquals("the CSV MIME is set on the intent", "text/csv", intent!!.type)
        assertEquals("content", intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.scheme)
    }

    @Test
    fun buildShareIntentForAnAllTripsCsvSharesViaTheSameAuthority() {
        val export = TripExportShareIntent.writeAllTripsCsv(context, sampleAllTrips(), 5)
        assertNotNull("a non-empty all-trips bundle must produce a file", export)
        val intent = TripExportShareIntent.buildShareIntent(context, export!!)

        assertNotNull(intent)
        val uri = intent!!.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        assertEquals(context.packageName + ".fileprovider", uri?.authority)
        assertEquals("text/csv", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

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

        private fun sampleAllTrips(): JSONArray {
            val trips = JSONArray()
            trips.put(JSONObject().put("tripId", 7).put("label", "Commute").put("route", sampleRoute()))
            val secondPoints = JSONArray()
            secondPoints.put(
                JSONObject()
                    .put("atMs", 40_000L)
                    .put("lat", 34.1)
                    .put("lng", -118.45)
                    .put("speedMps", 9.0),
            )
            secondPoints.put(
                JSONObject()
                    .put("atMs", 50_000L)
                    .put("lat", 34.2)
                    .put("lng", -118.50)
                    .put("speedMps", 8.0),
            )
            trips.put(
                JSONObject()
                    .put("tripId", 8)
                    .put("label", "Errand")
                    .put(
                        "route",
                        JSONObject()
                            .put("session", JSONObject().put("id", "8:40000:50000").put("sessionId", 8))
                            .put("points", secondPoints),
                    ),
            )
            return trips
        }

        private fun resetFileProviderCache() {
            try {
                val field = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
                field.isAccessible = true
                (field.get(null) as? MutableMap<*, *>)?.clear()
            } catch (_: ReflectiveOperationException) {
                // Field renamed in a future androidx version: the share-intent tests may become
                // order-sensitive again, but we don't want to mask that with a crash here.
            }
        }

        private fun wipe(dir: File) {
            if (dir.isDirectory) {
                dir.listFiles()?.forEach { it.delete() }
                dir.delete()
            }
        }
    }
}
