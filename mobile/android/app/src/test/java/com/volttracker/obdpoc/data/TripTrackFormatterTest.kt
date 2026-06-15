package com.volttracker.obdpoc.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the GPX/CSV serializers. These are the only thing standing between a logged
 * drive and a file a user hands to Strava/Garmin/a spreadsheet, so the structure (valid GPX 1.1
 * track, right trkpt count + a <time>; CSV header + N rows with the right lat/lng) is pinned here.
 * No Robolectric: the formatter is `org.json`-only, and the build wires the real org.json into unit
 * tests.
 */
class TripTrackFormatterTest {
    @Test
    fun gpxEmitsOneTrkptPerPointWithTimeAndCoordinates() {
        val route = routeWith(SAMPLE_POINTS)

        val gpx = TripTrackFormatter.toGpx(route)

        assertTrue("must declare GPX 1.1", gpx.contains("<gpx version=\"1.1\""))
        assertTrue("must open a single track segment", gpx.contains("<trkseg>"))
        assertEquals("one trkpt per point", SAMPLE_POINTS.size, countOccurrences(gpx, "<trkpt "))
        // First point carries lat/lon, a <time>, an <ele> and the speed extension.
        assertTrue("first trkpt lat", gpx.contains("lat=\"32.7\""))
        assertTrue("first trkpt lon", gpx.contains("lon=\"-117.1\""))
        assertEquals("one <time> per point", SAMPLE_POINTS.size, countOccurrences(gpx, "<time>"))
        assertTrue("ISO-8601 UTC time", gpx.contains("<time>1970-01-01T00:00:10Z</time>"))
        assertTrue("altitude element", gpx.contains("<ele>5</ele>"))
        assertTrue("speed extension", gpx.contains("<speed>12.5</speed>"))
        assertTrue("document is closed", gpx.trim().endsWith("</gpx>"))
    }

    @Test
    fun gpxEscapesTrackNameSoAdapterNameCannotBreakTheXml() {
        val route = routeWith(SAMPLE_POINTS)
        route.getJSONObject("session").put("adapterName", "A & B <hax>")

        val gpx = TripTrackFormatter.toGpx(route)

        assertTrue("ampersand escaped", gpx.contains("A &amp; B &lt;hax&gt;"))
        assertFalse("raw injected tag must not survive", gpx.contains("<hax>"))
    }

    @Test
    fun gpxForEmptyRouteIsStillValidWithNoTrackpoints() {
        val gpx = TripTrackFormatter.toGpx(routeWith(emptyList()))

        assertTrue(gpx.contains("<gpx version=\"1.1\""))
        assertEquals("no trkpt for an empty route", 0, countOccurrences(gpx, "<trkpt "))
        assertTrue(gpx.trim().endsWith("</gpx>"))
    }

    @Test
    fun csvHasHeaderThenOneRowPerPointWithCorrectLatLng() {
        val csv = TripTrackFormatter.toCsv(routeWith(SAMPLE_POINTS))

        val lines = csv.trim().lines()
        assertEquals("header + one row per point", SAMPLE_POINTS.size + 1, lines.size)
        assertEquals(TripTrackFormatter.CSV_HEADER, lines[0])
        // index,timestamp_iso,epoch_ms,latitude,longitude,...
        val firstCols = lines[1].split(",")
        assertEquals("0", firstCols[0])
        assertEquals("1970-01-01T00:00:10Z", firstCols[1])
        assertEquals("10000", firstCols[2])
        assertEquals("32.7", firstCols[3])
        assertEquals("-117.1", firstCols[4])
        assertEquals("12.5", firstCols[5])
        // Third point's soc rides along in the last column. Whole-number coordinates render
        // without a trailing ".0" (plain decimal), which numeric CSV/GPX readers parse identically.
        val thirdCols = lines[3].split(",")
        assertEquals("33", thirdCols[3])
        assertEquals("88.5", thirdCols[9])
    }

    @Test
    fun csvLeavesMissingFieldsBlankRatherThanZero() {
        val points = listOf(JSONObject().put("lat", 1.0).put("lng", 2.0).put("atMs", 0L))
        val csv = TripTrackFormatter.toCsv(routeWith(points))

        // Kotlin's split keeps trailing empty fields by default (limit = 0).
        val cols = csv.trim().lines()[1].split(",")
        assertEquals("1", cols[3])
        assertEquals("2", cols[4])
        assertEquals("blank speed (no value)", "", cols[5])
        assertEquals("blank soc (no value)", "", cols[9])
        // atMs == 0 is treated as "no timestamp", so the timestamp columns stay blank.
        assertEquals("blank iso", "", cols[1])
        assertEquals("blank epoch", "", cols[2])
    }

    @Test
    fun nonFiniteCoordinatesAreRejectedRatherThanCrashingTheExport() {
        // org.json rejects ±Infinity on put(), but an overflowing literal in a parsed payload
        // (e.g. "1e9999") yields optDouble == Infinity. The old isNaN()-only guard let it through to
        // coord()/toBigDecimal(), which throws on infinities — aborting the whole GPX/CSV export.
        val route =
            JSONObject(
                """
                {"session":{"id":"1:0:0","sessionId":1,"adapterName":"OBDLink"},
                 "points":[
                   {"atMs":10000,"lat":1e9999,"lng":-117.1,"speedMps":12.5},
                   {"atMs":20000,"lat":32.8,"lng":-1e9999},
                   {"atMs":30000,"lat":33.0,"lng":-117.2,"speedMps":14.0}
                 ]}
                """.trimIndent(),
            )

        // Only the one finite point survives — and neither serializer throws.
        assertEquals("non-finite points are not counted", 1, TripTrackFormatter.pointCount(route))
        val gpx = TripTrackFormatter.toGpx(route)
        assertEquals("only the finite trkpt is emitted", 1, countOccurrences(gpx, "<trkpt "))
        assertTrue("the finite point is the one written", gpx.contains("lat=\"33\""))
        assertTrue("document still closes cleanly", gpx.trim().endsWith("</gpx>"))

        val csv = TripTrackFormatter.toCsv(route)
        // CSV writes a row per point but blanks the non-finite coordinate cells (no crash).
        val rows = csv.trim().lines()
        assertEquals("header + one row per point", 4, rows.size)
        assertEquals("non-finite lat blanked", "", rows[1].split(",")[3])
        assertEquals("non-finite lng blanked", "", rows[2].split(",")[4])
    }

    @Test
    fun pointCountIgnoresEntriesMissingCoordinates() {
        val points =
            listOf(
                JSONObject().put("lat", 1.0).put("lng", 2.0),
                JSONObject().put("lat", 3.0), // no lng -> skipped
                JSONObject().put("lng", 4.0), // no lat -> skipped
            )
        assertEquals(1, TripTrackFormatter.pointCount(routeWith(points)))
        assertEquals(0, TripTrackFormatter.pointCount(JSONObject()))
    }

    @Test
    fun fileBaseNameDerivesFromStartTimeAndSessionId() {
        val route = routeWith(SAMPLE_POINTS)
        route.getJSONObject("session").put("startedAtMs", 0L).put("sessionId", 42)

        val base = TripTrackFormatter.fileBaseName(route)

        assertTrue("carries the trip date stamp: $base", base.startsWith("volt-trip-1970-01-01-0000"))
        assertTrue("carries the session id suffix: $base", base.endsWith("-42"))
    }

    private companion object {
        private val SAMPLE_POINTS =
            listOf(
                point(10_000L, 32.7, -117.1, speedMps = 12.5, altM = 5.0),
                point(20_000L, 32.8, -117.15, speedMps = 13.0),
                point(30_000L, 33.0, -117.2, speedMps = 14.0, soc = 88.5),
            )

        private fun point(
            atMs: Long,
            lat: Double,
            lng: Double,
            speedMps: Double? = null,
            altM: Double? = null,
            soc: Double? = null,
        ): JSONObject {
            val obj = JSONObject().put("atMs", atMs).put("lat", lat).put("lng", lng)
            speedMps?.let { obj.put("speedMps", it) }
            altM?.let { obj.put("altM", it) }
            soc?.let { obj.put("soc", it) }
            return obj
        }

        private fun routeWith(points: List<JSONObject>): JSONObject {
            val arr = JSONArray()
            points.forEach { arr.put(it) }
            return JSONObject()
                .put(
                    "session",
                    JSONObject().put("id", "1:10000:30000").put("sessionId", 1).put("adapterName", "OBDLink"),
                ).put("points", arr)
        }

        private fun countOccurrences(
            haystack: String,
            needle: String,
        ): Int {
            var count = 0
            var index = haystack.indexOf(needle)
            while (index >= 0) {
                count += 1
                index = haystack.indexOf(needle, index + needle.length)
            }
            return count
        }
    }
}
